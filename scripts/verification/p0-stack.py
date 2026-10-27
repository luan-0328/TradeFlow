"""P0 八服务独占回环验收运行器；仅新建依赖，不接管开发实例，不执行构建或部署。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import time
import traceback
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HIDDEN = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
SERVICES = ('user', 'product', 'order', 'payment', 'aftersale', 'payment-simulator', 'settlement', 'gateway')
DATABASES = tuple(service.replace('-', '_') for service in SERVICES if service != 'gateway')


def load_module(name, path):
    """仅导入现有纯函数，不调用其main/prepare或读取现有开发凭据。"""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def base_environment():
    """子进程仅继承操作系统运行必需变量，不继承开发数据库/管理员/模型会话秘密。"""
    allowed = {'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'COMSPEC', 'PATH', 'PATHEXT', 'TEMP', 'TMP',
               'JAVA_HOME', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'PROGRAMDATA',
               'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS'}
    return {k: v for k, v in os.environ.items() if k.upper() in allowed}


def require_artifacts():
    """拒绝旧JAR和旧前端dist；调用者先完成统一package/build，本脚本绝不自行调用Maven/npm build。"""
    shared = [p for module in ROOT.glob('tradeflow-*') if module.name.startswith('tradeflow-common') or module.name == 'tradeflow-contracts'
              for p in (module / 'src/main').rglob('*') if p.is_file()]
    artifacts = {}
    for service in SERVICES:
        # Maven可正确复用未改服务产物；只比较本模块及共享依赖，不能把无依赖的其他服务修改当作旧包。
        sources = shared + [p for p in (ROOT / f'tradeflow-{service}/src/main').rglob('*') if p.is_file()]
        latest = max(p.stat().st_mtime_ns for p in sources)
        jar = ROOT / f'tradeflow-{service}/target/tradeflow-{service}-0.1.0-SNAPSHOT.jar'
        if not jar.is_file() or jar.stat().st_mtime_ns < latest:
            raise RuntimeError('JAR缺失或早于当前生产源码，请主线先完成统一package：' + service)
        with zipfile.ZipFile(jar) as archive:
            if not any(name.startswith('BOOT-INF/') for name in archive.namelist()):
                raise RuntimeError('需要Spring Boot可执行JAR：' + service)
        artifacts[service] = jar
    for app in ('web-user', 'web-admin'):
        index = ROOT / app / 'dist/index.html'
        inputs = [p for folder in (ROOT / app / 'src', ROOT / 'web-shared/src') for p in folder.rglob('*') if p.is_file()]
        if not index.is_file() or any(p.stat().st_mtime_ns > index.stat().st_mtime_ns for p in inputs):
            raise RuntimeError('前端dist缺失或早于当前源码，请主线先完成统一build：' + app)
    return artifacts


def read_nacos(path):
    """只读取本任务明确传入的隔离Nacos ready及其同目录凭据，绝不扫描或修改旧注册中心。"""
    path = Path(path).resolve(strict=True)
    ready = json.loads(path.read_text(encoding='utf-8-sig'))
    endpoint = ready['endpoint']
    parsed = urllib.parse.urlsplit('http://' + endpoint)
    namespace = ready['namespace']
    credential = Path(ready['credentialFilePath']).resolve(strict=True)
    if parsed.hostname != '127.0.0.1' or not parsed.port or parsed.path or parsed.username or parsed.password:
        raise RuntimeError('Nacos必须来自本任务随机回环endpoint')
    if not namespace.startswith('tradeflow-fixture-') or credential.parent != path.parent or not ready.get('sdkProbe', {}).get('passed'):
        raise RuntimeError('Nacos隔离namespace/凭据归属/SDK预检证据不符合本任务约定')
    auth = json.loads(credential.read_text(encoding='utf-8-sig'))
    if not auth.get('username') or not auth.get('password'):
        raise RuntimeError('Nacos隔离凭据缺失')
    return ready, auth


class Stack:
    def __init__(self, args):
        self.args = args
        self.fixture = load_module('p0_message_helpers', ROOT / 'scripts/verification/messaging-fixture.py')
        self.redis = load_module('p0_redis_helpers', ROOT / 'scripts/Prepare-Redis.py')
        # 使用已处理PowerShell 7模块继承问题的只读执行器，保持JSON返回约定。
        self.fixture.powershell = lambda source: json.loads(self.redis.powershell(source) or '[]')
        self.artifacts = require_artifacts()
        self.nacos, self.nacos_auth = read_nacos(args.nacos_ready)
        self.root = ROOT / '.local' / ('p0-stack-' + uuid.uuid4().hex)
        self.root.mkdir(exist_ok=False)
        self.redis.protect(self.root, directory=True)
        self.processes, self.identities, self.logs = {}, {}, []
        self.smtp = None
        self.env = base_environment()
        numbers = self.fixture.free_ports(24)
        self.ports = dict(zip(SERVICES, numbers[:8]))
        self.management = dict(zip(SERVICES, numbers[8:16]))
        self.mysql_port, self.amqp, self.mq_management, self.mq_dist, self.epmd, self.redis_port, self.user_web, self.admin_web = numbers[16:]
        self.credentials = self.root / 'credentials.json'
        self.stop = self.root / 'stop-requested'
        self.report = {'fixtureOnly': True, 'root': str(self.root), 'result': 'starting', 'mavenExecuted': False,
                       'migrationFiles': [], 'runtimeGrantsApplied': [], 'javaServices': []}

    def write(self, name, value):
        self.redis.protected_write(self.root / name, value if isinstance(value, str) else json.dumps(value, indent=2))

    def observe(self):
        """只递归认领本次启动器的后代，记录首次创建时间与映像；后续监听/终止必须再次核对。"""
        inventory = self.fixture.rows(self.fixture.powershell('@(Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId) | ConvertTo-Json -Compress'))
        # 已结束启动器不再作为认领根，避免PID被其他任务复用后误认领。
        owned = {p.pid for p in self.processes.values() if p.poll() is None}
        current = self.fixture.identities(set(self.identities))
        owned |= {pid for pid, identity in current.items() if identity == self.identities[pid]}
        for _ in range(16):
            newer = owned | {int(p['ProcessId']) for p in inventory if int(p['ParentProcessId']) in owned}
            if newer == owned:
                break
            owned = newer
        for pid, identity in self.fixture.identities(owned).items():
            if pid in self.identities and identity != self.identities[pid]:
                raise RuntimeError('已登记fixture进程PID复用，拒绝继续认领')
            self.identities.setdefault(pid, identity)
        self.write('processes.json', list(self.identities.values()))
        current = self.fixture.identities(set(self.identities))
        matched = {pid for pid, identity in current.items() if identity == self.identities[pid]}
        return self.fixture.listening(matched)

    def owned_port(self, port, bound=None):
        bound = self.observe() if bound is None else bound
        return any(int(row['LocalPort']) == port for row in bound)

    def spawn(self, name, command, env=None, cwd=None):
        """无可见窗口；命令行只含非秘密配置，秘密使用最小环境或受保护配置文件。"""
        log = (self.root / (name + '.log')).open('wb')
        self.logs.append(log)
        process = subprocess.Popen(command, cwd=cwd or self.root, env=env or self.env,
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=log, creationflags=HIDDEN)
        self.processes[name] = process
        for pid, identity in self.fixture.identities({process.pid}).items():
            self.identities[pid] = identity
        return process

    def wait(self, name, predicate, timeout=120):
        """依赖端口先归属核验后才探测；就绪有界，任何已启动业务退出都立即失败。"""
        deadline = time.monotonic() + timeout
        last_status = 0
        while time.monotonic() < deadline:
            if self.stop.exists():
                raise RuntimeError('本次fixture收到停止请求')
            if any(p.poll() is not None for p in self.processes.values()):
                raise RuntimeError('本次fixture进程提前退出：' + name)
            bound = self.observe()
            try:
                if predicate(bound):
                    print(json.dumps({'phase': 'component-ready', 'component': name, 'root': str(self.root)}), flush=True)
                    return
            except (OSError, ValueError, TimeoutError, subprocess.TimeoutExpired):
                pass
            if time.monotonic() - last_status > 30:
                print(json.dumps({'phase': 'waiting', 'component': name}), flush=True)
                last_status = time.monotonic()
            time.sleep(.5)
        raise RuntimeError('独占fixture就绪超时：' + name)

    def mysql(self, statement, database=None):
        """每次写库先证明本次mysqld仍持有随机端口；根账号仅用于新建隔离库/运行账号，不交给JVM。"""
        if not self.owned_port(self.mysql_port):
            raise RuntimeError('自建MySQL监听归属丢失，拒绝写入')
        result = subprocess.run(self.client + ([] if database is None else [database]),
                                input=statement.encode() if isinstance(statement, str) else statement,
                                capture_output=True, timeout=60, env=self.env, creationflags=HIDDEN)
        if result.returncode:
            self.write('mysql-client-error.log', result.stderr.decode('utf-8', errors='replace'))
            raise RuntimeError('自建MySQL迁移/授权失败，详情仅在受保护日志')
        return result.stdout

    def secrets(self):
        """JWT与所有数据库/MQ/内部凭据均为本fixture新生成；文件权限先收紧再写秘密。"""
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.hazmat.primitives import serialization
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        self.write('jwt-private.pem', key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode())
        self.write('jwt-public.pem', key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode())
        self.values = {
            'MYSQL_HOST': '127.0.0.1', 'MYSQL_PORT': str(self.mysql_port),
            'TRADEFLOW_JWT_PRIVATE_KEY': (self.root / 'jwt-private.pem').as_posix(),
            'TRADEFLOW_JWT_PUBLIC_KEY': (self.root / 'jwt-public.pem').as_posix(), 'TRADEFLOW_JWT_ISSUER': 'tradeflow',
            'TRADEFLOW_BOOTSTRAP_ENABLED': 'true', 'TRADEFLOW_BOOTSTRAP_USERNAME': 'p0_admin_' + secrets.token_hex(6),
            'TRADEFLOW_BOOTSTRAP_PASSWORD': 'Tf!9' + secrets.token_hex(24),
            'TRADEFLOW_MONITORING_TOKEN': secrets.token_hex(32),
            'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': str(self.redis_port), 'REDIS_PASSWORD': secrets.token_hex(32),
            'RABBITMQ_HOST': '127.0.0.1', 'RABBITMQ_PORT': str(self.amqp), 'RABBITMQ_VHOST': '/tradeflow',
            'TRADEFLOW_MESSAGING_ENABLED': 'true', 'TRADEFLOW_NACOS_CONFIG_ENABLED': 'false', 'TRADEFLOW_SENTINEL_ENABLED': 'false',
            'NACOS_SERVER_ADDR': self.nacos['endpoint'], 'NACOS_NAMESPACE': self.nacos['namespace'], 'NACOS_GROUP': 'TRADEFLOW',
            'NACOS_USERNAME': self.nacos_auth['username'], 'NACOS_PASSWORD': self.nacos_auth['password'], 'NACOS_DISCOVERY_IP': '127.0.0.1',
            'TRADEFLOW_EVIDENCE_DIRECTORY': str(self.root / 'evidence'),
            'TRADEFLOW_SETTLEMENT_FEE_BPS': '1000', 'TRADEFLOW_SETTLEMENT_FREEZE_DAYS': '0',
            'TRADEFLOW_SETTLEMENT_WORKER_ENABLED': 'true', 'TRADEFLOW_SETTLEMENT_SCAN_DELAY_MS': '1000',
            'TRADEFLOW_RABBITMQ_MONITOR_USER': 'p0_monitor', 'TRADEFLOW_RABBITMQ_MONITOR_PASSWORD': secrets.token_hex(32)}
        for name in ('PRODUCT_SERVICE_TOKEN', 'ORDER_SERVICE_TOKEN', 'PAYMENT_SERVICE_TOKEN', 'AFTERSALE_SERVICE_TOKEN',
                     'CHANNEL_SERVICE_TOKEN', 'INTERNAL_SETTLEMENT_TOKEN', 'CALLBACK_SECRET', 'SIMULATOR_ADMIN_TOKEN'):
            self.values['TRADEFLOW_' + name] = secrets.token_hex(32)
        for service in DATABASES:
            self.values[f'TRADEFLOW_{service.upper()}_DB_USER'] = 'p0_' + service
            self.values[f'TRADEFLOW_{service.upper()}_DB_PASSWORD'] = secrets.token_hex(24)
        for service in ('PAYMENT', 'ORDER', 'AFTERSALE'):
            self.values[f'TRADEFLOW_{service}_MQ_USER'] = 'p0_' + service.lower()
            self.values[f'TRADEFLOW_{service}_MQ_PASSWORD'] = secrets.token_hex(32)
        self.mq_admin = {'user': 'p0_broker_admin', 'password': secrets.token_hex(32)}
        # 对验收脚本提供稳定结构，同时保留显式env键便于按原配置名取值；不打印该对象。
        self.write('credentials.json', {'fixtureOnly': True,
            'bootstrapUsername': self.values['TRADEFLOW_BOOTSTRAP_USERNAME'], 'bootstrapPassword': self.values['TRADEFLOW_BOOTSTRAP_PASSWORD'],
            'serviceTokens': {name: self.values['TRADEFLOW_' + variable] for name, variable in {
                'product': 'PRODUCT_SERVICE_TOKEN', 'order': 'ORDER_SERVICE_TOKEN', 'payment': 'PAYMENT_SERVICE_TOKEN',
                'aftersale': 'AFTERSALE_SERVICE_TOKEN', 'settlement': 'INTERNAL_SETTLEMENT_TOKEN', 'channel': 'CHANNEL_SERVICE_TOKEN',
                'callbackSecret': 'CALLBACK_SECRET', 'simulatorAdmin': 'SIMULATOR_ADMIN_TOKEN', 'monitoring': 'MONITORING_TOKEN'}.items()},
            'database': {'port': self.mysql_port, 'rootUser': 'root', 'rootPassword': '',
                'appUsers': {service: {'user': self.values[f'TRADEFLOW_{service.upper()}_DB_USER'],
                    'password': self.values[f'TRADEFLOW_{service.upper()}_DB_PASSWORD'], 'database': 'trade_' + service} for service in DATABASES}},
            'redis': {'port': self.redis_port, 'password': self.values['REDIS_PASSWORD']},
            'rabbit': {'amqpPort': self.amqp, 'managementPort': self.mq_management, **self.mq_admin,
                'monitorUser': self.values['TRADEFLOW_RABBITMQ_MONITOR_USER'], 'monitorPassword': self.values['TRADEFLOW_RABBITMQ_MONITOR_PASSWORD']},
            'environment': self.values})

    def dependencies(self):
        """MySQL、RabbitMQ、epmd和Redis全部使用新目录、新端口和独占cookie；不调用现有prepare入口。"""
        mysql = Path(self.args.mysql_bin).resolve()
        rabbit = Path(self.args.rabbitmq_home).resolve()
        redis_exe = Path(self.args.redis_bin).resolve() / 'redis-server.exe'
        erl = shutil.which('erl.exe')
        if not erl or not (mysql / 'mysqld.exe').is_file() or not (mysql / 'mysql.exe').is_file() or not redis_exe.is_file() or not (rabbit / 'sbin/rabbitmq-server.bat').is_file():
            raise RuntimeError('缺少已有MySQL/RabbitMQ/Erlang/Redis二进制，拒绝自动安装或复用旧实例')
        self.mysql_bin, self.rabbit_home = mysql, rabbit
        data, mq, redis_data = (self.root / name for name in ('mysql-data', 'rabbitmq', 'redis-data'))
        for path in (data, mq, redis_data):
            path.mkdir()
        base = [str(mysql / 'mysqld.exe'), '--no-defaults', f'--basedir={mysql.parent.as_posix()}', f'--datadir={data.as_posix()}']
        initialized = subprocess.run(base + ['--initialize-insecure'], capture_output=True, timeout=120, env=self.env, creationflags=HIDDEN)
        (self.root / 'mysql-initialize.log').write_bytes(initialized.stdout + initialized.stderr)
        initialized.check_returncode()
        self.client = [str(mysql / 'mysql.exe'), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={self.mysql_port}', '--user=root', '--default-character-set=utf8mb4', '--batch']
        self.spawn('mysql', base + ['--bind-address=127.0.0.1', f'--port={self.mysql_port}', '--mysqlx=OFF', '--skip-log-bin',
                   '--innodb-buffer-pool-size=96M', '--default-time-zone=+00:00', '--transaction-isolation=READ-COMMITTED'])
        cookie = secrets.token_hex(32)
        self.write('.erlang.cookie', cookie)
        appdata = self.root / 'appdata'
        (appdata / 'erlang').mkdir(parents=True)
        self.write('appdata/erlang/.erlang.cookie', cookie)
        erlang_home = Path(erl).resolve().parents[1]
        candidates = [erlang_home / 'bin/epmd.exe'] if (erlang_home / 'bin/epmd.exe').is_file() else list(erlang_home.glob('erts-*/bin/epmd.exe'))
        if len(candidates) != 1:
            raise RuntimeError('当前Erlang epmd二进制不唯一')
        self.node = 'tf_p0_' + uuid.uuid4().hex[:16] + '@localhost'
        self.rabbit_env = self.env | dict(APPDATA=str(appdata), LOCALAPPDATA=str(self.root / 'localappdata'), ERLANG_HOME=str(erlang_home),
            ERL_EPMD_ADDRESS='127.0.0.1', ERL_EPMD_PORT=str(self.epmd), RABBITMQ_BASE=str(mq), RABBITMQ_MNESIA_BASE=str(mq / 'db'),
            RABBITMQ_LOG_BASE=str(mq / 'log'), RABBITMQ_CONFIG_FILE=str(mq / 'rabbitmq'), RABBITMQ_ENABLED_PLUGINS_FILE=str(mq / 'enabled_plugins'),
            RABBITMQ_CONF_ENV_FILE=str(mq / 'empty-env.bat'), RABBITMQ_NODENAME=self.node, RABBITMQ_NODE_PORT=str(self.amqp), RABBITMQ_DIST_PORT=str(self.mq_dist),
            RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='-kernel inet_dist_use_interface {127,0,0,1} +S 2:2 -start_epmd false',
            RABBITMQ_CTL_ERL_ARGS='+S 1:1', ERL_CRASH_DUMP=str(self.root / 'erl_crash.dump'))
        topology = load_module('p0_topology', ROOT / 'scripts/Prepare-RabbitMQ.py')
        self.write('rabbitmq/empty-env.bat', '@echo off\n')
        self.write('rabbitmq/enabled_plugins', '[rabbitmq_management].\n')
        self.write('rabbitmq/definitions.json', topology.definitions(self.mq_admin, self.values))
        self.write('rabbitmq/rabbitmq.conf', f'''listeners.tcp.1 = 127.0.0.1:{self.amqp}
management.tcp.ip = 127.0.0.1
management.tcp.port = {self.mq_management}
distribution.listener.interface = 127.0.0.1
load_definitions = {(mq / 'definitions.json').as_posix()}
default_user = {self.mq_admin['user']}
default_pass = {self.mq_admin['password']}
vm_memory_high_watermark.absolute = 256MiB
disk_free_limit.absolute = 256MB
log.console = false
''')
        self.spawn('epmd', [str(candidates[0]), '-address', '127.0.0.1', '-port', str(self.epmd)], self.rabbit_env)
        self.spawn('rabbitmq', ['cmd.exe', '/d', '/c', str(rabbit / 'sbin/rabbitmq-server.bat')], self.rabbit_env)
        self.write('redis.conf', f'''bind 127.0.0.1
port {self.redis_port}
protected-mode yes
requirepass {self.values['REDIS_PASSWORD']}
dir "{self.redis.redis_path(redis_data)}"
appendonly no
save ""
daemonize no
logfile "{self.redis.redis_path(self.root / 'redis-server.log')}"
''')
        self.spawn('redis', [str(redis_exe), self.redis.redis_path(self.root / 'redis.conf')], cwd=redis_exe.parent)

        def dependencies_ready(bound):
            expected = {self.mysql_port, self.amqp, self.mq_management, self.mq_dist, self.epmd, self.redis_port}
            if not expected.issubset({int(row['LocalPort']) for row in bound}):
                return False
            nodes = self.fixture.api(self.mq_management, self.mq_admin, 'nodes')
            if len(nodes) != 1 or nodes[0]['name'] != self.node or not nodes[0].get('running'):
                return False
            probe = subprocess.run(self.client + ['-e', 'SELECT 1'], capture_output=True, timeout=5, env=self.env, creationflags=HIDDEN)
            with socket.create_connection(('127.0.0.1', self.redis_port), timeout=3) as sock, sock.makefile('rwb') as stream:
                self.redis.resp(stream, 'AUTH', self.values['REDIS_PASSWORD'])
                pong = self.redis.resp(stream, 'PING')
            return probe.returncode == 0 and pong == b'+PONG\r\n'
        self.wait('isolated-dependencies', dependencies_ready)
        self.report['rabbitmqNodeConfirmed'] = self.node

    def migrate(self):
        """每服务一次已核验连接合并迁移/建运行账号/授权；每文件仍保留SHA并检查执行期间原文件未变。"""
        grants = load_module('p0_runtime_grants', ROOT / 'scripts/deployment_runtime_grants.py')
        for service in DATABASES:
            db = 'trade_' + service
            directory = ROOT / ('tradeflow-' + service.replace('_', '-')) / 'src/main/resources/db/migration'
            migrations = sorted(directory.glob('V*__*.sql'), key=lambda path: int(re.match(r'V(\d+)__', path.name).group(1)))
            if not migrations:
                raise RuntimeError('服务迁移目录为空：' + service)
            files = [(migration, migration.read_bytes()) for migration in migrations]
            records = [{'path': str(path.relative_to(ROOT)), 'sha256': hashlib.sha256(content).hexdigest()} for path, content in files]
            username = self.values[f'TRADEFLOW_{service.upper()}_DB_USER']
            password = self.values[f'TRADEFLOW_{service.upper()}_DB_PASSWORD']
            statements = [f'CREATE DATABASE {db} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\nUSE {db};'.encode()]
            statements += [content for path, content in files]
            statements += [f"CREATE USER '{username}'@'127.0.0.1' IDENTIFIED BY '{password}';".encode(),
                           grants.runtime_grants(service, self.values).replace("@'%'", "@'127.0.0.1'").encode()]
            print(json.dumps({'phase': 'migrating', 'service': service, 'migrationCount': len(files)}), flush=True)
            self.mysql(b'\n\n'.join(statements))
            if any(path.read_bytes() != content for path, content in files):
                raise RuntimeError('迁移执行期间源码文件变更，拒绝继续：' + service)
            self.report['migrationFiles'].extend(records)
            self.report['runtimeGrantsApplied'].append(service)
            self.write('migration-report.json', self.report)
            print(json.dumps({'phase': 'migrated', 'service': service, 'migrationCount': len(files)}), flush=True)

    def service_environment(self, service):
        """按Start-Local授权矩阵分配最小凭据；网关无数据库/内部Token，迁移root与MQ管理员不进入任何业务进程。"""
        public = {'MYSQL_HOST', 'MYSQL_PORT', 'TRADEFLOW_MONITORING_TOKEN', 'TRADEFLOW_JWT_PUBLIC_KEY', 'TRADEFLOW_JWT_ISSUER',
                  'TRADEFLOW_NACOS_CONFIG_ENABLED', 'TRADEFLOW_SENTINEL_ENABLED', 'NACOS_SERVER_ADDR', 'NACOS_NAMESPACE',
                  'NACOS_GROUP', 'NACOS_USERNAME', 'NACOS_PASSWORD', 'NACOS_DISCOVERY_IP'}
        purpose = {
            'user': ('USER_DB_USER', 'USER_DB_PASSWORD', 'JWT_PRIVATE_KEY', 'BOOTSTRAP_ENABLED', 'BOOTSTRAP_USERNAME', 'BOOTSTRAP_PASSWORD', 'PRODUCT_SERVICE_TOKEN', 'ORDER_SERVICE_TOKEN', 'PAYMENT_SERVICE_TOKEN', 'AFTERSALE_SERVICE_TOKEN', 'INTERNAL_SETTLEMENT_TOKEN'),
            'product': ('PRODUCT_DB_USER', 'PRODUCT_DB_PASSWORD', 'PRODUCT_SERVICE_TOKEN', 'ORDER_SERVICE_TOKEN'),
            'order': ('ORDER_DB_USER', 'ORDER_DB_PASSWORD', 'ORDER_MQ_USER', 'ORDER_MQ_PASSWORD', 'PRODUCT_SERVICE_TOKEN', 'ORDER_SERVICE_TOKEN', 'PAYMENT_SERVICE_TOKEN', 'AFTERSALE_SERVICE_TOKEN', 'INTERNAL_SETTLEMENT_TOKEN'),
            'payment': ('PAYMENT_DB_USER', 'PAYMENT_DB_PASSWORD', 'PAYMENT_MQ_USER', 'PAYMENT_MQ_PASSWORD', 'PAYMENT_SERVICE_TOKEN', 'ORDER_SERVICE_TOKEN', 'CHANNEL_SERVICE_TOKEN', 'AFTERSALE_SERVICE_TOKEN', 'CALLBACK_SECRET', 'INTERNAL_SETTLEMENT_TOKEN'),
            'aftersale': ('AFTERSALE_DB_USER', 'AFTERSALE_DB_PASSWORD', 'AFTERSALE_MQ_USER', 'AFTERSALE_MQ_PASSWORD', 'AFTERSALE_SERVICE_TOKEN', 'EVIDENCE_DIRECTORY'),
            'payment-simulator': ('PAYMENT_SIMULATOR_DB_USER', 'PAYMENT_SIMULATOR_DB_PASSWORD', 'CHANNEL_SERVICE_TOKEN', 'CALLBACK_SECRET', 'SIMULATOR_ADMIN_TOKEN'),
            'settlement': ('SETTLEMENT_DB_USER', 'SETTLEMENT_DB_PASSWORD', 'INTERNAL_SETTLEMENT_TOKEN', 'SETTLEMENT_FEE_BPS', 'SETTLEMENT_FREEZE_DAYS', 'SETTLEMENT_WORKER_ENABLED', 'SETTLEMENT_SCAN_DELAY_MS'),
            'gateway': ()}[service]
        allowed = public | {'TRADEFLOW_' + value for value in purpose}
        if service in ('order', 'payment', 'aftersale'):
            allowed |= {'RABBITMQ_HOST', 'RABBITMQ_PORT', 'RABBITMQ_VHOST', 'TRADEFLOW_MESSAGING_ENABLED'}
        if service in ('gateway', 'product', 'user'):
            allowed |= {'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD'}
        if service == 'gateway':
            allowed -= {'MYSQL_HOST', 'MYSQL_PORT'}
        result = self.env | {k: v for k, v in self.values.items() if k in allowed}
        if service == 'user':
            result['SPRING_APPLICATION_JSON'] = json.dumps({'spring': {'mail': {'host': '127.0.0.1', 'port': self.smtp.port,
                'username': 'fixture@example.test', 'password': '', 'properties': {'mail.smtp.auth': 'false', 'mail.smtp.ssl.enable': 'false'}}}})
        return result

    def applications(self):
        """dev profile实际注册Nacos并使用lb路由；不注入固定下游地址，不启用fake或回退，逐服务等待真实readiness。"""
        java = shutil.which('java.exe')
        node = shutil.which('node.exe')
        vite = ROOT / 'node_modules/vite/bin/vite.js'
        if not java or not node or not vite.is_file():
            raise RuntimeError('缺少现有Java/Node/Vite，拒绝自动安装')
        # Oracle javapath是转启真实JVM的shim；Popen直接绑定java.home/bin避免shim退出误判服务死亡。
        probe = subprocess.run([java, '-XshowSettings:properties', '-version'], capture_output=True,
                               timeout=20, env=self.env, creationflags=HIDDEN)
        java_home = re.search(r'^\s*java.home\s*=\s*(.+)$', probe.stderr.decode(errors='replace'), re.MULTILINE)
        if probe.returncode or java_home is None:
            self.write('java-probe.log', (probe.stdout + probe.stderr).decode(errors='replace'))
            raise RuntimeError('无法定位真实JVM java.home，拒绝通过shim启动业务')
        java = Path(java_home.group(1).strip()) / 'bin/java.exe'
        if not java.is_file():
            raise RuntimeError('java.home缺少真实bin/java.exe')
        java = str(java.resolve())
        self.report['javaExecutable'] = java
        for service in SERVICES:
            command = [java, '-Xms32m', '-Xmx192m' if service in ('order', 'payment', 'gateway') else '-Xmx128m',
                '-XX:MaxMetaspaceSize=160m', '-XX:ReservedCodeCacheSize=48m', '-XX:MaxDirectMemorySize=32m',
                '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC', '-Dfile.encoding=UTF-8', f'-Duser.home={self.root / "client-cache" / service}', '-jar', str(self.artifacts[service]),
                '--spring.profiles.active=dev', '--server.address=127.0.0.1', f'--server.port={self.ports[service]}',
                '--management.server.address=127.0.0.1', f'--management.server.port={self.management[service]}',
                # Alibaba initializer不判断enabled，仍按dev eager初始化命令端口；fixture同时关闭eager，保持全部监听仅回环。
                '--spring.cloud.sentinel.enabled=false', '--spring.cloud.sentinel.eager=false', '--spring.cloud.sentinel.scg.enabled=false', '--feign.sentinel.enabled=false', '--spring.flyway.enabled=false',
                '--spring.datasource.hikari.maximum-pool-size=3', '--spring.datasource.hikari.minimum-idle=1',
                '--spring.cloud.nacos.discovery.ip=127.0.0.1', f'--spring.cloud.nacos.discovery.port={self.ports[service]}']
            self.spawn(service, command, self.service_environment(service), ROOT)
            port = self.management[service]

            def ready(bound, port=port, service=service):
                if not self.owned_port(port, bound) or not self.owned_port(self.ports[service], bound):
                    return False
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health/readiness', timeout=3) as response:
                    return json.load(response).get('status') == 'UP'
            self.wait(service, ready, timeout=self.args.startup_timeout)
            self.report['javaServices'].append(service)
        for app, port in (('web-user', self.user_web), ('web-admin', self.admin_web)):
            env = self.env | {'TRADEFLOW_GATEWAY_TARGET': f'http://127.0.0.1:{self.ports["gateway"]}'}
            self.spawn(app, [node, str(vite), 'preview', '--host', '127.0.0.1', '--port', str(port), '--strictPort'], env, ROOT / app)

            def web_ready(bound, port=port):
                if not self.owned_port(port, bound):
                    return False
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/', timeout=3) as response:
                    return response.status == 200
            self.wait(app, web_ready, timeout=45)

    def run(self):
        """就绪后写可审阅的非秘密ready；hold有界，收到本fixture stop文件立即进入仅自建进程清理。"""
        print(json.dumps({'phase': 'starting', 'root': str(self.root)}), flush=True)
        try:
            self.secrets()
            from smtp_fixture import SmtpFixture
            self.smtp = SmtpFixture(self.root / 'smtp-messages.json')
            self.dependencies()
            self.migrate()
            self.applications()
            ready = {'fixtureOnly': True, 'root': str(self.root), 'gatewayUrl': f'http://127.0.0.1:{self.ports["gateway"]}',
                'userWebUrl': f'http://127.0.0.1:{self.user_web}', 'adminWebUrl': f'http://127.0.0.1:{self.admin_web}',
                'ports': self.ports, 'managementPorts': self.management, 'credentialFilePath': str(self.credentials),
                'smtpMessagesPath': str(self.root / 'smtp-messages.json'),
                'stopFilePath': str(self.stop), 'nacosNamespace': self.nacos['namespace'], 'nacosEndpoint': self.nacos['endpoint'],
                'dependencyPorts': {'mysql': self.mysql_port, 'amqp': self.amqp, 'rabbitManagement': self.mq_management, 'redis': self.redis_port}}
            self.write('ready.json', ready)
            self.report['result'] = 'ready'
            print(json.dumps({'phase': 'ready', 'readyFilePath': str(self.root / 'ready.json'), **ready}), flush=True)
            deadline = time.monotonic() + self.args.hold_seconds
            while time.monotonic() < deadline and not self.stop.exists():
                self.observe()
                if any(process.poll() is not None for process in self.processes.values()):
                    raise RuntimeError('hold期间自建进程退出')
                time.sleep(1)
            self.report['result'] = 'stopped-by-request' if self.stop.exists() else 'hold-expired'
        except BaseException as error:
            self.report.update(result='failed', errorType=type(error).__name__, errorMessage=str(error),
                               tracebackFilePath=str(self.root / 'failure-traceback.log'))
            self.write('failure-traceback.log', traceback.format_exc())
            raise
        finally:
            self.cleanup()

    def terminate_verified(self, pid, identity):
        """只按已记录创建时间和映像精准结束单进程，不按名称/端口/命令行片段杀全局Erlang或其他应用。"""
        current = self.fixture.identities({pid}).get(pid)
        if current is None:
            return 'already-exited'
        if current != identity:
            return 'identity-changed-skip'
        expected_path = identity['Path'].replace("'", "''")
        expected_start = identity['Started'].replace("'", "''")
        source = f"$p=Get-Process -Id {pid} -ErrorAction SilentlyContinue;if($p -and $p.Path -eq '{expected_path}' -and $p.StartTime.ToUniversalTime().ToString('o') -eq '{expected_start}'){{Stop-Process -Id {pid} -Force -ErrorAction Stop}};exit 0"
        subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', source], capture_output=True, timeout=15, env=self.env, creationflags=HIDDEN, check=True)
        return 'terminated-verified'

    def cleanup(self):
        """先尽力停止自建Java/Node，再关闭已核验MySQL/Rabbit；任何监听残留均记录失败，保留数据日志供审阅。"""
        errors = []
        actions = []
        if self.smtp is not None:
            self.smtp.close()
        def record_error(stage, error):
            errors.append({'stage': stage, 'type': type(error).__name__, 'message': str(error)})
        try:
            self.observe()
        except Exception as error:
            record_error('observe-before-cleanup', error)
        # 已登记业务及其后代优先退出；Rabbit/MySQL随后用本实例协议优雅关闭。
        dependencies = {'mysqld.exe', 'beam.smp.exe', 'erl.exe', 'epmd.exe', 'erlsrv.exe', 'redis-server.exe'}
        for pid, identity in reversed(list(self.identities.items())):
            if Path(identity.get('Path') or '').name.lower() not in dependencies:
                try:
                    actions.append({'pid': pid, 'phase': 'applications', 'outcome': self.terminate_verified(pid, identity)})
                except Exception as error:
                    record_error('terminate-applications', error)
        try:
            bound = self.observe()
            if hasattr(self, 'client') and self.owned_port(self.mysql_port, bound):
                result = subprocess.run([str(self.mysql_bin / 'mysqladmin.exe'), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={self.mysql_port}', '--user=root', 'shutdown'], capture_output=True, timeout=20, env=self.env, creationflags=HIDDEN)
                (self.root / 'mysql-shutdown.log').write_bytes(result.stdout + result.stderr)
            if hasattr(self, 'rabbit_env') and self.owned_port(self.mq_management, bound):
                nodes = self.fixture.api(self.mq_management, self.mq_admin, 'nodes')
                if len(nodes) == 1 and nodes[0]['name'] == self.node:
                    result = subprocess.run(['cmd.exe', '/d', '/c', str(self.rabbit_home / 'sbin/rabbitmqctl.bat'), 'shutdown'], capture_output=True, timeout=25, env=self.rabbit_env, cwd=self.root, creationflags=HIDDEN)
                    (self.root / 'rabbit-shutdown.log').write_bytes(result.stdout + result.stderr)
        except Exception as error:
            record_error('graceful-dependencies', error)
        for pid, identity in reversed(list(self.identities.items())):
            try:
                actions.append({'pid': pid, 'phase': 'remaining', 'outcome': self.terminate_verified(pid, identity)})
            except Exception as error:
                record_error('terminate-remaining', error)
        for process in self.processes.values():
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                errors.append({'stage': 'wait-launcher', 'type': 'TimeoutExpired', 'message': 'owned launcher did not exit within timeout'})
        remaining = 'inspection-failed'
        try:
            current = self.fixture.identities(set(self.identities))
            still_owned = {pid for pid, identity in current.items() if identity == self.identities[pid]}
            remaining = self.fixture.listening(still_owned)
            alive = list(still_owned)
        except Exception as error:
            record_error('final-inspection', error)
            alive = 'inspection-failed'
        self.report.update(remainingOwnedListeners=remaining, remainingOwnedProcesses=alive,
            ownedServerStopped=remaining == [] and alive == [], cleanupErrors=errors, cleanupActions=actions)
        if not self.report['ownedServerStopped']:
            self.report['result'] = 'cleanup-incomplete'
        self.write('result.json', self.report)
        for log in self.logs:
            log.close()
        print(json.dumps({'phase': 'stopped', 'resultFilePath': str(self.root / 'result.json'),
                          'ownedServerStopped': self.report['ownedServerStopped']}), flush=True)
        if not self.report['ownedServerStopped']:
            raise RuntimeError('自建fixture残留进程或监听，详情见受保护result.json')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nacos-ready', required=True)
    parser.add_argument('--hold-seconds', type=int, default=1800)
    parser.add_argument('--startup-timeout', type=int, default=180)
    parser.add_argument('--mysql-bin', default='D:/mysql-8.0.46-winx64/bin')
    parser.add_argument('--rabbitmq-home', default=str(ROOT / '.local/tools/rabbitmq_server-4.1.8'))
    parser.add_argument('--redis-bin', default='D:/Redis/Redis-7.0.15-Windows-x64-msys2')
    args = parser.parse_args()
    if os.name != 'nt' or not 1 <= args.hold_seconds <= 14400 or not 30 <= args.startup_timeout <= 600:
        parser.error('仅支持本机Windows；hold须为1～14400秒，startup须为30～600秒')
    Stack(args).run()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit('P0独占栈未完成：' + type(error).__name__ + '；未回显秘密，详情见新建fixture日志。') from None
