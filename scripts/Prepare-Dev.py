"""准备本机独立MySQL与所属业务数据库，执行Flyway；不连接远程或复用旧验证实例。"""
import argparse
import base64
import ctypes
from ctypes import wintypes
import getpass
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local'
DB = LOCAL / 'mysql'
DATA = DB / 'data'
PORT = 13316
ADMIN = LOCAL / 'database-admin.json'
ENV = LOCAL / 'dev-env.json'


def protected_write(path, content):
    """先设置仅当前用户可访问再写凭据，避免创建时短暂继承宽泛权限。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.touch(exist_ok=True)
    subprocess.run(['icacls', str(path), '/inheritance:r', '/grant:r', getpass.getuser() + ':F'],
                   check=True, capture_output=True)
    path.write_text(content, encoding='utf-8')


def settings():
    """首次生成本地开发凭据，重复执行保留现有密码与签名密钥。"""
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization
    LOCAL.mkdir(exist_ok=True)
    if not ADMIN.exists():
        admin = {'root': secrets.token_hex(24)}
        for service in ('user', 'product', 'order', 'payment', 'payment_simulator', 'aftersale', 'settlement'):
            admin[service] = secrets.token_hex(24)
        protected_write(ADMIN, json.dumps(admin, indent=2))
    if not ENV.exists():
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        private = LOCAL / 'jwt-private.pem'
        public = LOCAL / 'jwt-public.pem'
        protected_write(private, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                 serialization.NoEncryption()).decode())
        protected_write(public, key.public_key().public_bytes(serialization.Encoding.PEM,
                                                            serialization.PublicFormat.SubjectPublicKeyInfo).decode())
        env = {'MYSQL_HOST': '127.0.0.1', 'MYSQL_PORT': str(PORT),
               'TRADEFLOW_JWT_PRIVATE_KEY': private.as_posix(), 'TRADEFLOW_JWT_PUBLIC_KEY': public.as_posix(),
               'TRADEFLOW_JWT_ISSUER': 'tradeflow',
               'TRADEFLOW_PRODUCT_SERVICE_TOKEN': secrets.token_hex(32),
               'TRADEFLOW_ORDER_SERVICE_TOKEN': secrets.token_hex(32),
               'TRADEFLOW_BOOTSTRAP_ENABLED': 'true', 'TRADEFLOW_BOOTSTRAP_USERNAME': 'platform_admin',
               'TRADEFLOW_BOOTSTRAP_PASSWORD': 'Tf!9' + secrets.token_hex(16)}
        for service in ('user', 'product', 'order', 'payment', 'payment_simulator', 'aftersale', 'settlement'):
            env[f'TRADEFLOW_{service.upper()}_DB_USER'] = f'tf_{service}_app'
            env[f'TRADEFLOW_{service.upper()}_DB_PASSWORD'] = secrets.token_hex(24)
        protected_write(ENV, json.dumps(env, indent=2))
    # 增量补充本批账号和独立用途凭据，已有环境绝不轮换密钥。
    admin = json.loads(ADMIN.read_text())
    env = json.loads(ENV.read_text())
    for service in ('user', 'product', 'order', 'payment', 'payment_simulator', 'aftersale', 'settlement'):
        admin.setdefault(service, secrets.token_hex(24))
        env.setdefault(f'TRADEFLOW_{service.upper()}_DB_USER', f'tf_{service}_app')
        env.setdefault(f'TRADEFLOW_{service.upper()}_DB_PASSWORD', secrets.token_hex(24))
    for purpose in ('PAYMENT_SERVICE_TOKEN', 'CHANNEL_SERVICE_TOKEN', 'CALLBACK_SECRET', 'SIMULATOR_ADMIN_TOKEN', 'AFTERSALE_SERVICE_TOKEN', 'INTERNAL_SETTLEMENT_TOKEN'):
        env.setdefault('TRADEFLOW_' + purpose, secrets.token_hex(32))
    protected_write(ADMIN, json.dumps(admin, indent=2))
    protected_write(ENV, json.dumps(env, indent=2))
    return admin, env


def mysql(admin, sql):
    """SQL通过标准输入传递，密码仅存在子进程环境；不回显SQL或凭据。"""
    env = os.environ.copy()
    env['MYSQL_PWD'] = admin['root']
    result = subprocess.run([shutil.which('mysql'), '--no-defaults', '--protocol=TCP', '-h127.0.0.1',
                             f'-P{PORT}', '-uroot', '--default-character-set=utf8mb4', '-N', '-B'],
                            input=sql, text=True, capture_output=True, env=env)
    if result.returncode:
        # 原始SQL可能包含凭据，只返回固定错误并将非敏感错误类别留给维护者。
        raise RuntimeError('本机独立MySQL命令失败，未输出SQL和密钥；请查看专用数据库错误日志。')
    return result.stdout.strip()


def running():
    try:
        with socket.create_connection(('127.0.0.1', PORT), timeout=1):
            return True
    except OSError:
        return False


def start_mysql(admin):
    """仅在新专用目录初始化；已有监听必须验证datadir，绝不停止或接管其他实例。"""
    DB.mkdir(exist_ok=True)
    if running():
        actual = mysql(admin, 'SELECT @@datadir;')
        if Path(actual).resolve() != DATA.resolve():
            raise RuntimeError('13316不是本项目数据库，拒绝继续。')
        return
    binary = shutil.which('mysqld')
    if not binary or not shutil.which('mysql'):
        raise RuntimeError('请将MySQL8.0.46的bin目录加入PATH。')
    config = DB / 'my.ini'
    config.write_text(f'''[mysqld]
basedir={Path(binary).parents[1].as_posix()}
datadir={DATA.as_posix()}
port={PORT}
bind-address=127.0.0.1
mysqlx=0
skip-name-resolve
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
default-time-zone=+00:00
transaction-isolation=READ-COMMITTED
innodb-buffer-pool-size=128M
innodb-redo-log-capacity=64M
max-connections=40
performance-schema=OFF
log-error={(DB / 'mysql.err').as_posix()}
''', encoding='utf-8')
    first = not (DATA / 'mysql').exists()
    if first:
        if DATA.exists() and any(DATA.iterdir()):
            raise RuntimeError('专用数据目录非空但未完成初始化；请人工核对，不自动删除。')
        result = subprocess.run([binary, '--defaults-file=' + str(config), '--initialize-insecure'], capture_output=True)
        if result.returncode:
            raise RuntimeError('MySQL初始化失败，检查.local/mysql/mysql.err。')
    init = DB / 'initialize.sql'
    arguments = [binary, '--defaults-file=' + str(config), '--no-monitor']
    if first or not admin.get('root_tcp_ready'):
        protected_write(init, "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + admin['root'] + "';\n"
                        + "CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '" + admin['root'] + "';\n"
                        + "GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;\n")
        arguments.append('--init-file=' + str(init))
    with (DB / 'process.log').open('ab') as log:
        process = subprocess.Popen(arguments, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                   creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)
    kernel32 = ctypes.windll.kernel32
    kernel32.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    created, exited, kernel, user = (wintypes.FILETIME() for _ in range(4))
    if not kernel32.GetProcessTimes(wintypes.HANDLE(int(process._handle)), ctypes.byref(created), ctypes.byref(exited), ctypes.byref(kernel), ctypes.byref(user)):
        raise RuntimeError('无法记录MySQL进程创建时间。')
    ticks = (created.dwHighDateTime << 32) + created.dwLowDateTime + 504911232000000000
    (LOCAL / 'mysql-process.json').write_text(json.dumps({'pid': process.pid, 'startedTicks': ticks,
             'service': 'tradeflow-mysql', 'port': PORT, 'dataDir': str(DATA)}, indent=2))
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('MySQL启动失败，检查专用错误日志。')
        if running():
            try:
                mysql(admin, 'SELECT 1;')
                admin['root_tcp_ready'] = True
                protected_write(ADMIN, json.dumps(admin, indent=2))
                if init.exists():
                    init.unlink()
                return
            except RuntimeError:
                pass
        time.sleep(0.5)
    raise RuntimeError('专用MySQL未在60秒内就绪，未停止其他实例。')


def migrate(admin, env):
    """先独立Flyway迁移，再授予运行账号逐表权限；业务JVM不持有迁移凭据。"""
    for service in ('user', 'product', 'order', 'payment', 'payment_simulator', 'aftersale', 'settlement'):
        db = 'trade_' + service
        # 归属记录只保存在受保护文件，不往空业务库写标记表；Flyway禁止对非空库自动baseline。
        known = mysql(admin, f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{db}';")
        if known != '0':
            if db not in admin.get('owned_databases', []):
                raise RuntimeError(f'{db}已存在且非本项目专用库，拒绝迁移。')
        else:
            mysql(admin, f"CREATE DATABASE {db} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
            admin.setdefault('owned_databases', []).append(db)
            protected_write(ADMIN, json.dumps(admin, indent=2))
        migration_user = f'tf_{service}_migrate'
        mysql(admin, f"CREATE USER IF NOT EXISTS '{migration_user}'@'127.0.0.1' IDENTIFIED BY '{admin[service]}'; GRANT ALL PRIVILEGES ON {db}.* TO '{migration_user}'@'127.0.0.1';")
        # 迁移进程只继承Java/操作系统必要环境与本服务迁移凭据，不继承root、运行库密码或服务Token。
        migration_environment = {'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'COMSPEC', 'PATHEXT', 'PATH',
                                 'TEMP', 'TMP', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'PROGRAMDATA',
                                 'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE', 'JAVA_HOME'}
        child = {name: value for name, value in os.environ.items() if name.upper() in migration_environment}
        child.update(MYSQL_HOST='127.0.0.1', MYSQL_PORT=str(PORT))
        child[f'TRADEFLOW_{service.upper()}_MIGRATION_USER'] = migration_user
        child[f'TRADEFLOW_{service.upper()}_MIGRATION_PASSWORD'] = admin[service]
        module = service.replace('_', '-')
        namespace = service.replace('_', '')
        # 结算模块自有入口不在infrastructure包；其他既有服务保持原迁移契约。
        migration_main = ('com.tradeflow.settlement.DatabaseMigration' if service == 'settlement'
                          else f'com.tradeflow.{namespace}.infrastructure.DatabaseMigration')
        jar = ROOT / f'tradeflow-{module}/target/tradeflow-{module}-0.1.0-SNAPSHOT.jar'
        if not jar.exists():
            raise RuntimeError('先完成Maven package，再执行Prepare-Dev.py。')
        with (LOCAL / f'migration-{service}.log').open('wb') as log:
            result = subprocess.run([shutil.which('java'), '-Xmx128m',
                    f'-Dloader.main={migration_main}',
                    '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher'],
                    env=child, stdout=log, stderr=log)
        if result.returncode:
            raise RuntimeError(f'{service}迁移失败，检查.local/migration-{service}.log。')
        app = env[f'TRADEFLOW_{service.upper()}_DB_USER']
        password = env[f'TRADEFLOW_{service.upper()}_DB_PASSWORD']
        mysql(admin, f"CREATE USER IF NOT EXISTS '{app}'@'127.0.0.1' IDENTIFIED BY '{password}';")
        mutable = {
            'user': ['user_account', 'user_role', 'merchant_member', 'login_rate_limit', 'login_entry_window', 'authorization_guard'],
            'product': ['product', 'sku', 'inventory', 'inventory_receipt', 'idempotency_request', 'product_quote', 'reservation_header', 'inventory_condition'],
            'order': ['orders', 'idempotency_request', 'process_task', 'order_creation_intent', 'order_item', 'order_item_unit', 'refund_authorization', 'refund_flow_task', 'return_stock_task'],
            'payment': ['payment_order', 'channel_notification', 'idempotency_request', 'process_task'],
            'payment_simulator': [],
            'aftersale': ['aftersale', 'aftersale_task', 'aftersale_refund_attempt'],
            'settlement': []
        }[service]
        immutable = {
            'user': ['audit_log', 'merchant', 'merchant_application', 'user_address', 'user_notification'],
            'product': ['quote_item', 'receipt_item', 'inventory_ledger', 'audit_log', 'reservation_item', 'unshipped_restock', 'return_restock', 'return_restock_item', 'inventory_condition_operation', 'product_image'],
            'order': ['order_address', 'order_status_log', 'audit_log', 'order_payment_fact', 'order_shipment', 'order_refund_fact', 'refund_allocation', 'order_return_receipt', 'order_return_disposition', 'merchant_shipping_rule', 'order_charge_snapshot', 'trade_group', 'trade_group_child', 'shipping_rule_command', 'order_notification_task'],
            'payment': ['payment_ledger', 'audit_log', 'refund_account', 'refund_source', 'refund_order', 'outbox_event', 'reconciliation_run', 'reconciliation_issue', 'reconciliation_action', 'reconciliation_stage_line', 'reconciliation_observation', 'reconciliation_command'],
            'payment_simulator': ['channel_transaction', 'fault_scenario', 'process_task', 'callback_task', 'audit_log', 'channel_refund', 'refund_fault_scenario', 'refund_process_task', 'refund_callback_task', 'bill_snapshot', 'bill_snapshot_line', 'bill_projection_scenario'],
            'aftersale': ['aftersale_idempotency', 'aftersale_audit', 'aftersale_allocation', 'aftersale_return_shipment', 'aftersale_return_inspection', 'aftersale_return_disposition', 'evidence_file', 'aftersale_evidence', 'platform_dispute'],
            'settlement': ['settlement_task', 'settlement_account', 'settlement_item', 'settlement_fact', 'settlement_ledger', 'settlement_batch', 'settlement_reconciliation', 'settlement_audit']
        }[service]
        # U1-U4新增表权限按可变列收紧；ON DUPLICATE自赋值也只开放对应唯一键列，账本不开放UPDATE/DELETE。
        upgrade_columns = {
            'user': {
                'merchant': 'name,description,status,version',
                'merchant_application': 'status,reason,version',
                'user_address': 'recipient,phone,region,address,default_address,deleted,version',
                'user_notification': 'business_key,read_at'
            },
            'product': {'product_image': 'deleted'},
            'order': {'merchant_shipping_rule': 'merchant_id,fee_cents,version', 'trade_group': 'group_no',
                      'order_notification_task': 'source_log_id,lease_owner,claim_version,attempt_count,lease_until,state,last_error,delivered_at,next_retry_at'},
            'aftersale': {'platform_dispute': 'dispute_no,status,decision,decision_reason,decided_by,version,decided_at'},
            'settlement': {
                'settlement_task': 'task_no,lease_owner,claim_version,lease_until,scan_cursor,candidate_cursor,last_error',
                'settlement_account': 'merchant_id,debt_cents',
                'settlement_item': 'refund_cents,fee_cents,status,batch_no'
            }
        }.get(service, {})
        # B5所有参与者都保存本库消息证据，原始载荷和操作审计不授予任意修改权。
        if service in ('order', 'payment', 'aftersale'):
            immutable += ['inbox_message', 'deferred_event', 'message_failure', 'message_operation']
            if service in ('order', 'aftersale'):
                immutable += ['outbox_event']
            if service == 'order':
                immutable += ['event_projection']
        # 已存在的运行账号不得保留新增表整表UPDATE；不可变流水不能通过旧宽权限绕开列授权。
        for table in immutable:
            for privilege in ('UPDATE', 'DELETE'):
                broad = mysql(admin, f"SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA='{db}' AND TABLE_NAME='{table}' AND PRIVILEGE_TYPE='{privilege}' AND GRANTEE=CONCAT(CHAR(39),'{app}',CHAR(39),'@',CHAR(39),'127.0.0.1',CHAR(39));")
                if broad != '0':
                    mysql(admin, f"REVOKE {privilege} ON {db}.{table} FROM '{app}'@'127.0.0.1';")
        grants = [f"GRANT SELECT,INSERT,UPDATE ON {db}.{table} TO '{app}'@'127.0.0.1';" for table in mutable]
        grants += [f"GRANT SELECT,INSERT ON {db}.{table} TO '{app}'@'127.0.0.1';" for table in immutable]
        grants += [f"GRANT UPDATE({columns}) ON {db}.{table} TO '{app}'@'127.0.0.1';" for table, columns in upgrade_columns.items()]
        if service == 'order':
            receipt_update = mysql(admin, f"SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA='{db}' AND TABLE_NAME='order_return_receipt' AND PRIVILEGE_TYPE='UPDATE' AND GRANTEE=CONCAT(CHAR(39),'{app}',CHAR(39),'@',CHAR(39),'127.0.0.1',CHAR(39));")
            if receipt_update != '0':
                mysql(admin, f"REVOKE UPDATE ON {db}.order_return_receipt FROM '{app}'@'127.0.0.1';")
            grants += [f"GRANT UPDATE(status) ON {db}.order_return_receipt TO '{app}'@'127.0.0.1';"]
        if service == 'aftersale':
            grants += [f"GRANT UPDATE(state) ON {db}.evidence_file TO '{app}'@'127.0.0.1';"]
        if service == 'user':
            grants += [f"GRANT SELECT ON {db}.role TO '{app}'@'127.0.0.1';", f"GRANT DELETE ON {db}.login_rate_limit TO '{app}'@'127.0.0.1';", f"GRANT DELETE ON {db}.login_entry_window TO '{app}'@'127.0.0.1';"]
        if service == 'payment':
            # 升级B4既有账户时收紧原整表UPDATE，避免它覆盖B5的原始消息列保护。
            broad = mysql(admin, f"SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA='{db}' AND TABLE_NAME='outbox_event' AND PRIVILEGE_TYPE='UPDATE' AND GRANTEE=CONCAT(CHAR(39),'{app}',CHAR(39),'@',CHAR(39),'127.0.0.1',CHAR(39));")
            if broad != '0':
                mysql(admin, f"REVOKE UPDATE ON {db}.outbox_event FROM '{app}'@'127.0.0.1';")
        if service in ('order', 'payment', 'aftersale'):
            message_columns = {
                'inbox_message': 'event_id,status,updated_at',
                'deferred_event': 'deferred_id,status,attempt_count,cycle_started_at,next_retry_at,lease_owner,lease_until,claim_version,last_error,updated_at,version',
                'message_failure': 'failure_id,disposition,status,attempt_count,cycle_started_at,next_retry_at,lease_owner,lease_until,claim_version,published_at,last_error,updated_at,version',
                'outbox_event': 'status,attempt_count,next_retry_at,lease_owner,lease_until,claim_version,published_at,last_error,wire_payload,updated_at,version'
            }
            grants += [f"GRANT UPDATE({cols}) ON {db}.{table} TO '{app}'@'127.0.0.1';" for table, cols in message_columns.items()]
        if service == 'aftersale':
            grants += [f"GRANT UPDATE(idempotency_key) ON {db}.aftersale_idempotency TO '{app}'@'127.0.0.1';"]
        if service == 'payment':
            columns = {
                'refund_account': 'reserved_refund_cents,succeeded_refund_cents,version',
                'refund_source': 'source_no,reserved_cents,succeeded_cents,active_refund_no,version',
                'refund_order': 'status,channel_refund_no,channel_version,channel_updated_at,succeeded_at,review_required,last_error,version'
            }
            grants += [f"GRANT UPDATE({cols}) ON {db}.{table} TO '{app}'@'127.0.0.1';" for table, cols in columns.items()]
        if service == 'payment_simulator':
            columns = {
                'channel_transaction': 'payment_no,status,channel_version,updated_at,succeeded_at,reserved_refund_cents,succeeded_refund_cents',
                'fault_scenario': 'payment_no',
                'channel_refund': 'status,channel_version,succeeded_at,updated_at',
                'refund_fault_scenario': 'refund_no',
                'refund_process_task': 'state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error',
                'refund_callback_task': 'state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error',
                'process_task': 'task_no,state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error',
                'callback_task': 'task_no,state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error'
            }
            grants += [f"GRANT UPDATE({cols}) ON {db}.{table} TO '{app}'@'127.0.0.1';" for table, cols in columns.items()]
        # B7证据、指纹、请求原因不授予修改权；只开放本库恢复状态和必要首次绑定列。
        if service == 'payment':
            task_columns = 'state,payload,lease_owner,claim_version,lease_until,consecutive_failures,next_at,last_error,updated_at'
            for table, extra in [('reconciliation_run',''), ('reconciliation_issue',',action_no'), ('reconciliation_action',',issue_no,origin_run_no')]:
                grants += [f"GRANT UPDATE({task_columns}{extra}) ON {db}.{table} TO '{app}'@'127.0.0.1';"]
            grants += [f"GRANT UPDATE(result_no) ON {db}.reconciliation_command TO '{app}'@'127.0.0.1';"]
        if service == 'payment_simulator':
            grants += [f"GRANT UPDATE(request_no,manifest_json) ON {db}.bill_snapshot TO '{app}'@'127.0.0.1';",
                       f"GRANT UPDATE(request_no) ON {db}.bill_projection_scenario TO '{app}'@'127.0.0.1';"]
        mysql(admin, '\n'.join(grants))
        print(f'{db}: Flyway migrated; runtime grants ready (no DDL, no ledger update/delete).')


def stop_mysql(admin):
    """验证专用数据目录后优雅关闭；仍有业务连接时拒绝停库。"""
    if not running():
        print('Local MySQL is already stopped.')
        return
    if Path(mysql(admin, 'SELECT @@datadir;')).resolve() != DATA.resolve():
        raise RuntimeError('端口不是本项目数据库，拒绝关闭。')
    if mysql(admin, "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER IN ('tf_user_app','tf_product_app','tf_order_app','tf_payment_app','tf_payment_simulator_app','tf_aftersale_app','tf_settlement_app');") != '0':
        raise RuntimeError('先运行Stop-Local.ps1停止业务服务，再关闭开发数据库。')
    env = os.environ.copy()
    env['MYSQL_PWD'] = admin['root']
    result = subprocess.run([shutil.which('mysqladmin'), '--no-defaults', '--protocol=TCP', '-h127.0.0.1',
                             f'-P{PORT}', '-uroot', 'shutdown'], env=env, capture_output=True)
    if result.returncode:
        raise RuntimeError('专用MySQL优雅关闭失败，未执行按名称强制终止。')
    print('Local MySQL stopped; data retained.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', default='prepare', choices=['prepare', 'stop'])
    args = parser.parse_args()
    if args.action == 'stop':
        if not ADMIN.exists():
            raise SystemExit('本项目没有开发数据库记录。')
        stop_mysql(json.loads(ADMIN.read_text()))
        raise SystemExit(0)
    admin, env = settings()
    start_mysql(admin)
    migrate(admin, env)
    print('Local MySQL ready on 127.0.0.1:13316; credentials not printed.')
