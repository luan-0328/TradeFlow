"""只将明确允许的运行产物打包，不读取本机运行秘密，不连接服务或启动部署。"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APPS = ('gateway', 'user', 'product', 'order', 'payment', 'aftersale', 'payment-simulator', 'settlement')
TEXT_FILES = (
    '.dockerignore', 'deploy/Dockerfile', 'deploy/Dockerfile.dockerignore', 'deploy/Dockerfile.web.release',
    'deploy/compose.server-demo.yaml',
    'deploy/Dockerfile.web.release.dockerignore', 'deploy/web/nginx.conf',
    'scripts/Prepare-Deployment.py', 'scripts/deployment_runtime_grants.py',
    'scripts/deployment_rabbit_topology.py', 'scripts/Backup-Deployment.py',
    'scripts/verification/backup-verify.py',
    'docs/project-hardening.md', 'docs/entry-protection.md',
    'docs/delivery-status.md', 'docs/runbooks/backup-restore.md', 'docs/runbooks/server-demo.md', 'docs/runbooks/nacos-discovery.md',
    'docs/b12-development.md', 'docs/b13-development.md', 'docs/b14-development.md',
)


def safe_file(relative):
    """白名单文件和所有祖先禁止链接/联接，防止把工作区外或.local内容间接装入包。"""
    path = ROOT / relative
    for entry in (path, *path.parents):
        if entry == ROOT:
            break
        if entry.is_symlink() or getattr(entry, 'is_junction', lambda: False)():
            raise RuntimeError('交付输入包含链接，拒绝打包：' + relative)
    if not path.is_file() or not path.resolve().is_relative_to(ROOT):
        raise RuntimeError('交付输入缺失或越界：' + relative)
    return path


def inputs():
    """固定八个启动JAR及两端dist；不使用整个工作区递归打包，不包含开发数据库和证据。"""
    names = list(TEXT_FILES)
    for app in APPS:
        module = 'tradeflow-' + app
        names.append(f'{module}/target/{module}-0.1.0-SNAPSHOT.jar')
    for app in ('web-user', 'web-admin'):
        names.append(f'{app}/dist/index.html')
        asset_root = ROOT / app / 'dist/assets'
        assets = sorted(asset_root.rglob('*'))
        count = 0
        for path in assets:
            if path.is_symlink() or getattr(path, 'is_junction', lambda: False)():
                raise RuntimeError('前端资源包含链接')
            if path.is_file():
                if path.suffix.lower() not in {'.js', '.css', '.svg', '.png', '.jpg', '.jpeg', '.webp', '.ico', '.woff', '.woff2', '.ttf'}:
                    raise RuntimeError('前端资源类型不在交付白名单')
                names.append(path.relative_to(ROOT).as_posix())
                count += 1
        if not count:
            raise RuntimeError('前端资源缺失，请先构建：' + app)
    return [(name, safe_file(name)) for name in sorted(names)]


def package():
    """哈希覆盖实际写入ZIP的字节；失败保留partial排查，不将半包标成成功，也不覆盖旧包。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', default=str(ROOT / '.local/releases'))
    args = parser.parse_args()
    files = inputs()
    compose = json.loads(safe_file('deploy/compose.application.yaml').read_text(encoding='utf-8').split('\n', 1)[1])
    for name in ('web-user', 'web-admin'):
        compose['services'][name]['build']['dockerfile'] = 'deploy/Dockerfile.web.release'
    readme = safe_file('docs/runbooks/release-package.md').read_bytes()
    output = Path(args.output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    target = output / f'tradeflow-0.1.0-{stamp}-{uuid.uuid4().hex[:8]}.zip'
    partial = target.with_suffix('.zip.partial')
    entries = []
    # JAR已经压缩，再压缩耗时且收益有限；交付优先保持字节与校验清晰。
    with zipfile.ZipFile(partial, 'x', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for name, path in files:
            digest = hashlib.sha256()
            size = 0
            with path.open('rb') as source, archive.open(name, 'w', force_zip64=True) as dest:
                for block in iter(lambda: source.read(1024 * 1024), b''):
                    dest.write(block)
                    digest.update(block)
                    size += len(block)
            entries.append({'path': name, 'sizeBytes': size, 'sha256': digest.hexdigest()})
        for name, content in (
            ('deploy/compose.application.yaml', (json.dumps(compose, ensure_ascii=False, indent=2) + '\n').encode()),
            ('README.md', readme),
        ):
            archive.writestr(name, content)
            entries.append({'path': name, 'sizeBytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()})
        manifest = {'formatVersion': 1, 'version': '0.1.0', 'createdAtUtc': stamp,
                    'scope': 'JAR + frontend dist + deployment recipes; no data or secrets',
                    'verification': 'Packaging checksums only; see environment-specific acceptance reports',
                    'files': entries}
        metadata = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
        archive.writestr('manifest.json', metadata)
        sums = ''.join(f"{e['sha256']}  {e['path']}\n" for e in entries)
        sums += hashlib.sha256(metadata).hexdigest() + '  manifest.json\n'
        archive.writestr('SHA256SUMS', sums)
    partial.rename(target)
    with target.open('rb') as source:
        checksum = hashlib.file_digest(source, 'sha256').hexdigest()
    target.with_suffix('.zip.sha256').write_text(checksum + '  ' + target.name + '\n', encoding='ascii')
    print(json.dumps({'archive': str(target), 'sizeBytes': target.stat().st_size, 'sha256': checksum,
                      'payloadFiles': len(entries)}, ensure_ascii=False))


if __name__ == '__main__':
    package()
