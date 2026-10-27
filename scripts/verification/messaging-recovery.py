"""P0拓扑告警/真实监听重投/节点重启独占fixture；不连接、复用或停止任何既有实例。"""
import argparse
import base64
import getpass
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import urllib.parse
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs): return None

HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def protect(path, value):
    """运行根目录先移除继承权限，秘密只落在受保护新目录且不传命令行。"""
    path.write_text(value, encoding='utf-8')


def powershell(command):
    result = subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command', command], capture_output=True, check=True, timeout=20, creationflags=HIDDEN)
    return json.loads(result.stdout.decode('utf-8-sig') or '[]')


def rows(value):
    return [value] if isinstance(value, dict) else value


def process_children(parent):
    """仅记录当前启动进程的后代 PID，不回显命令行或读取其他进程秘密。"""
    inventory = rows(powershell('@(Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId) | ConvertTo-Json -Compress'))
    owned = {parent}
    for _ in range(10):
        newer = owned | {int(p['ProcessId']) for p in inventory if int(p['ParentProcessId']) in owned}
        if newer == owned:
            break
        owned = newer
    return owned


def listening(pids):
    """每次访问测试依赖前，先证明监听属于新建进程且全部仅回环。"""
    ids = ','.join(str(pid) for pid in sorted(pids))
    found = rows(powershell(f'@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {{ $_.OwningProcess -in @({ids}) }} | Select-Object LocalAddress,LocalPort,OwningProcess) | ConvertTo-Json -Compress'))
    if any(row['LocalAddress'] not in ('127.0.0.1', '::1') for row in found):
        raise RuntimeError('自建fixture存在非回环监听，停止自建进程并拒绝继续')
    return found


def identities(pids):
    """登记自建后代的映像及创建时间，清理时防PID复用，不依赖命令行包含节点名。"""
    if not pids:
        return {}
    ids = ','.join(str(pid) for pid in sorted(pids))
    found = rows(powershell(f"@(Get-Process -Id {ids} -ErrorAction SilentlyContinue | Select-Object Id,Path,@{{Name='Started';Expression={{$_.StartTime.ToUniversalTime().ToString('o')}}}}) | ConvertTo-Json -Compress"))
    return {int(row['Id']): row for row in found}


def free_ports(count):
    held = []
    try:
        for _ in range(count):
            sock = socket.socket()
            sock.bind(('127.0.0.1', 0))
            held.append(sock)
        return [sock.getsockname()[1] for sock in held]
    finally:
        for sock in held:
            sock.close()


