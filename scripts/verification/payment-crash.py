"""仅全新独占MySQL执行跨JVM支付崩溃恢复；通道为持久文件替身，不连接既有DB/通道。"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('backup_mysql_fixture', Path(__file__).with_name('backup-mysql-fixture.py'))
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin', type=Path, default=Path('D:/mysql-8.0.46-winx64/bin'))
    args = parser.parse_args()
    root = ROOT / '.local' / ('payment-crash-' + uuid.uuid4().hex)
    root.mkdir(exist_ok=False)
    runtime = {'fixtureOnly': True, 'paymentNo': 'pay_' + uuid.uuid4().hex, 'orderNo': 'ord_' + uuid.uuid4().hex}
    (root / 'runtime.json').write_text(json.dumps(runtime, indent=2))
    server = fixture.FixtureInstance(root / 'mysql', args.mysql_bin.resolve())
    report = {'scope': 'real-jvm-halt-mysql-payment-service-mapper-with-file-channel-double'}
    try:
        server.start()
        server.check_owned()
        if not server.sql('SELECT VERSION();').startswith('8.0.46'):
            raise RuntimeError('要求MySQL8.0.46')
        if server.sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name NOT IN ('mysql','information_schema','performance_schema','sys');") != '0':
            raise RuntimeError('独占fixture实例非空，拒绝加载')
        server.sql('CREATE DATABASE trade_payment CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
        migrations = ROOT / 'tradeflow-payment/src/main/resources/db/migration'
        for migration in sorted(migrations.glob('V*.sql'), key=lambda p: int(p.name.split('__')[0][1:])):
            server.check_owned()
            server.sql('USE trade_payment;\n' + migration.read_text(encoding='utf-8'))
        env = os.environ.copy()
        env['TRADEFLOW_PAYMENT_CRASH_DIR'] = str(root)
        env['TRADEFLOW_PAYMENT_CRASH_URL'] = f'jdbc:mysql://127.0.0.1:{server.port}/trade_payment?serverTimezone=UTC'
        report.update(mysqlPort=server.port, mysqlPid=server.process.pid, migrationsLoaded=True)
        print(json.dumps({'phase': 'maven-test', 'runtime': str(root), 'mysqlPort': server.port}), flush=True)
        with (root / 'maven.log').open('wb') as output:
            result = subprocess.run(['cmd', '/c', str(ROOT / 'mvnw.cmd'), '-B', '-ntp', '-pl', 'tradeflow-payment',
                '-am', '-Dtest=PaymentCrashRecoveryTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'],
                cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, timeout=180)
        report['mavenExitCode'] = result.returncode
        if result.returncode:
            raise RuntimeError('JVM崩溃恢复测试失败；检查专属maven/jvm日志')
        report['test'] = json.loads((root / 'java-result.json').read_text())
        report['result'] = 'passed'
    except Exception as error:
        report['result'] = 'failed'
        report['failureType'] = type(error).__name__
        raise
    finally:
        server.stop()
        report['ownedMysqlStopped'] = server.process is None or server.process.poll() is not None
        (root / 'result.json').write_text(json.dumps(report, indent=2))
        print(json.dumps({'report': str(root / 'result.json'), **report}), flush=True)


if __name__ == '__main__':
    main()
