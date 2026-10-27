"""只读检查备份字节、证据关联与隔离计划；绝不解压、连接服务或执行恢复。"""
import argparse
import hashlib
import json
import re
import stat
import uuid
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit

DATABASES = ['trade_' + s for s in ('user', 'product', 'order', 'payment', 'aftersale', 'payment_simulator')]
REQUIRED = {'credentials.json', 'deployment-id', 'jwt-private.pem', 'jwt-public.pem',
            'rabbit-definitions.json'} | {s + '.env' for s in ('gateway', 'user', 'product', 'order', 'payment', 'aftersale', 'payment_simulator')}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def safe_name(name):
    """拒绝跨平台路径越界及Windows盘符/备用数据流，未通过前不读取归档成员。"""
    path = PurePosixPath(name)
    require(bool(name) and not path.is_absolute() and '\\' not in name and ':' not in name
            and all(p not in ('', '.', '..') for p in name.split('/')),
            '归档含不安全路径')
    require(all(not p.endswith((' ', '.')) and p.split('.')[0].upper() not in
                {'CON', 'PRN', 'AUX', 'NUL'} | {f'{s}{i}' for s in ('COM', 'LPT') for i in range(1, 10)}
                for p in path.parts), '归档含Windows特殊路径')
    return name


def digest(stream):
    return hashlib.file_digest(stream, 'sha256').hexdigest()


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def verify_bundle(directory, allow_incomplete=False):
    """manifest须来自可信离线副本；逐字节验散列，无法用可篡改的散列证明来源真实性。"""
    directory = Path(directory)
    require(directory.is_dir() and (allow_incomplete or not directory.name.endswith('.incomplete')), '不接受未完成备份')
    require(not any(p.is_symlink() or p.is_junction() for p in [directory, *directory.parents]), '备份路径包含链接')
    for name in ('manifest.json', 'databases.sql', 'protected-config-evidence.zip', 'evidence-index.json'):
        path = directory / name
        require(path.is_file() and not path.is_symlink() and not path.is_junction(), '备份缺失或含链接文件')
    manifest = load_json(directory / 'manifest.json')
    require(manifest.get('formatVersion') == 2, '需要v2备份；旧备份缺证据索引，不能声称关联验证通过')
    require(manifest.get('databases') == DATABASES and manifest.get('quiescentConfirmed') is True, '六库/停写声明不符')
    identity = manifest['deploymentIdentity']
    deployment_id = str(uuid.UUID(identity['deploymentId']))
    files = manifest['files']
    require(all(isinstance(v, str) and re.fullmatch('[0-9a-f]{64}', v) for v in files.values()), '散列格式错误')
    for name in ('databases.sql', 'protected-config-evidence.zip', 'evidence-index.json'):
        with (directory / name).open('rb') as stream:
            require(digest(stream) == files.get(name), '备份文件散列不符')
    require((directory / 'databases.sql').stat().st_size > 0, '数据库备份为空')
    with zipfile.ZipFile(directory / 'protected-config-evidence.zip') as archive:
        members = archive.infolist()
        names = [safe_name(i.filename) for i in members]
        require(len({n.casefold() for n in names}) == len(names), '归档含重复或大小写冲突路径')
        folded = {n.casefold() for n in names}
        require(not any(str(parent).casefold() in folded for name in names
                        for parent in PurePosixPath(name).parents if str(parent) != '.'), '归档文件与目录路径冲突')
        require(REQUIRED <= set(names), '缺少配套秘密或服务配置')
        require(set(files) == {'databases.sql', 'protected-config-evidence.zip', 'evidence-index.json'} |
                {'archive/' + n for n in names}, 'manifest与归档成员不完全对应')
        for info in members:
            kind = stat.S_IFMT(info.external_attr >> 16)
            require(kind in (0, stat.S_IFREG) and not info.is_dir(), '归档含链接或非普通文件')
            with archive.open(info) as stream:
                require(digest(stream) == files['archive/' + info.filename], '归档成员散列不符')
        require(archive.read('deployment-id').decode().strip() == deployment_id and
                json.loads(archive.read('credentials.json'))['deployment_id'] == deployment_id,
                '秘密与部署身份不匹配')
        evidence = load_json(directory / 'evidence-index.json')
        require(evidence['deploymentId'] == deployment_id, '证据索引部署身份不符')
        seen = set()
        for row in evidence['files']:
            key = row['objectKey']
            require(isinstance(key, str) and re.fullmatch('ev_[0-9a-f]{32}', key) and key not in seen, '证据键无效或重复')
            seen.add(key)
            name = 'evidence/' + key
            require(name in names and files['archive/' + name] == row['sha256'] and
                    archive.getinfo(name).file_size == row['sizeBytes'], '数据库证据引用与文件不一致')
        # 仅供隔离计划比对，不返回/打印配置中的密码或JWT。
        env = dict(line.split('=', 1) for line in archive.read('order.env').decode().splitlines() if '=' in line)
    return {'deploymentId': deployment_id, 'evidenceCount': len(seen), 'sourceRegistry': env.get('NACOS_SERVER_ADDR', ''),
            'sourceNamespace': env.get('NACOS_NAMESPACE', ''), 'sourceBroker': env.get('RABBITMQ_HOST', ''),
            'sourceBrokerPort': env.get('RABBITMQ_PORT', '5672')}


