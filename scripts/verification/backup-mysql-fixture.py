"""在全新专属目录演练原生MySQL六个合成fixture库；不访问开发库，不证明Compose恢复。"""
import argparse
import hashlib
import importlib.util
import json
import shutil
import socket
import subprocess
import time
import uuid
import zipfile
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('backup_verify', Path(__file__).with_name('backup-verify.py'))
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class FixtureInstance:
    def __init__(self, directory, binaries):
        self.directory, self.binaries = directory, binaries
        self.data = directory / 'data'
        self.port = free_port()
        self.process = None

    def command(self, executable, args, **kwargs):
        result = subprocess.run([str(self.binaries / executable), *args], capture_output=True, timeout=120, **kwargs)
        if result.returncode:
            raise RuntimeError('fixture MySQL命令失败；不回显原始数据库输出')
        return result.stdout

    def sql(self, statement):
        return self.command('mysql.exe' if __import__('os').name == 'nt' else 'mysql',
                            ['--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={self.port}',
                             '--user=root', '--default-character-set=utf8mb4', '-N', '-B'], input=statement.encode()).decode('utf-8').strip()

    def start(self):
        """先独占新目录再初始化；每次连接后用服务端datadir复核归属，不认领存量实例。"""
        self.directory.mkdir(exist_ok=False)
        self.data.mkdir()
        suffix = '.exe' if __import__('os').name == 'nt' else ''
        args = ['--no-defaults', f'--basedir={self.binaries.parent}', f'--datadir={self.data}']
        self.command('mysqld' + suffix, args + ['--initialize-insecure'])
        self.process = subprocess.Popen([str(self.binaries / ('mysqld' + suffix)), *args,
            '--bind-address=127.0.0.1', f'--port={self.port}', '--mysqlx=0', '--skip-log-bin',
            f'--log-error={self.directory / "mysql.log"}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        for _ in range(120):
            if self.process.poll() is not None:
                raise RuntimeError('新建fixture实例提前退出')
            try:
                self.check_owned()
                return
            except (RuntimeError, subprocess.TimeoutExpired):
                time.sleep(.25)
        raise RuntimeError('fixture实例未就绪')

    def check_owned(self):
        actual = Path(self.sql('SELECT @@datadir;')).resolve()
        if actual != self.data.resolve() or self.process.poll() is not None:
            raise RuntimeError('fixture目标身份不符，禁止导入或shutdown')

    def stop(self):
        """只关闭当前脚本Popen创建且datadir复核通过的实例；不停止任何既有进程。"""
        if self.process is None or self.process.poll() is not None:
            return
        self.check_owned()
        suffix = '.exe' if __import__('os').name == 'nt' else ''
        self.command('mysqladmin' + suffix, ['--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
            f'--port={self.port}', '--user=root', 'shutdown'])
        self.process.wait(timeout=30)


def run(binaries):
    root = Path(__file__).resolve().parents[2] / '.local' / ('backup-fixture-' + uuid.uuid4().hex)
    root.mkdir(exist_ok=False)
    root.chmod(0o700)
    (root / 'fixture-owner.json').write_text(json.dumps({'purpose': 'synthetic-backup-restore-only', 'runId': root.name}))
    source, destination = FixtureInstance(root / 'source', binaries), FixtureInstance(root / 'destination', binaries)
    try:
        source.start()
        version = source.sql('SELECT VERSION();')
        verify.require(version.startswith('8.0.46'), 'fixture要求MySQL8.0.46')
        source.check_owned()
        sql = ''
        for database in verify.DATABASES:
            sql += f"CREATE DATABASE {database}; USE {database}; "
            sql += "CREATE TABLE fixture_probe(id INT PRIMARY KEY COMMENT '测试编号', amount INT NOT NULL COMMENT '测试金额', CONSTRAINT ck_amount CHECK(amount>=0)) COMMENT='合成恢复测试'; INSERT INTO fixture_probe VALUES(1,123); "
        key, content = 'ev_' + 'a' * 32, b'synthetic-evidence-fixture'
        sha = hashlib.sha256(content).hexdigest()
        sql += "USE trade_aftersale; CREATE TABLE evidence_file(object_key VARCHAR(40) PRIMARY KEY COMMENT '合成文件键', size_bytes BIGINT COMMENT '字节数',content_hash CHAR(64) COMMENT '内容散列',state VARCHAR(16) COMMENT '状态') COMMENT='合成证据元数据'; "
        sql += f"INSERT INTO evidence_file VALUES('{key}',{len(content)},'{sha}','BOUND');"
        source.sql(sql)
        bundle = root / 'bundle'
        bundle.mkdir()
        suffix = '.exe' if __import__('os').name == 'nt' else ''
        dump = source.command('mysqldump' + suffix, ['--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
            f'--port={source.port}', '--user=root', '--single-transaction', '--hex-blob', '--set-gtid-purged=OFF',
            '--no-tablespaces', '--databases', *verify.DATABASES])
        (bundle / 'databases.sql').write_bytes(dump)
        deployment_id = str(uuid.uuid4())
        entries = {name: b'SYNTHETIC-FIXTURE-NOT-A-REAL-SECRET' for name in verify.REQUIRED}
        entries.update({'credentials.json': json.dumps({'deployment_id': deployment_id, 'fixtureOnly': True}).encode(),
                        'deployment-id': deployment_id.encode(), 'evidence/' + key: content,
                        'order.env': b'NACOS_SERVER_ADDR=fixture-source:8848\nNACOS_NAMESPACE=fixture-source\nRABBITMQ_HOST=fixture-source-broker\n'})
        with zipfile.ZipFile(bundle / 'protected-config-evidence.zip', 'w') as archive:
            for name, value in entries.items():
                archive.writestr(name, value)
        index_row = source.sql('SELECT object_key,size_bytes,content_hash FROM trade_aftersale.evidence_file;').split('\t')
        (bundle / 'evidence-index.json').write_text(json.dumps({'deploymentId': deployment_id, 'files':
            [{'objectKey': index_row[0], 'sizeBytes': int(index_row[1]), 'sha256': index_row[2]}]}))
        files = {name: hashlib.sha256((bundle / name).read_bytes()).hexdigest() for name in
                 ('databases.sql', 'protected-config-evidence.zip', 'evidence-index.json')}
        files.update({'archive/' + name: hashlib.sha256(value).hexdigest() for name, value in entries.items()})
        (bundle / 'manifest.json').write_text(json.dumps({'formatVersion': 2, 'fixtureOnly': True,
            'deploymentIdentity': {'deploymentId': deployment_id}, 'databases': verify.DATABASES,
            'quiescentConfirmed': True, 'files': files}, indent=2))
        verify.verify_bundle(bundle)
        source.stop()
        destination.start()
        destination.check_owned()
        verify.require(destination.sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name NOT IN ('mysql','information_schema','performance_schema','sys');") == '0', '目标不是空实例')
        destination.sql(dump.decode('utf-8'))
        for database in verify.DATABASES:
            verify.require(destination.sql(f'SELECT id,amount FROM {database}.fixture_probe;') == '1\t123', '恢复内容不符')
        restored = destination.sql('SELECT object_key,size_bytes,content_hash FROM trade_aftersale.evidence_file;').split('\t')
        verify.require(restored == index_row and int(restored[1]) == len(content) and restored[2] == sha, '恢复证据关联不符')
        rejected = False
        try:
            destination.sql('INSERT INTO trade_order.fixture_probe VALUES(2,-1);')
        except RuntimeError:
            rejected = True
        verify.require(rejected, '恢复后CHECK约束未生效')
        report = {'scope': 'native-mysql-synthetic-six-database-fixture-only', 'mysqlVersion': version,
                  'integrityPassed': True, 'emptyOwnedDestinationVerified': True, 'sixFixtureRowsVerified': True,
                  'restoredEvidenceReferenceVerified': True, 'restoredCheckConstraintVerified': True,
                  'sourcePort': source.port, 'destinationPort': destination.port, 'composeRestoreExecuted': False}
        destination.stop()
        report['ownedInstancesStopped'] = True
        (root / 'result.json').write_text(json.dumps(report, indent=2))
        print(json.dumps({'result': 'passed', 'report': str(root / 'result.json'), **report}))
    finally:
        source.stop()
        destination.stop()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin', type=Path, default=Path(shutil.which('mysqld') or '').parent)
    run(parser.parse_args().mysql_bin.resolve())
