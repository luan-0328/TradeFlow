"""只创建独立临时 MySQL，加载支付迁移并执行围栏回归；不会连接既有业务实例。"""
import argparse
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import json

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin', default=os.environ.get('TRADEFLOW_MYSQL_BIN', 'D:/mysql-8.0.46-winx64/bin'))
    args = parser.parse_args()
    binaries = Path(args.mysql_bin).resolve()
    # 每轮仅在全新目录初始化；保留数据与日志供复核，不删除任何实例。
    runtime = Path(tempfile.mkdtemp(prefix='tradeflow-fault-mysql-')).resolve()
    data = runtime / 'data'
    data.mkdir()
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    base = [str(binaries / 'mysqld.exe'), '--no-defaults', f'--basedir={binaries.parent.as_posix()}', f'--datadir={data.as_posix()}']
    init = subprocess.run(base + ['--initialize-insecure'], capture_output=True)
    (runtime / 'initialize.log').write_bytes(init.stdout + init.stderr)
    init.check_returncode()
    client = [str(binaries / 'mysql.exe'), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={port}', '--user=root', '--default-character-set=utf8mb4', '--batch']
    with (runtime / 'server.log').open('wb') as log:
        process = subprocess.Popen(base + ['--bind-address=127.0.0.1', f'--port={port}', '--mysqlx=OFF', '--skip-log-bin', '--innodb-buffer-pool-size=64M', '--default-time-zone=+00:00', '--transaction-isolation=READ-COMMITTED'], stdout=log, stderr=log, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    print(f'isolated runtime={runtime} port={port} pid={process.pid}', flush=True)
    try:
        for _ in range(120):
            if process.poll() is not None:
                raise RuntimeError(f'独立 mysqld 启动失败，查看 {runtime / "server.log"}')
            probe = subprocess.run(client + ['-e', 'SELECT VERSION()'], capture_output=True)
            if probe.returncode == 0:
                print(probe.stdout.decode().strip(), flush=True)
                break
            time.sleep(.5)
        else:
            raise RuntimeError('独立 mysqld 就绪超时')
        owned = subprocess.run(client + ['--skip-column-names', '-e', 'SELECT @@datadir'], capture_output=True, check=True)
        assert Path(owned.stdout.decode().strip()).resolve() == data.resolve(), '拒绝访问非本脚本实例'
        subprocess.run(client, input=b'CREATE DATABASE trade_payment CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;', check=True)
        migrations = ROOT / 'tradeflow-payment/src/main/resources/db/migration'
        for migration in sorted(migrations.glob('V*.sql'), key=lambda p: int(p.name.split('__')[0][1:])):
            subprocess.run(client + ['trade_payment'], input=migration.read_bytes(), check=True)
        env = os.environ.copy()
        env['TRADEFLOW_FAULT_MYSQL_URL'] = f'jdbc:mysql://127.0.0.1:{port}/trade_payment?serverTimezone=UTC'
        # 只能由调用者取得 Maven 独占窗口后运行；Java 测试同时验证真实 Mapper 和 Mock 编排。
        command = [str(ROOT / 'mvnw.cmd'), '-B', '-ntp', '-pl', 'tradeflow-order,tradeflow-payment', '-am', '-Dtest=OrderRecoveryFaultTest,PaymentRecoveryFaultTest,PaymentLeaseMysqlTest,RefundReliabilityMysqlTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (runtime / 'maven.log').write_bytes(result.stdout)
        print(result.stdout.decode(errors='replace'), flush=True)
        result.check_returncode()
        (runtime / 'result.json').write_text(json.dumps({'scope':'real-mysql-service-mapper-transactions-with-mock-rpc',
                'result':'PASS','log':str(runtime / 'maven.log')},indent=2),encoding='utf-8')
    finally:
        # 只停止本脚本创建且 PID 仍存活的独立实例；绝不停止既有业务进程。
        if process.poll() is None:
            owned = subprocess.run(client + ['--skip-column-names', '-e', 'SELECT @@datadir'], capture_output=True)
            if owned.returncode or Path(owned.stdout.decode().strip()).resolve() != data.resolve():
                process.kill();process.wait(timeout=10)
                raise RuntimeError('独占MySQL归属不符，仅终止本脚本进程，未发送shutdown')
            stop = subprocess.run([str(binaries / 'mysqladmin.exe'), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={port}', '--user=root', 'shutdown'], capture_output=True)
            (runtime / 'shutdown.log').write_bytes(stop.stdout + stop.stderr)
            stop.check_returncode()
            process.wait(timeout=30)
        print(f'evidence preserved: {runtime}', flush=True)


if __name__ == '__main__':
    main()