def loopback_endpoint(value):
    """计划仅允许明确的回环端点，避免别名/DNS将演练流量带回原环境。"""
    parsed = urlsplit('tcp://' + value)
    require(parsed.hostname in ('127.0.0.1', '::1') and parsed.port is not None and
            parsed.username is None and parsed.password is None and not parsed.path and not parsed.query and not parsed.fragment,
            '隔离端点必须是带端口的明确回环地址')


def endpoint_key(value, default_port=None):
    """仅规范化字面端点/回环别名，不做DNS查询；实际网络出口仍须现场阻断。"""
    parsed = urlsplit(value if '://' in value else 'tcp://' + value.strip())
    host = parsed.hostname
    return ('loopback' if host in ('127.0.0.1', '::1', 'localhost') else host, parsed.port or default_port)


def verify_plan(plan, bundle):
    """只验证可静态证明的前置条件；不声称目标实例已空、网络已隔离或允许导入。"""
    require(plan['sourceDeploymentId'] == bundle['deploymentId'], '恢复计划关联了错误备份')
    destination_id = str(uuid.UUID(plan['destinationDeploymentId']))
    require(destination_id != bundle['deploymentId'], '恢复部署身份不能复用原身份')
    require(re.fullmatch(r'tradeflow-restore-[a-z0-9-]+', plan['project']) is not None, '必须使用独立恢复项目')
    paths = [Path(plan[key]) for key in ('mysqlDataDirectory', 'evidenceDirectory')]
    for path in paths:
        require(path.is_absolute() and '..' not in path.parts and not path.resolve().exists() and not path.is_symlink(), '恢复目录必须为不存在的绝对新路径')
        require(not any(p.is_symlink() or p.is_junction() for p in path.parents), '恢复目录父路径含链接')
    paths = [path.resolve() for path in paths]
    require(paths[0] != paths[1] and paths[0] not in paths[1].parents and paths[1] not in paths[0].parents,
            '恢复目录不得重叠')
    for key in ('mysqlEndpoint', 'registryEndpoint', 'brokerEndpoint'):
        loopback_endpoint(plan[key])
    require(len({plan[k] for k in ('mysqlEndpoint', 'registryEndpoint', 'brokerEndpoint')}) == 3, '隔离端点不能重叠')
    require(urlsplit('tcp://' + plan['mysqlEndpoint']).port not in (3306, 13316), '拒绝常用原实例MySQL端口')
    source_registries = [endpoint_key(value) for value in bundle['sourceRegistry'].split(',') if value.strip()]
    require(endpoint_key(plan['registryEndpoint']) not in source_registries and
            bool(plan['registryNamespace']) and plan['registryNamespace'] != bundle['sourceNamespace'],
            '禁止恢复服务注册原registry/namespace')
    require(endpoint_key(plan['brokerEndpoint']) != endpoint_key(bundle['sourceBroker'], int(bundle['sourceBrokerPort'])), '禁止连接原MQ')
    require(plan.get('networkEgressDenied') is True and plan.get('applicationsDisabled') is True and
            plan.get('bootstrapDisabled') is True and plan.get('freshVolumesOnly') is True,
            '必须声明阻断外联、禁启业务/初始化脚本及仅用新卷')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--plan', type=Path, help='可选：只读隔离计划JSON，不连接目标')
    args = parser.parse_args()
    try:
        bundle = verify_bundle(args.bundle)
        if args.plan:
            verify_plan(load_json(args.plan), bundle)
        print(json.dumps({'integrity': 'passed', 'evidenceReferences': bundle['evidenceCount'],
                          'isolationPlan': 'static-preconditions-passed' if args.plan else 'not-checked',
                          'restoreExecuted': False, 'databaseEmptinessVerified': False}))
    except Exception:
        raise SystemExit('校验拒绝：备份完整性、证据关联或隔离计划不满足要求；未连接服务、未解压、未恢复。') from None


if __name__ == '__main__':
    main()
