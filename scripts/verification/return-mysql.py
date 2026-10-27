"""全新回环MySQL加载商品/订单/售后迁移，验证真实业务用例；运行前需取得Maven独占窗口。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    binary = Path(os.environ.get('TRADEFLOW_MYSQL_BIN', str(Path(shutil.which('mysqld')).parent))).resolve()
    suffix = '.exe' if os.name == 'nt' else ''
    local = ROOT / '.local' / 'verification'
    local.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='return-mysql-', dir=local)).resolve()
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    config = work / 'my.ini'
    config.write_text(f'''[mysqld]
basedir={binary.parent.as_posix()}
datadir={(work / 'data').as_posix()}
port={port}
bind-address=127.0.0.1
mysqlx=0
skip-log-bin
default-time-zone=+00:00
transaction-isolation=READ-COMMITTED
innodb-buffer-pool-size=48M
log-error={(work / 'mysql-error.log').as_posix()}
''', encoding='utf-8')
    quiet = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    subprocess.run([str(binary / ('mysqld' + suffix)), f'--defaults-file={config}', '--initialize-insecure'], check=True, capture_output=True, **quiet)
    process = subprocess.Popen([str(binary / ('mysqld' + suffix)), f'--defaults-file={config}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **quiet)
    client = [str(binary / ('mysql' + suffix)), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={port}', '--user=root', '-N', '-B', '--default-character-set=utf8mb4']

    def sql(statement):
        response = subprocess.run(client, input=statement.encode('utf-8'), capture_output=True, timeout=30, **quiet)
        if response.returncode:
            raise RuntimeError('独占退货MySQL命令失败: ' + response.stderr.decode('utf-8', errors='replace'))
        return response.stdout.decode('utf-8').strip()

    result = {'kind': 'real-mysql-mybatis-spring-transaction', 'checks': [], 'migrations': []}
    try:
        for _ in range(100):
            if process.poll() is not None:
                raise RuntimeError('独占退货MySQL提前退出')
            try:
                sql('SELECT 1;')
                break
            except RuntimeError:
                time.sleep(.2)
        else:
            raise RuntimeError('独占退货MySQL就绪超时')
        assert Path(sql('SELECT @@datadir;')).resolve() == (work / 'data').resolve(), '禁止操作非本脚本实例'
        for service, maximum in [('product', 5), ('order', 7), ('aftersale', 4)]:
            schema = 'trade_' + service
            sql(f'CREATE DATABASE {schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
            migrations = ROOT / f'tradeflow-{service}/src/main/resources/db/migration'
            files = sorted(migrations.glob('V*.sql'), key=lambda file: int(file.name.split('__')[0][1:]))
            files = [file for file in files if int(file.name.split('__')[0][1:]) <= maximum]
            assert len(files) == maximum
            for migration in files:
                original = migration.read_bytes()
                sql(f'USE {schema};' + original.decode('utf-8-sig'))
                result['migrations'].append({'path': str(migration.relative_to(ROOT)), 'sha256': hashlib.sha256(original).hexdigest()})
                assert migration.read_bytes() == original, '验证脚本不得改写任何迁移字节'
            assert sql(f"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='{schema}' AND column_comment NOT REGEXP '[一-龥]';") == '0'
            assert sql(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{schema}' AND table_type='BASE TABLE' AND table_comment NOT REGEXP '[一-龥]';") == '0'
            result['checks'].append(f'{service} V1–V{maximum}全量迁移及表/字段中文COMMENT完整')
        environment = os.environ.copy()
        environment['TRADEFLOW_RETURN_MYSQL_URL'] = f'jdbc:mysql://127.0.0.1:{port}/trade_product?serverTimezone=UTC'
        # 此脚本不访问任何已有业务库；只向本次Maven传递随机端口，凭据为空的本次隔离root不写入全局配置。
        command = [str(ROOT / 'mvnw.cmd'), '-B', '-ntp', '-pl', 'tradeflow-product', '-am',
                   '-Dtest=ReturnDispositionMysqlTest,ReservationReliabilityMysqlTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
        run = subprocess.run(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (work / 'maven.log').write_bytes(run.stdout)
        if run.returncode:
            print(run.stdout.decode(errors='replace'))
        run.check_returncode()
        result['checks'].append('ReturnDispositionMysqlTest真实Mapper与Spring事务5项通过（含商品XML创建/幂等/改价/下架）')
        result['checks'].append('ReservationReliabilityMysqlTest真实预占并发/重放/墓碑/多SKU回滚/确认释放竞争/流水故障7项通过')
        result['status'] = 'PASS'
    finally:
        if process.poll() is None:
            try:
                assert Path(sql('SELECT @@datadir;')).resolve() == (work / 'data').resolve()
                subprocess.run([str(binary / ('mysqladmin' + suffix)), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={port}', '--user=root', 'shutdown'], capture_output=True, timeout=15, **quiet)
                process.wait(timeout=20)
            except (AssertionError, RuntimeError, subprocess.TimeoutExpired):
                process.kill()
                process.wait(timeout=10)
        result['owned_process_stopped'] = process.poll() is not None
        (work / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'result': str(work / 'result.json'), **result}, ensure_ascii=False))


if __name__ == '__main__':
    main()
