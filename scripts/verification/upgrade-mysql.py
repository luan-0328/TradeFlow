"""新建独占回环MySQL验证U1-U4；不复用现有库，执行前必须取得Maven独占窗口。"""
import concurrent.futures
import hashlib
import getpass
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SERVICES = ('user', 'product', 'order', 'payment', 'payment_simulator', 'aftersale', 'settlement')


def main():
    found = os.environ.get('TRADEFLOW_MYSQL_BIN')
    executable = shutil.which('mysqld')
    if not found and not executable:
        raise RuntimeError('请配置TRADEFLOW_MYSQL_BIN或将MySQL8.0 bin加入PATH；未连接任何数据库')
    binary = Path(found or str(Path(executable).parent)).resolve()
    suffix = '.exe' if os.name == 'nt' else ''
    local = ROOT / '.local' / 'verification'
    local.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='upgrade-mysql-', dir=local)).resolve()
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    data = work / 'data'
    config = work / 'my.ini'
    config.write_text(f"""[mysqld]
basedir={binary.parent.as_posix()}
datadir={data.as_posix()}
port={port}
bind-address=127.0.0.1
mysqlx=0
skip-log-bin
default-time-zone=+00:00
transaction-isolation=READ-COMMITTED
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
innodb-buffer-pool-size=64M
max-allowed-packet=16M
log-error={(work / 'mysql-error.log').as_posix()}
""", encoding='utf-8')
    quiet = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    subprocess.run([str(binary / ('mysqld' + suffix)), f'--defaults-file={config}', '--initialize-insecure'], check=True, capture_output=True, **quiet)
    process = subprocess.Popen([str(binary / ('mysqld' + suffix)), f'--defaults-file={config}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **quiet)
    client = [str(binary / ('mysql' + suffix)), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={port}', '-N', '-B', '--default-character-set=utf8mb4']
    result = {'kind': 'fresh-loopback-mysql-upgrade', 'status': 'FAILED', 'checks': [], 'migrations': [], 'owned_port': port}
    redis_fixture = None

    def execute(statement, user='root', password=''):
        environment = os.environ.copy()
        environment['MYSQL_PWD'] = password
        return subprocess.run(client + ['--user=' + user], input=statement.encode('utf-8'), capture_output=True, timeout=45, env=environment, **quiet)

    def sql(statement, user='root', password=''):
        response = execute(statement, user, password)
        if response.returncode:
            # 不打印SQL，避免未来扩展账号授权时将随机密码写入日志。
            match = re.search(rb'ERROR ([0-9]+)', response.stderr)
            raise RuntimeError('独占升级MySQL命令失败，错误类别=' + (match.group(1).decode() if match else 'UNKNOWN'))
        return response.stdout.decode('utf-8').strip()

    def denied(statement, codes, user='root', password=''):
        response = execute(statement, user, password)
        if not response.returncode or not any(('ERROR ' + str(code)).encode() in response.stderr for code in codes):
            raise AssertionError('预期数据库约束/权限拒绝未发生，或发生非预期错误类别')

    try:
        for _ in range(100):
            if process.poll() is not None:
                raise RuntimeError('本脚本MySQL提前退出')
            try:
                sql('SELECT 1;')
                break
            except RuntimeError as startup_error:
                last_startup_error = str(startup_error)
                time.sleep(.2)
        else:
            raise RuntimeError('本脚本MySQL就绪超时：' + locals().get('last_startup_error', '无客户端诊断'))
        if Path(sql('SELECT @@datadir;')).resolve() != data.resolve():
            raise RuntimeError('datadir归属不符，禁止操作已有实例')
        for service in SERVICES:
            schema = 'trade_' + service
            sql(f'CREATE DATABASE {schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
            directory = ROOT / ('tradeflow-' + service.replace('_', '-')) / 'src/main/resources/db/migration'
            files = sorted(directory.glob('V*.sql'), key=lambda path: int(path.name.split('__')[0][1:]))
            versions = [int(path.name.split('__')[0][1:]) for path in files]
            if versions != list(range(1, max(versions) + 1)):
                raise AssertionError('迁移版本必须从V1连续；不得漏掉中间发布版本')
            for migration in files:
                original = migration.read_bytes()
                sql(f'USE {schema};' + original.decode('utf-8-sig'))
                result['migrations'].append({'path': str(migration.relative_to(ROOT)), 'sha256': hashlib.sha256(original).hexdigest()})
                if migration.read_bytes() != original:
                    raise AssertionError('验证脚本不得修改已发布迁移字节')
            for kind, query in [('字段', 'columns WHERE column_comment'), ('表', "tables WHERE table_type='BASE TABLE' AND table_comment")]:
                view, predicate = query.split(' WHERE ', 1)
                if sql(f"SELECT COUNT(*) FROM information_schema.{view} WHERE table_schema='{schema}' AND {predicate} NOT REGEXP '[一-龥]';") != '0':
                    raise AssertionError(service + kind + '缺少中文COMMENT')
            result['checks'].append(service + '全量V迁移/表字段中文COMMENT')
        sql('CREATE DATABASE trade_settlement_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
        # 地址默认唯一是生成列数据库约束；软删除原地址后允许创建新默认，其他账号互不影响。
        address = "INSERT INTO trade_user.user_address(user_id,recipient,phone,address,default_address) VALUES(7001,'测试','10086','隔离地址',TRUE);"
        sql(address)
        denied(address, [1062])
        sql("INSERT INTO trade_user.user_address(user_id,recipient,phone,address,default_address) VALUES(7002,'测试','10086','隔离地址',TRUE); UPDATE trade_user.user_address SET deleted=TRUE WHERE user_id=7001;" + address)
        result['checks'].append('user_address同账号默认唯一/软删除释放/异账号隔离')
        # 两个真实连接同时争买家原键：一个提交，另一个必须1062；不能用顺序执行代替并发。
        def group(index):
            statement = "START TRANSACTION; INSERT INTO trade_order.trade_group(group_no,user_id,idempotency_key,request_hash) VALUES('grp_fixture_" + str(index) + "',7001,'fixture-group-key','" + 'a'*64 + "'); SELECT SLEEP(0.15); COMMIT;"
            return execute(statement)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            group_results = list(executor.map(group, (1, 2)))
        if sum(item.returncode == 0 for item in group_results) != 1 or sum(b'ERROR 1062' in item.stderr for item in group_results) != 1:
            raise AssertionError('交易组同买家原键并发唯一约束不成立')
        if sql("SELECT COUNT(*) FROM trade_order.trade_group WHERE user_id=7001 AND idempotency_key='fixture-group-key';") != '1':
            raise AssertionError('交易组出现重复业务事实')
        denied("INSERT INTO trade_order.merchant_shipping_rule(merchant_id,fee_cents) VALUES('merchant_bad',-1);", [3819])
        denied("INSERT INTO trade_order.order_charge_snapshot(order_no,merchant_id,shipping_cents,shipping_version) VALUES('ord_bad','merchant_bad',1000001,1);", [3819])
        result['checks'].append('trade_group原键真实并发唯一/运费规则和快照CHECK')
        dispute = "INSERT INTO trade_aftersale.platform_dispute(dispute_no,aftersale_no,user_id,idempotency_key,request_hash,reason) VALUES('dsp_fixture','as_fixture',7001,'fixture-dispute-key','" + 'b'*64 + "','隔离测试');"
        sql(dispute)
        denied(dispute.replace('dsp_fixture', 'dsp_duplicate'), [1062])
        denied("UPDATE trade_aftersale.platform_dispute SET status='INVALID' WHERE dispute_no='dsp_fixture';", [3819])
        denied("UPDATE trade_aftersale.platform_dispute SET decision='MONEY_SUCCEEDED' WHERE dispute_no='dsp_fixture';", [3819])
        result['checks'].append('platform_dispute原键唯一/状态与裁定CHECK')
        # 从实际Mapper资源取原SQL，验证通知物化去重、字段绑定、领取与旧租约拒绝；不复制另一份实现。
        notification = ET.parse(ROOT / 'tradeflow-order/src/main/resources/mapper/order/OrderNotificationMapper.xml').getroot()
        def mapped(name, bindings=None):
            element = next(item for item in notification if item.get('id') == name)
            statement = ''.join(element.itertext())
            for key, value in (bindings or {}).items():
                statement = statement.replace('#{' + key + '}', str(value))
            if '#{' in statement:
                raise AssertionError('通知Mapper测试存在未绑定参数')
            return 'USE trade_order;' + statement + ';'
        sql("INSERT INTO trade_order.orders(order_no,user_id,merchant_id,payment_no,reservation_no,quote_no,total_amount_cents,pay_deadline,status) VALUES('ord_fixture_notification','7001','merchant_demo','pay_fixture_notification','res_fixture_notification','quo_fixture_notification',100,TIMESTAMPADD(MINUTE,10,UTC_TIMESTAMP(3)),'PAID'); INSERT INTO trade_order.order_status_log(order_no,from_status,to_status,operation,actor_id,request_id,reason) VALUES('ord_fixture_notification','PENDING_PAYMENT','PAID','PAYMENT_ACCEPTED','fixture','fixture','隔离通知验证');")
        sql(mapped('materialize'))
        sql(mapped('materialize'))
        if sql("SELECT COUNT(*) FROM trade_order.order_notification_task WHERE order_no='ord_fixture_notification';") != '1':
            raise AssertionError('通知同原日志重复物化')
        task_id = sql("SELECT id FROM trade_order.order_notification_task WHERE order_no='ord_fixture_notification';")
        if not task_id.isdigit():
            raise AssertionError('通知任务主键异常')
        if sql(mapped('claim', {'id': task_id, 'owner': "'old-owner'"}) + 'SELECT ROW_COUNT();') != '1':
            raise AssertionError('通知首次领取失败')
        sql(f"UPDATE trade_order.order_notification_task SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id={task_id};")
        if sql(mapped('claim', {'id': task_id, 'owner': "'new-owner'"}) + 'SELECT ROW_COUNT();') != '1':
            raise AssertionError('通知租约到期重新领取失败')
        finish = {'id': task_id, 'owner': "'old-owner'", 'version': 1, 'state': "'SUCCEEDED'", 'error': 'NULL'}
        if sql(mapped('finish', finish) + 'SELECT ROW_COUNT();') != '0':
            raise AssertionError('旧通知租约执行者越过版本围栏')
        finish.update(owner="'new-owner'", version=2)
        if sql(mapped('finish', finish) + 'SELECT ROW_COUNT();') != '1':
            raise AssertionError('当前通知租约不能确认完成')
        if sql(f"SELECT CONCAT(state,':',claim_version,':',attempt_count) FROM trade_order.order_notification_task WHERE id={task_id};") != 'SUCCEEDED:2:2':
            raise AssertionError('通知完成状态/版本/尝试次数不一致')
        result['checks'].append('实际通知Mapper物化SQL/去重/参数绑定/租约过期重领/旧版本围栏/当前版本确认')

        # 直接复用交付权限生成器，避免测试一份与实际授权不同的复制策略；只对本次新库执行。
        spec = importlib.util.spec_from_file_location('upgrade_runtime_grants', ROOT / 'scripts/deployment_runtime_grants.py')
        grants_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(grants_module)
        accounts = {}
        for service in SERVICES:
            username = 'verify_' + service
            password = secrets.token_hex(16)
            accounts[service] = (username, password)
            environment = {'TRADEFLOW_' + service.upper() + '_DB_USER': username}
            sql(f"CREATE USER '{username}'@'127.0.0.1' IDENTIFIED BY '{password}';")
            sql(grants_module.runtime_grants(service, environment).replace("@'%'", "@'127.0.0.1'"))
        for service, table, column in [('product', 'inventory_ledger', 'available_delta'), ('order', 'order_status_log', 'reason'), ('payment', 'payment_ledger', 'business_no'), ('settlement', 'settlement_ledger', 'principal_delta'), ('settlement', 'settlement_fact', 'amount_cents'), ('settlement', 'settlement_batch', 'net_cents'), ('order', 'shipping_rule_command', 'fee_cents')]:
            username, password = accounts[service]
            sql(f'SELECT COUNT(*) FROM trade_{service}.{table};', username, password)
            denied(f'UPDATE trade_{service}.{table} SET {column}={column} WHERE 1=0;', [1142, 1143], username, password)
            denied(f'DELETE FROM trade_{service}.{table} WHERE 1=0;', [1142], username, password)
        for service, statement in [('product', 'UPDATE trade_product.product_image SET deleted=1 WHERE 1=0;'), ('user', "INSERT INTO trade_user.user_notification(user_id,business_key,title,content) VALUES(7001,'fixture-notice','标题','内容') ON DUPLICATE KEY UPDATE business_key=VALUES(business_key);"), ('order', "INSERT INTO trade_order.trade_group(group_no,user_id,idempotency_key,request_hash) VALUES('grp_privilege',7002,'grant-key','"+'c'*64+"') ON DUPLICATE KEY UPDATE group_no=group_no;"), ('settlement', "INSERT INTO trade_settlement.settlement_task(task_no,lease_owner) VALUES('SCAN','') ON DUPLICATE KEY UPDATE task_no=task_no;")]:
            sql(statement, *accounts[service])
        sql("UPDATE trade_user.user_address SET region=region WHERE 1=0;", *accounts['user'])
        denied('UPDATE trade_product.product_image SET content=content WHERE 1=0;', [1142, 1143], *accounts['product'])
        denied('UPDATE trade_order.trade_group SET request_hash=request_hash WHERE 1=0;', [1142, 1143], *accounts['order'])
        result['checks'].append('实际runtime grants执行/必要no-op与状态列可写/账本事实快照禁止UPDATE与DELETE')
        environment = os.environ.copy()
        environment.update(TRADEFLOW_RETURN_MYSQL_URL=f'jdbc:mysql://127.0.0.1:{port}/trade_product?serverTimezone=UTC',
                           TRADEFLOW_UPGRADE_MYSQL_URL=f'jdbc:mysql://127.0.0.1:{port}/trade_user?serverTimezone=UTC',
                           TRADEFLOW_SETTLEMENT_TEST_DB_URL=f'jdbc:mysql://127.0.0.1:{port}/trade_settlement_test?serverTimezone=UTC',
                           TRADEFLOW_SETTLEMENT_TEST_DB_USER='root', TRADEFLOW_SETTLEMENT_TEST_DB_PASSWORD='')
        # 邮箱注册与实际Lua使用独占Redis，不连接开发实例；GreenMail由测试JVM持有。
        from isolated_redis import IsolatedRedis
        redis_fixture = IsolatedRedis(work)
        environment['TRADEFLOW_EMAIL_REDIS_PORT'] = str(redis_fixture.port)
        # 全应用边界只启动本次测试JVM，密钥为本次临时生成；不读取已有开发或生产凭据。
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.hazmat.primitives import serialization
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        private, public = work / 'jwt-private.pem', work / 'jwt-public.pem'
        for path, content in [(private, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())),
                              (public, key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))]:
            path.touch(mode=0o600)
            if os.name == 'nt':
                subprocess.run(['icacls', str(path), '/inheritance:r', '/grant:r', getpass.getuser() + ':F'], check=True, capture_output=True, **quiet)
            path.write_bytes(content)
        environment.update(TRADEFLOW_FULL_APPLICATION_FIXTURE='true', TRADEFLOW_UPGRADE_MYSQL_PORT=str(port),
                           TRADEFLOW_JWT_PUBLIC_KEY=public.as_posix(), TRADEFLOW_JWT_PRIVATE_KEY=private.as_posix(), TRADEFLOW_JWT_ISSUER='tradeflow')
        for purpose in ('MONITORING_TOKEN', 'PRODUCT_SERVICE_TOKEN', 'ORDER_SERVICE_TOKEN', 'PAYMENT_SERVICE_TOKEN',
                        'AFTERSALE_SERVICE_TOKEN', 'CHANNEL_SERVICE_TOKEN', 'INTERNAL_SETTLEMENT_TOKEN', 'CALLBACK_SECRET', 'SIMULATOR_ADMIN_TOKEN'):
            environment['TRADEFLOW_' + purpose] = secrets.token_hex(32)
        # 这是唯一Maven调用，服务按reactor串行运行；调用脚本前必须确认主线构建已结束。
        modules = 'tradeflow-user' if '--user-only' in sys.argv else 'tradeflow-user,tradeflow-product,tradeflow-order,tradeflow-payment,tradeflow-aftersale,tradeflow-payment-simulator,tradeflow-settlement'
        command = [str(ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')), '-B', '-ntp', '-fae', '-pl', modules, '-am',
                   '-Dtest=EmailCodeRedisTest,ReturnDispositionMysqlTest,ReservationReliabilityMysqlTest,ProductUpgradeMysqlTest,OrderUpgradeMysqlTest,SettlementMySqlTest,SettlementBusinessMySqlTest,SettlementApplicationTest,UserApplicationTest,ProductApplicationTest,OrderApplicationTest,PaymentApplicationTest,AftersaleApplicationTest,PaymentSimulatorApplicationTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
        run_started = time.time()
        run = subprocess.run(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (work / 'maven.log').write_bytes(run.stdout)
        if run.returncode:
            raise RuntimeError('真实MyBatis/Spring事务测试失败，查看本次隔离目录maven.log')
        expected = {'product': ['ReturnDispositionMysqlTest', 'ReservationReliabilityMysqlTest', 'ProductUpgradeMysqlTest', 'ProductApplicationTest'],
                    'settlement': ['SettlementMySqlTest', 'SettlementBusinessMySqlTest', 'SettlementApplicationTest'], 'user': ['UserApplicationTest','EmailCodeRedisTest'], 'order': ['OrderApplicationTest', 'OrderUpgradeMysqlTest'],
                    'payment': ['PaymentApplicationTest'], 'aftersale': ['AftersaleApplicationTest'], 'payment-simulator': ['PaymentSimulatorApplicationTest']}
        result['test_suites'] = []
        if '--user-only' in sys.argv:
            expected = {'user': expected['user']}
        for module, names in expected.items():
            reports = ROOT / ('tradeflow-' + module) / 'target/surefire-reports'
            for name in names:
                matches = list(reports.glob('TEST-*.' + name + '.xml'))
                if len(matches) != 1 or matches[0].stat().st_mtime < run_started - 1:
                    raise AssertionError('缺少本次测试报告：' + name)
                suite = ET.parse(matches[0]).getroot()
                values = {key: int(suite.get(key, '0')) for key in ('tests', 'skipped', 'errors', 'failures')}
                if values['tests'] <= 0 or any(values[key] for key in ('skipped', 'errors', 'failures')):
                    raise AssertionError('真实依赖目标测试未全部执行通过：' + name)
                result['test_suites'].append({'class': name, **values})
        result['checks'].append('真实ProductUpgrade/ReturnDisposition/ReservationReliability/SettlementMySql及七应用边界测试进程退出0，详见maven.log及Surefire报告')
        result['status'] = 'PASS'
    except BaseException as failure:
        result['failure'] = type(failure).__name__ + ': ' + str(failure)
        raise
    finally:
        if redis_fixture is not None:
            result['owned_redis_stopped'] = redis_fixture.close()
        if process.poll() is None:
            try:
                if Path(sql('SELECT @@datadir;')).resolve() != data.resolve():
                    raise RuntimeError('停止前datadir归属不符')
                subprocess.run([str(binary / ('mysqladmin' + suffix)), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={port}', '--user=root', 'shutdown'], capture_output=True, timeout=15, **quiet)
                process.wait(timeout=20)
            except (RuntimeError, subprocess.TimeoutExpired):
                # 仅回收本函数创建的Popen句柄，不按端口/名称终止任何其他进程。
                process.kill()
                process.wait(timeout=10)
        result['owned_process_stopped'] = process.poll() is not None
        (work / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'result': str(work / 'result.json'), **result}, ensure_ascii=False))


if __name__ == '__main__':
    main()