def api(port, secret, path, method='GET', body=None):
    token = base64.b64encode((secret['user'] + ':' + secret['password']).encode()).decode()
    request = urllib.request.Request(f'http://127.0.0.1:{port}/api/' + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': 'Basic ' + token, 'Content-Type': 'application/json'})
    with HTTP.open(request, timeout=5) as response:
        value = response.read()
        return json.loads(value) if value else None


def topology_evidence(runtime, management, admin, fixture_env):
    """真实只读Exporter→Prometheus→告警firing；删一条扇出绑定且保留另一目标，不发布业务消息。"""
    import sys
    script_dir = str(ROOT / 'scripts')
    if script_dir not in sys.path: sys.path.insert(0, script_dir)
    spec = importlib.util.spec_from_file_location('recovery_exporter', ROOT / 'scripts/Rabbit-Exporter.py')
    exporter = importlib.util.module_from_spec(spec); spec.loader.exec_module(exporter)
    exporter_port, prom_port = free_ports(2)
    config = {'managementPort': management, 'listenPort': exporter_port, 'user': fixture_env['TRADEFLOW_RABBITMQ_MONITOR_USER'],
              'password': fixture_env['TRADEFLOW_RABBITMQ_MONITOR_PASSWORD'], 'token': secrets.token_hex(32)}
    protect(runtime / 'exporter.json', json.dumps(config))
    proms = list((ROOT / '.local/tools').glob('prometheus-*/prometheus.exe'))
    if len(proms) != 1: raise RuntimeError('无法唯一定位已有Prometheus发行包')
    # 直接加载生产规则；只启用隔离job，其他业务缺失告警不会连接任何应用。
    rules = (ROOT / 'deploy/prometheus/alerts.yml').as_posix()
    protect(runtime / 'prometheus.yml', f"""global:
  scrape_interval: 1s
  evaluation_interval: 1s
rule_files:
  - '{rules}'
scrape_configs:
  - job_name: tradeflow-rabbitmq
    bearer_token: {config['token']}
    static_configs:
      - targets: ['127.0.0.1:{exporter_port}']
""")
    processes, handles = [], []
    missing = None
    report = {'fixtureOnly': True, 'productionRules': rules}
    def metrics():
        req = urllib.request.Request(f'http://127.0.0.1:{exporter_port}/metrics', headers={'Authorization': 'Bearer '+config['token']})
        with HTTP.open(req, timeout=3) as response: return response.read().decode()
    def alerts():
        with HTTP.open(f'http://127.0.0.1:{prom_port}/api/v1/alerts', timeout=3) as response: return json.load(response)['data']['alerts']
    def await_condition(predicate, seconds, label):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if any(process.poll() is not None for process in processes): raise RuntimeError('隔离监控进程提前退出')
            try:
                if predicate(): return
            except (OSError, ValueError): pass
            time.sleep(.5)
        raise RuntimeError(label)
    try:
        commands = [[sys.executable, str(ROOT / 'scripts/Rabbit-Exporter.py'), str(runtime / 'exporter.json')],
                    [str(proms[0]), '--config.file='+str(runtime / 'prometheus.yml'), '--storage.tsdb.path='+str(runtime / 'prometheus-data'), '--web.listen-address=127.0.0.1:'+str(prom_port)]]
        for index, command in enumerate(commands):
            log = (runtime / f'monitor-{index}.log').open('wb'); handles.append(log)
            processes.append(subprocess.Popen(command, cwd=runtime, stdin=subprocess.DEVNULL, stdout=log, stderr=log, creationflags=HIDDEN))
        await_condition(lambda: 'tradeflow_rabbitmq_topology_valid 1' in metrics(), 40, '初始拓扑采样未成功')
        monitor = {'user': config['user'], 'password': config['password']}
        try:
            # 尝试幂等声明既有绑定，不新增资源；monitor必须被Broker拒绝写入。
            api(management, monitor, 'bindings/%2Ftradeflow/e/tradeflow.events/q/q.aftersale.refund', 'POST', {'routing_key':'refund.succeeded.v1','arguments':{}})
        except urllib.error.HTTPError as error:
            if error.code not in (401, 403): raise
            report['monitorMutationDenied'] = True
        else: raise RuntimeError('monitor账号意外具有拓扑写权限')
        owned = set().union(*(process_children(p.pid) for p in processes))
        report['loopbackListeners'] = listening(owned)
        bindings = api(management, admin, 'bindings/%2Ftradeflow')
        missing = next(row for row in bindings if row['source']=='tradeflow.events' and row['destination']=='q.aftersale.refund' and row['routing_key']=='refund.succeeded.v1')
        path = 'bindings/%2Ftradeflow/e/tradeflow.events/q/q.aftersale.refund/' + urllib.parse.quote(missing['properties_key'], safe='')
        api(management, admin, path, 'DELETE')
        _, checks = exporter.collect_once(config)
        failures = [resource for (_, resource), value in checks.items() if value == 0]
        assert set(failures) == {'tradeflow.events>q.aftersale.refund:refund.succeeded.v1', 'tradeflow.events'}
        report['missingBindingDetected'] = failures
        print(json.dumps({'phase':'topology-drift-awaiting-production-alert','fixture':str(runtime)}), flush=True)
        await_condition(lambda: any(a['labels'].get('alertname')=='TradeFlowRabbitTopologyDrift' and a['state']=='firing' for a in alerts()), 125, '生产拓扑告警未进入firing')
        report['productionAlertFiring'] = True
        (runtime / 'topology-drift.metrics').write_text(metrics(), encoding='utf-8')
        api(management, admin, 'bindings/%2Ftradeflow/e/tradeflow.events/q/q.aftersale.refund', 'POST', {'routing_key':'refund.succeeded.v1','arguments':{}})
        missing = None
        await_condition(lambda: 'tradeflow_rabbitmq_topology_valid 1' in metrics(), 40, '恢复绑定后指标未恢复')
        await_condition(lambda: not any(a['labels'].get('alertname')=='TradeFlowRabbitTopologyDrift' for a in alerts()), 10, '恢复绑定后告警未消退')
        report['bindingRestoredAndAlertResolved'] = True
        # 无效管理凭据必须NaN+collection_success=0，不能把无法读取当成健康拓扑。
        try: exporter.collect_once({**config, 'password':'fixture-invalid'})
        except OSError: report['invalidMonitorCredentialsRejected'] = True
        else: raise RuntimeError('无效monitor凭据未拒绝')
        failed = exporter.render({'values':{}, 'topology':{key:'NaN' for key in checks}, 'success':0, 'last':0}).decode()
        assert 'tradeflow_rabbitmq_topology_valid NaN' in failed and 'tradeflow_rabbitmq_collection_success 0' in failed
    finally:
        if missing is not None:
            api(management, admin, 'bindings/%2Ftradeflow/e/tradeflow.events/q/q.aftersale.refund', 'POST', {'routing_key':'refund.succeeded.v1','arguments':{}})
        for process in reversed(processes):
            if process.poll() is None: process.terminate(); process.wait(timeout=20)
        for handle in handles: handle.close()
        report['ownedMonitoringStopped'] = all(p.poll() is not None for p in processes)
        protect(runtime / 'topology-result.json', json.dumps(report, indent=2))


def run(args):
    if os.name != 'nt':
        raise RuntimeError('本fixture仅支持具备Windows监听归属核验的本机环境')
    mysql = Path(args.mysql_bin).resolve()
    rabbit = Path(args.rabbitmq_home).resolve()
    erl = shutil.which('erl.exe')
    if not erl or not (mysql / 'mysqld.exe').is_file() or not (mysql / 'mysql.exe').is_file() or not (rabbit / 'sbin/rabbitmq-server.bat').is_file():
        raise RuntimeError('缺少已有Erlang/MySQL/RabbitMQ二进制；未下载或修改安装')
    runtime = ROOT / '.local' / ('messaging-recovery-' + uuid.uuid4().hex)
    runtime.mkdir(exist_ok=False)
    subprocess.run(['icacls', str(runtime), '/inheritance:r', '/grant:r', getpass.getuser() + ':(OI)(CI)F'], capture_output=True, check=True, creationflags=HIDDEN)
    data = runtime / 'mysql-data'
    data.mkdir()
    mq = runtime / 'rabbitmq'
    mq.mkdir()
    mysql_port, amqp, management, dist, epmd_port = free_ports(5)
    node = 'tf_fixture_' + uuid.uuid4().hex[:16] + '@localhost'
    marker = uuid.uuid4().hex
    secret = {'user': 'fixture_admin', 'password': secrets.token_hex(32)}
    protect(runtime / 'fixture-secret.json', json.dumps(secret))
    # Erlang 从新 home 的 cookie 文件读取，避免 -setcookie 参数或修改当前用户 cookie。
    cookie = secrets.token_hex(32)
    protect(runtime / '.erlang.cookie', cookie)
    # OTP26在Windows优先使用APPDATA/erlang而非-home；两处均指向本次隔离目录。
    appdata = runtime / 'appdata'
    (appdata / 'erlang').mkdir(parents=True)
    protect(appdata / 'erlang/.erlang.cookie', cookie)
    env = {k: v for k, v in os.environ.items() if k.upper() in {'SYSTEMROOT','WINDIR','COMSPEC','PATH','PATHEXT','TEMP','TMP','JAVA_HOME','MAVEN_OPTS','PROCESSOR_ARCHITECTURE','NUMBER_OF_PROCESSORS','USERPROFILE','APPDATA','LOCALAPPDATA'}}
    erlang_home = Path(erl).resolve().parents[1]
    epmd_candidates = [erlang_home / 'bin/epmd.exe'] if (erlang_home / 'bin/epmd.exe').is_file() else list(erlang_home.glob('erts-*/bin/epmd.exe'))
    if len(epmd_candidates) != 1:
        raise RuntimeError('无法唯一确定当前Erlang安装的epmd，拒绝启动fixture')
    env.update(APPDATA=str(appdata), LOCALAPPDATA=str(runtime / 'localappdata'), ERLANG_HOME=str(erlang_home), ERL_EPMD_ADDRESS='127.0.0.1', ERL_EPMD_PORT=str(epmd_port),
        RABBITMQ_BASE=str(mq), RABBITMQ_MNESIA_BASE=str(mq / 'db'), RABBITMQ_LOG_BASE=str(mq / 'log'),
        RABBITMQ_CONFIG_FILE=str(mq / 'rabbitmq'), RABBITMQ_ENABLED_PLUGINS_FILE=str(mq / 'enabled_plugins'),
        RABBITMQ_CONF_ENV_FILE=str(mq / 'empty-env.bat'), RABBITMQ_NODENAME=node, RABBITMQ_NODE_PORT=str(amqp), RABBITMQ_DIST_PORT=str(dist),
        RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='-kernel inet_dist_use_interface {127,0,0,1} +S 2:2 -start_epmd false',
        RABBITMQ_CTL_ERL_ARGS='+S 1:1', ERL_CRASH_DUMP=str(runtime / 'erl_crash.dump'))
    protect(mq / 'empty-env.bat', '@echo off\n')
    protect(mq / 'enabled_plugins', '[rabbitmq_management].\n')
    # 仅复用纯拓扑构造，不调用会读写开发凭据或固定实例的 settings/prepare/install。
    spec = importlib.util.spec_from_file_location('fixture_topology', ROOT / 'scripts/deployment_rabbit_topology.py')
    topology = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(topology)
    fixture_env = {'TRADEFLOW_RABBITMQ_MONITOR_USER': 'fixture_monitor', 'TRADEFLOW_RABBITMQ_MONITOR_PASSWORD': secrets.token_hex(32)}
    for service in ('PAYMENT', 'ORDER', 'AFTERSALE'):
        fixture_env[f'TRADEFLOW_{service}_MQ_USER'] = 'fixture_' + service.lower()
        fixture_env[f'TRADEFLOW_{service}_MQ_PASSWORD'] = secrets.token_hex(32)
    definitions = topology.definitions(secret, fixture_env)
    protect(mq / 'definitions.json', json.dumps(definitions))
    protect(mq / 'rabbitmq.conf', f'''listeners.tcp.1 = 127.0.0.1:{amqp}
management.tcp.ip = 127.0.0.1
management.tcp.port = {management}
distribution.listener.interface = 127.0.0.1
load_definitions = {(mq / 'definitions.json').as_posix()}
default_user = {secret['user']}
default_pass = {secret['password']}
vm_memory_high_watermark.absolute = 256MiB
disk_free_limit.absolute = 256MB
log.console = false
''')
    metadata = {'fixtureOnly': True, 'root': str(runtime.resolve()), 'marker': marker, 'node': node,
        'mysqlUrl': f'jdbc:mysql://127.0.0.1:{mysql_port}/trade_payment?serverTimezone=UTC', 'amqpPort': amqp, 'managementPort': management}
    protect(runtime / 'runtime.json', json.dumps(metadata, indent=2))
    base = [str(mysql / 'mysqld.exe'), '--no-defaults', f'--basedir={mysql.parent.as_posix()}', f'--datadir={data.as_posix()}']
    client = [str(mysql / 'mysql.exe'), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={mysql_port}', '--user=root', '--default-character-set=utf8mb4', '--batch']
    processes = {}
    owned = set()
    recorded = {}
    logs = []
    report = {'fixture': str(runtime), 'mavenExecuted': False, 'result': 'starting'}
    try:
        initialized = subprocess.run(base + ['--initialize-insecure'], capture_output=True, timeout=90, creationflags=HIDDEN)
        (runtime / 'mysql-initialize.log').write_bytes(initialized.stdout + initialized.stderr)
        initialized.check_returncode()
        for name, command in [('mysql', base + ['--bind-address=127.0.0.1', f'--port={mysql_port}', '--mysqlx=OFF', '--skip-log-bin', '--innodb-buffer-pool-size=64M', '--default-time-zone=+00:00', '--transaction-isolation=READ-COMMITTED']),
                              ('epmd', [str(epmd_candidates[0]), '-address', '127.0.0.1', '-port', str(epmd_port)]),
                              ('rabbit', ['cmd.exe', '/d', '/c', str(rabbit / 'sbin/rabbitmq-server.bat')])]:
            log = (runtime / (name + '-output.log')).open('wb')
            logs.append(log)
            processes[name] = subprocess.Popen(command, cwd=runtime, env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=log, creationflags=HIDDEN)
        print(json.dumps({'phase': 'starting', 'fixture': str(runtime), 'mysqlPort': mysql_port, 'amqpPort': amqp}), flush=True)
        deadline = time.monotonic() + 120
        ready_mysql = ready_rabbit = False
        while time.monotonic() < deadline:
            # Windows环境可能为exe/bat增加启动壳；三种依赖都按完整后代核验监听。
            for process in processes.values():
                owned |= process_children(process.pid)
            for pid, identity in identities(owned).items():
                recorded.setdefault(pid, identity)
            all_ids = owned | {processes['mysql'].pid, processes['epmd'].pid}
            bound = listening(all_ids)
            ports = {int(row['LocalPort']) for row in bound}
            if mysql_port in ports and not ready_mysql:
                probe = subprocess.run(client + ['-e', 'SELECT 1'], capture_output=True, timeout=5, creationflags=HIDDEN)
                ready_mysql = probe.returncode == 0
            if management in ports and not ready_rabbit:
                try:
                    nodes = api(management, secret, 'nodes')
                    ready_rabbit = len(nodes) == 1 and nodes[0]['name'] == node and nodes[0].get('running') and any(int(row['LocalPort']) == management and int(row['OwningProcess']) in owned for row in bound)
                except (OSError, ValueError):
                    pass
            if ready_mysql and ready_rabbit and {amqp, management, dist, epmd_port, mysql_port}.issubset(ports):
                break
            if processes['mysql'].poll() is not None or processes['rabbit'].poll() is not None or processes['epmd'].poll() is not None:
                raise RuntimeError('自建fixture启动进程提前退出；仅保留日志不回显秘密')
            time.sleep(.5)
        else:
            raise RuntimeError('自建消息fixture就绪超时')
        # 所有数据库写入均在进程归属与回环检查通过之后。
        def sql(statement, database=None):
            result = subprocess.run(client + ([] if database is None else [database]), input=statement, capture_output=True, timeout=45, creationflags=HIDDEN)
            if result.returncode:
                (runtime / 'mysql-client-error.log').write_bytes(result.stderr)
                raise RuntimeError('fixture数据库迁移/标记失败，日志保留')
        sql(b'CREATE DATABASE trade_payment CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
        for version in range(1, 5):
            migrations = list((ROOT / 'tradeflow-payment/src/main/resources/db/migration').glob(f'V{version}__*.sql'))
            if len(migrations) != 1:
                raise RuntimeError('支付V1-V4迁移不唯一')
            sql(migrations[0].read_bytes(), 'trade_payment')
        sql(("CREATE TABLE fixture_identity(marker CHAR(32) NOT NULL COMMENT '独占实例随机标记') COMMENT='消息fixture实例归属'; INSERT INTO fixture_identity VALUES ('" + marker + "');").encode(), 'trade_payment')
        report.update(loopbackListeners=bound, brokerNodeConfirmed=True, paymentMigrations=[1,2,3,4])
        print(json.dumps({'phase': 'ready', 'fixture': str(runtime), 'allListenersLoopback': True}), flush=True)
        topology_evidence(runtime, management, secret, fixture_env)
        if args.maven:
            test_env = env.copy()
            test_env['TRADEFLOW_MESSAGING_FIXTURE_DIR'] = str(runtime)
            command = ['cmd.exe', '/d', '/c', str(ROOT / 'mvnw.cmd'), '-B', '-ntp', '-pl', 'tradeflow-payment', '-am', '-Dtest=MessagingRecoveryMysqlTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
            report['mavenExecuted'] = True
            with (runtime / 'maven.log').open('wb') as log:
                result = subprocess.Popen(command, cwd=ROOT, env=test_env, stdout=log, stderr=subprocess.STDOUT, creationflags=HIDDEN)
                processes['maven'] = result
                deadline = time.monotonic() + 360
                restarted = False
                while result.poll() is None:
                    if (runtime / 'restart.request').exists() and not restarted:
                        # 仅在已登记节点和监听归属仍匹配时完整关闭并重启本次节点，保留同一磁盘数据。
                        bound = listening(owned)
                        nodes = api(management, secret, 'nodes')
                        if len(nodes) != 1 or nodes[0]['name'] != node or not any(int(r['LocalPort']) == management for r in bound):
                            raise RuntimeError('重启节点归属不匹配')
                        for process in processes.values(): owned |= process_children(process.pid)
                        for pid, identity in identities(owned).items(): recorded.setdefault(pid, identity)
                        stopped = subprocess.run(['cmd.exe', '/d', '/c', str(rabbit / 'sbin/rabbitmqctl.bat'), 'shutdown'], cwd=runtime, env=env, capture_output=True, timeout=30, creationflags=HIDDEN)
                        (runtime / 'restart-shutdown.log').write_bytes(stopped.stdout + stopped.stderr)
                        stopped.check_returncode()
                        processes['rabbit'].wait(timeout=30)
                        # 重启前节点数据已存在；移除启动期definitions导入，证明绑定和队列由磁盘恢复。
                        config_path = mq / 'rabbitmq.conf'
                        config_path.write_text('\n'.join(line for line in config_path.read_text().splitlines() if not line.startswith('load_definitions')), encoding='utf-8')
                        restart_log = (runtime / 'rabbit-restart-output.log').open('wb'); logs.append(restart_log)
                        processes['rabbit'] = subprocess.Popen(['cmd.exe', '/d', '/c', str(rabbit / 'sbin/rabbitmq-server.bat')], cwd=runtime, env=env, stdin=subprocess.DEVNULL, stdout=restart_log, stderr=restart_log, creationflags=HIDDEN)
                        restart_deadline = time.monotonic() + 90
                        while time.monotonic() < restart_deadline:
                            owned |= process_children(processes['rabbit'].pid)
                            for pid, identity in identities(owned).items(): recorded.setdefault(pid, identity)
                            bounds = listening(owned)
                            if {amqp, management}.issubset({int(r['LocalPort']) for r in bounds}):
                                try:
                                    recovered = api(management, secret, 'nodes')
                                    if len(recovered) == 1 and recovered[0]['name'] == node and recovered[0].get('running'): break
                                except (OSError, ValueError): pass
                            time.sleep(.5)
                        else: raise RuntimeError('隔离Broker重启未就绪')
                        protect(runtime / 'restart.complete', json.dumps({'node': node, 'sameDisk': True, 'definitionsReimported': False}))
                        restarted = True
                    if time.monotonic() > deadline:
                        owned |= process_children(result.pid)
                        for pid, identity in identities(owned).items(): recorded.setdefault(pid, identity)
                        result.terminate(); result.wait(timeout=20)
                        raise RuntimeError('独占Maven超时')
                    time.sleep(.2)
            report['brokerRestarted'] = restarted
            report['mavenExitCode'] = result.returncode
            if result.returncode:
                raise RuntimeError('消息可靠性集成测试失败；证据见独占目录maven.log')
            source_report = ROOT / 'tradeflow-payment/target/surefire-reports/TEST-com.tradeflow.messaging.MessagingRecoveryMysqlTest.xml'
            # target由全工程串行任务复用；复制本次原始报告，后续构建不能覆盖本次证据。
            shutil.copyfile(source_report, runtime / 'MessagingRecoveryMysqlTest.xml')
            suite = ET.parse(runtime / 'MessagingRecoveryMysqlTest.xml').getroot()
            counts = {key: int(suite.attrib.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
            report['surefire'] = counts
            if counts['tests'] != 5 or any(counts[key] != 0 for key in ('failures', 'errors', 'skipped')):
                raise RuntimeError('消息真实测试数量或结果不符，不能把跳过当通过')
            evidence_files = ['listener-nack.json', 'listener-lost-ack.json', 'partial-binding-recovery.json', 'replay-rejections.json', 'broker-restart.json']
            if not restarted or not all((runtime / name).is_file() for name in evidence_files):
                raise RuntimeError('真实故障分支证据缺失')
            report['jvmEvidence'] = evidence_files
        report['finalLoopbackListeners'] = listening(owned | {processes['mysql'].pid, processes['epmd'].pid})
        report['result'] = 'passed' if args.maven else 'fixture-preflight-passed'
    except Exception as error:
        report['result'] = 'failed'
        report['errorType'] = type(error).__name__
        raise
    finally:
        # 优雅关闭只针对已证明归属的新节点；不会按端口或进程名关闭其他服务。
        if 'rabbit' in processes:
            for process in processes.values():
                owned |= process_children(process.pid)
            for pid, identity in identities(owned).items():
                recorded.setdefault(pid, identity)
            try:
                now = listening(owned)
                if any(int(row['LocalPort']) == management for row in now):
                    nodes = api(management, secret, 'nodes')
                    if len(nodes) == 1 and nodes[0]['name'] == node:
                        result = subprocess.run(['cmd.exe', '/d', '/c', str(rabbit / 'sbin/rabbitmqctl.bat'), 'shutdown'], cwd=runtime, env=env, capture_output=True, timeout=30, creationflags=HIDDEN)
                        (runtime / 'rabbit-shutdown.log').write_bytes(result.stdout + result.stderr)
            except Exception:
                pass
            # 若启动未成功或优雅停止失败，逐个核验专属节点/目录后只结束已登记后代。
            for pid in sorted(owned, reverse=True):
                if pid == processes['rabbit'].pid:
                    continue
                identity = recorded.get(pid)
                if not identity:
                    continue
                if identity['Path'].lower().endswith('mysqld.exe'):
                    continue
                expected_path = identity['Path'].replace("'", "''")
                expected_start = identity['Started'].replace("'", "''")
                command = f"$p=Get-Process -Id {pid} -ErrorAction SilentlyContinue; if($p -and $p.Path -eq '{expected_path}' -and $p.StartTime.ToUniversalTime().ToString('o') -eq '{expected_start}'){{ Stop-Process -Id {pid} -Force -ErrorAction SilentlyContinue }}"
                subprocess.run(['powershell','-NoProfile','-NonInteractive','-Command',command], capture_output=True, timeout=15, creationflags=HIDDEN)
        for name in ('rabbit', 'mysql', 'epmd'):
            process = processes.get(name)
            if name == 'mysql' and process is not None and process.poll() is None:
                try:
                    mysql_ids = {pid for pid, identity in recorded.items() if identity['Path'].lower().endswith('mysqld.exe')} | {process.pid}
                    if any(int(row['LocalPort']) == mysql_port for row in listening(mysql_ids)):
                        shutdown = subprocess.run([str(mysql / 'mysqladmin.exe'), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={mysql_port}', '--user=root', 'shutdown'], capture_output=True, timeout=15, creationflags=HIDDEN)
                        (runtime / 'mysql-shutdown.log').write_bytes(shutdown.stdout + shutdown.stderr)
                        if shutdown.returncode == 0:
                            process.wait(timeout=20)
                except Exception:
                    pass
            if process is not None and process.poll() is None:
                process.terminate()
                process.wait(timeout=20)
        for log in logs:
            log.close()
        report['ownedLauncherProcessesStopped'] = all(p.poll() is not None for p in processes.values())
        try:
            report['remainingOwnedListeners'] = listening(owned | {p.pid for p in processes.values()})
        except Exception:
            report['remainingOwnedListeners'] = 'inspection-failed'
        report['ownedServerStopped'] = report['ownedLauncherProcessesStopped'] and report['remainingOwnedListeners'] == []
        if not report['ownedServerStopped']:
            report['result'] = 'cleanup-incomplete'
        protect(runtime / 'result.json', json.dumps(report, indent=2))
        print(json.dumps({'phase': 'stopped', 'report': str(runtime / 'result.json'), 'result': report['result']}), flush=True)
        if not report['ownedServerStopped']:
            raise RuntimeError('自建fixture停止核验未完成，不能报告通过')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin', default='D:/mysql-8.0.46-winx64/bin')
    parser.add_argument('--rabbitmq-home', default=str(ROOT / '.local/tools/rabbitmq_server-4.1.8'))
    parser.add_argument('--maven', action='store_true', help='调用者取得Maven独占窗口后运行真实集成用例')
    try:
        run(parser.parse_args())
    except Exception as error:
        raise SystemExit('独占消息fixture未通过：' + type(error).__name__ + '；未回显秘密，详细日志留在新fixture目录。') from None
