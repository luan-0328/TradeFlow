"""使用合成秘密和证据回归拒绝条件；不读取.local，不连接任何服务。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest
import uuid
import zipfile

SPEC = importlib.util.spec_from_file_location('verify', Path(__file__).with_name('backup-verify.py'))
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


class BackupSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='tradeflow-backup-test-')
        self.root = Path(self.temp.name)
        self.bundle = self.root / 'bundle'
        self.bundle.mkdir()
        self.identity = str(uuid.uuid4())
        self.key = 'ev_' + 'a' * 32
        self.entries = {name: b'synthetic-fixture' for name in verify.REQUIRED}
        self.entries.update({'credentials.json': json.dumps({'deployment_id': self.identity}).encode(),
                             'deployment-id': self.identity.encode(),
                             'order.env': b'NACOS_SERVER_ADDR=original-registry:8848\nNACOS_NAMESPACE=original\nRABBITMQ_HOST=rabbitmq\n',
                             'evidence/' + self.key: b'fixture-evidence'})
        self.index = {'deploymentId': self.identity, 'files': [{'objectKey': self.key, 'sizeBytes': 16,
                      'sha256': hashlib.sha256(b'fixture-evidence').hexdigest()}]}
        self.write_bundle()

    def tearDown(self):
        self.temp.cleanup()

    def write_bundle(self, extra=None):
        (self.bundle / 'databases.sql').write_text('-- synthetic SQL fixture\n')
        (self.bundle / 'evidence-index.json').write_text(json.dumps(self.index))
        with zipfile.ZipFile(self.bundle / 'protected-config-evidence.zip', 'w') as archive:
            for name, content in self.entries.items():
                archive.writestr(name, content)
            if extra:
                archive.writestr(*extra)
        files = {name: hashlib.sha256((self.bundle / name).read_bytes()).hexdigest()
                 for name in ('databases.sql', 'protected-config-evidence.zip', 'evidence-index.json')}
        with zipfile.ZipFile(self.bundle / 'protected-config-evidence.zip') as archive:
            for info in archive.infolist():
                files['archive/' + info.filename] = hashlib.sha256(archive.read(info)).hexdigest()
        (self.bundle / 'manifest.json').write_text(json.dumps({'formatVersion': 2, 'deploymentIdentity':
            {'deploymentId': self.identity}, 'databases': verify.DATABASES, 'quiescentConfirmed': True, 'files': files}))

    def plan(self):
        return {'sourceDeploymentId': self.identity, 'destinationDeploymentId': str(uuid.uuid4()),
                'project': 'tradeflow-restore-fixture', 'mysqlDataDirectory': str(self.root / 'new-mysql'),
                'evidenceDirectory': str(self.root / 'new-evidence'), 'mysqlEndpoint': '127.0.0.1:23471',
                'registryEndpoint': '127.0.0.1:23472', 'registryNamespace': 'isolated-restore',
                'brokerEndpoint': '127.0.0.1:23473', 'networkEgressDenied': True,
                'applicationsDisabled': True, 'bootstrapDisabled': True, 'freshVolumesOnly': True}

    def test_valid_bundle_and_plan(self):
        result = verify.verify_bundle(self.bundle)
        self.assertEqual(result['evidenceCount'], 1)
        verify.verify_plan(self.plan(), result)
        self.assertFalse((self.root / 'new-mysql').exists())

    def test_corrupt_dump_archive_and_index(self):
        for name in ('databases.sql', 'protected-config-evidence.zip', 'evidence-index.json'):
            with self.subTest(name=name):
                self.write_bundle()
                with (self.bundle / name).open('ab') as output:
                    output.write(b'changed')
                with self.assertRaises(ValueError):
                    verify.verify_bundle(self.bundle)

    def test_unsafe_paths(self):
        for name in ('../outside', '/outside', 'C:/outside', 'evidence\\outside', 'x/../outside',
                     'x//outside', 'x/./outside', 'file:secret', 'CON.txt', 'evidence/file.', 'evidence/file '):
            with self.subTest(name=name):
                with self.assertRaises(ValueError):
                    verify.safe_name(name)
                # Windows zipfile写入时会将反斜线归一化；直接函数断言覆盖原始恶意成员。
                if '\\' in name:
                    continue
                self.write_bundle((name, b'bad'))
                with self.assertRaises(ValueError):
                    verify.verify_bundle(self.bundle)

    def test_archive_link_rejected(self):
        link = zipfile.ZipInfo('linked-file')
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.write_bundle((link, b'/outside'))
        with self.assertRaises(ValueError):
            verify.verify_bundle(self.bundle)

    def test_duplicate_case_path_rejected(self):
        self.write_bundle(('Credentials.json', b'other'))
        with self.assertRaises(ValueError):
            verify.verify_bundle(self.bundle)

    def test_unlisted_member_rejected(self):
        with zipfile.ZipFile(self.bundle / 'protected-config-evidence.zip', 'a') as archive:
            archive.writestr('not-in-manifest', b'bad')
        manifest_path = self.bundle / 'manifest.json'
        manifest = json.loads(manifest_path.read_text())
        manifest['files']['protected-config-evidence.zip'] = hashlib.sha256((self.bundle / 'protected-config-evidence.zip').read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            verify.verify_bundle(self.bundle)

    def test_secret_identity_mismatch(self):
        for name in ('deployment-id', 'credentials.json'):
            with self.subTest(name=name):
                original = self.entries[name]
                self.entries[name] = (str(uuid.uuid4()).encode() if name == 'deployment-id' else
                                      json.dumps({'deployment_id': str(uuid.uuid4())}).encode())
                self.write_bundle()
                with self.assertRaises(ValueError):
                    verify.verify_bundle(self.bundle)
                self.entries[name] = original

    def test_missing_changed_or_wrong_size_evidence(self):
        name = 'evidence/' + self.key
        original = self.entries.pop(name)
        self.write_bundle()
        with self.assertRaises(ValueError):
            verify.verify_bundle(self.bundle)
        self.entries[name] = b'changed'
        self.write_bundle()
        with self.assertRaises(ValueError):
            verify.verify_bundle(self.bundle)
        self.entries[name] = original
        self.index['files'][0]['sizeBytes'] = 999
        self.write_bundle()
        with self.assertRaises(ValueError):
            verify.verify_bundle(self.bundle)

    def test_incomplete_and_legacy_rejected(self):
        incomplete = self.root / 'backup.incomplete'
        self.bundle.rename(incomplete)
        with self.assertRaises(ValueError):
            verify.verify_bundle(incomplete)
        verify.verify_bundle(incomplete, allow_incomplete=True)
        incomplete.rename(self.bundle)
        path = self.bundle / 'manifest.json'
        manifest = json.loads(path.read_text())
        del manifest['formatVersion']
        path.write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            verify.verify_bundle(self.bundle)

    def test_wrong_reused_and_nonlocal_targets_rejected(self):
        bundle = verify.verify_bundle(self.bundle)
        changes = {'sourceDeploymentId': str(uuid.uuid4()), 'destinationDeploymentId': self.identity,
                   'project': 'tradeflow-b8', 'mysqlDataDirectory': str(self.bundle),
                   'evidenceDirectory': str(self.root / 'new-mysql'), 'mysqlEndpoint': '127.0.0.1:13316',
                   'registryEndpoint': 'original-registry:8848', 'registryNamespace': 'original',
                   'brokerEndpoint': '192.0.2.1:5672', 'applicationsDisabled': False,
                   'bootstrapDisabled': False, 'freshVolumesOnly': False, 'networkEgressDenied': False}
        for key, value in changes.items():
            with self.subTest(key=key):
                plan = self.plan()
                plan[key] = value
                with self.assertRaises(ValueError):
                    verify.verify_plan(plan, bundle)

    def test_original_endpoint_aliases_and_registry_list_rejected(self):
        bundle = verify.verify_bundle(self.bundle)
        bundle['sourceRegistry'] = 'another-registry:8848,http://localhost:23472'
        with self.assertRaises(ValueError):
            verify.verify_plan(self.plan(), bundle)
        bundle['sourceRegistry'] = 'original-registry:8848'
        bundle['sourceBroker'] = 'localhost'
        bundle['sourceBrokerPort'] = '23473'
        with self.assertRaises(ValueError):
            verify.verify_plan(self.plan(), bundle)


if __name__ == '__main__':
    unittest.main(verbosity=2)
