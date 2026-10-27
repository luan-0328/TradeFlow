"""停写窗口内备份容器专用六库、证据和配置；只备份，不提供破坏性恢复入口。"""
import argparse
import datetime
import hashlib
import json
import os
import re
import uuid
from pathlib import Path
import subprocess
import zipfile
import importlib.util
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent/'verification'))
VERIFY_SPEC=importlib.util.spec_from_file_location('backup_verify',Path(__file__).resolve().parent/'verification/backup-verify.py')
VERIFY=importlib.util.module_from_spec(VERIFY_SPEC)
VERIFY_SPEC.loader.exec_module(VERIFY)

ROOT=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('deployment',ROOT/'scripts/Prepare-Deployment.py')
DEPLOY=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEPLOY)
APPS={'gateway','user','product','order','payment','aftersale','payment-simulator'}
DATABASES=['trade_'+s for s in DEPLOY.SERVICES]
COMMAND=None
DOCKER=None
IDENTITY=None


def invoke(command, output=None):
    """固定参数调用，不回显Docker可能包含秘密的原始错误。"""
    result=subprocess.run(command,stdout=output or subprocess.PIPE,stderr=subprocess.PIPE,timeout=1800)
    if result.returncode: raise RuntimeError('容器备份命令失败；未输出原始错误。')
    return result.stdout.decode().strip() if output is None else None


def run(arguments, output=None):
    """MySQL操作固定到已验明身份的容器ID，避免Compose目标切换。"""
    if arguments[:3]==['exec','-T','mysql']:
        return invoke(DOCKER+['exec','-i',IDENTITY['containerId']]+arguments[3:],output)
    return invoke(COMMAND+arguments,output)


def host_path(value):
    """统一Docker Desktop的宿主挂载表示，仅接受当前目录的精确映射。"""
    value=value.replace('\\','/').rstrip('/')
    if os.name=='nt':
        value=value.lower()
        for prefix in ('/run/desktop/mnt/host/','/host_mnt/'):
            if value.startswith(prefix): value=value[len(prefix):]
        if re.match(r'^/[a-z]/',value): value=value[1:]
        if re.match(r'^[a-z]:/',value): value=value[0]+value[2:]
    return value


def inspect_identity():
    """校验固定项目、当前目录只读标记挂载及卷内首次初始化归属。"""
    container_id=invoke(COMMAND+['ps','-q','mysql'])
    if not re.fullmatch(r'[a-f0-9]{12,64}',container_id):raise RuntimeError('没有唯一运行MySQL容器。')
    info=json.loads(invoke(DOCKER+['inspect',container_id]))[0]
    labels=info.get('Config',{}).get('Labels',{}) or {}
    if labels.get('com.docker.compose.project')!='tradeflow-b8' or labels.get('com.docker.compose.service')!='mysql':
        raise RuntimeError('容器不属于固定部署项目。')
    mount=next((m for m in info['Mounts'] if m['Destination']=='/run/tradeflow/deployment-id'),None)
    expected=str((DEPLOY.DEST/'deployment-id').resolve())
    if not mount or mount.get('Type')!='bind' or mount.get('RW') or host_path(mount['Source'])!=host_path(expected):
        raise RuntimeError('容器标记挂载不属于当前部署目录。')
    state=json.loads((DEPLOY.DEST/'credentials.json').read_text(encoding='utf-8'))
    deployment_id=str(uuid.UUID(state['deployment_id']))
    if (DEPLOY.DEST/'deployment-id').read_text(encoding='utf-8').strip()!=deployment_id:
        raise RuntimeError('本地部署身份文件不一致。')
    for marker in ('/run/tradeflow/deployment-id','/var/lib/mysql/.tradeflow-deployment-id'):
        if invoke(DOCKER+['exec',info['Id'],'cat',marker])!=deployment_id:
            raise RuntimeError('MySQL持久卷身份不匹配；禁止自动认领旧卷。')
    data_mount=next((m for m in info['Mounts'] if m['Destination']=='/var/lib/mysql'),None)
    if not data_mount:raise RuntimeError('缺少MySQL持久卷。')
    return {'deploymentId':deployment_id,'containerId':info['Id'],'containerName':info['Name'],
            'imageId':info['Image'],'mysqlDataMount':data_mount,'markerMountSource':mount['Source']}


def pin_local_deployment():
    """拒绝环境端点覆盖，只允许经inspect确认的本机Unix/npipe context。"""
    global COMMAND,DOCKER,IDENTITY
    if any(os.environ.get(k) for k in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH')):
        raise RuntimeError('拒绝Docker端点覆盖；请先清除DOCKER_HOST/CONTEXT/TLS环境变量。')
    context=invoke(['docker','context','show'])
    data=json.loads(invoke(['docker','context','inspect',context]))[0]
    endpoint=data['Endpoints']['docker']['Host']
    local_unix=endpoint.startswith('unix:///') and not endpoint.startswith('unix:////')
    local_pipe=bool(re.fullmatch(r'npipe:/{4}\./pipe/[A-Za-z0-9_.-]+',endpoint))
    if not (local_unix or local_pipe):raise RuntimeError('只允许本机Unix/npipe Docker端点，拒绝TCP/SSH。')
    DOCKER=['docker','--host',endpoint]
    COMMAND=DOCKER+['compose','-p','tradeflow-b8','--project-directory',str(ROOT/'deploy'),'-f',str(ROOT/'deploy/compose.application.yaml')]
    IDENTITY=inspect_identity()
    IDENTITY.update(dockerContext=context,dockerEndpoint=endpoint)


def verify_identity():
    """备份过程中替换容器/标记/持久卷立即中止，不混合另一部署的数据。"""
    actual=inspect_identity()
    for key in ('deploymentId','containerId','imageId','mysqlDataMount','markerMountSource'):
        if actual[key]!=IDENTITY[key]:raise RuntimeError('部署身份在备份期间变化，拒绝完成。')


def quiescent():
    """必须应用全停且无运行账号连接；不能仅靠操作员确认推定停写。"""
    verify_identity()
    if APPS.intersection(run(['ps','--status','running','--services']).splitlines()):
        raise RuntimeError('仍有业务容器运行，拒绝备份。')
    users=','.join("'tf_"+s+"_app'" for s in DEPLOY.SERVICES)
    sql=f'SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER IN ({users});'
    if run(['exec','-T','mysql','mysql','--defaults-extra-file=/run/secrets/mysql-client.cnf','-N','-B','-e',sql])!='0':
        raise RuntimeError('仍有业务数据库连接，拒绝备份。')


def backup(confirmed):
    """六库共享一次一致性快照；停写窗口覆盖证据归档，任何失败均不生成成功标记。"""
    if not confirmed: raise RuntimeError('需显式--confirm-quiescent，确认所有外部写入者/迁移已停且窗口持续至完成。')
    if any(p.is_symlink() or p.is_junction() for p in [DEPLOY.DEST,*DEPLOY.DEST.parents,*DEPLOY.DEST.rglob('*')]):
        raise RuntimeError('部署路径包含链接，拒绝读取或归档。')
    if not (DEPLOY.DEST/'credentials.json').exists():raise RuntimeError('没有独立部署记录，拒绝读取其他实例。')
    pin_local_deployment()
    quiescent()
    target=ROOT/'.local/backups'
    if any(p.is_symlink() or p.is_junction() for p in [target,*target.parents]):raise RuntimeError('备份输出路径包含链接。')
    DEPLOY.protect(target,True)
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    work=target/(stamp+'.incomplete')
    DEPLOY.protect(work,True)
    dump=work/'databases.sql';DEPLOY.protect(dump)
    with dump.open('wb') as output:
        run(['exec','-T','mysql','mysqldump','--defaults-extra-file=/run/secrets/mysql-client.cnf',
             '--single-transaction','--quick','--routines','--events','--triggers','--hex-blob',
             '--set-gtid-purged=OFF','--no-tablespaces','--databases']+DATABASES,output)
    quiescent()
    # 不跟随符号链接，防止归档越界或把无关目录秘密加入备份。
    files=[p for p in DEPLOY.DEST.rglob('*') if p.is_file()]
    if any(p.is_symlink() or p.is_junction() for p in [DEPLOY.DEST,*DEPLOY.DEST.parents,*DEPLOY.DEST.rglob('*')]):raise RuntimeError('部署目录存在链接，拒绝归档。')
    # 在同一停写窗口导出有效证据引用；恢复前可核对数据库承诺的字节数和内容散列。
    evidence_rows=run(['exec','-T','mysql','mysql','--defaults-extra-file=/run/secrets/mysql-client.cnf','-N','-B','-e',
        "SELECT object_key,size_bytes,content_hash FROM trade_aftersale.evidence_file WHERE state IN ('UPLOADED','BOUND') ORDER BY object_key;"])
    evidence=[]
    for line in evidence_rows.splitlines():
        key,size,content_hash=line.split('\t')
        if not re.fullmatch(r'ev_[0-9a-f]{32}',key) or not re.fullmatch(r'[0-9a-f]{64}',content_hash):raise RuntimeError('证据索引格式无效。')
        evidence.append({'objectKey':key,'sizeBytes':int(size),'sha256':content_hash})
    DEPLOY.write(work/'evidence-index.json',json.dumps({'deploymentId':IDENTITY['deploymentId'],'files':evidence},indent=2))
    archive=work/'protected-config-evidence.zip';DEPLOY.protect(archive)
    manifest={'formatVersion':2,'deploymentIdentity':IDENTITY,'createdAtUtc':stamp,'databases':DATABASES,'quiescentConfirmed':True,
              'scope':'six databases, deployment credentials/JWT/Rabbit definitions, evidence; not broker messages', 'files':{}}
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for p in files:
            name=p.relative_to(DEPLOY.DEST).as_posix()
            VERIFY.safe_name(name)
            z.write(p,name)
            # 对真正写入归档的字节计算散列，避免再次读取源文件产生混合版本manifest。
            with z.open(name) as stream:
                manifest['files']['archive/'+name]=hashlib.file_digest(stream,'sha256').hexdigest()
    quiescent()
    for p in (dump,archive,work/'evidence-index.json'):
        with p.open('rb') as stream:manifest['files'][p.name]=hashlib.file_digest(stream,'sha256').hexdigest()
    DEPLOY.write(work/'manifest.json',json.dumps(manifest,indent=2))
    # 完成标记只能在离线检查也通过后生成；失败继续保留.incomplete。
    VERIFY.verify_bundle(work,allow_incomplete=True)
    final=target/stamp
    work.rename(final)
    print('Backup completed in protected .local/backups/'+stamp+'; keep encrypted offline copy; restore not executed.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-quiescent',action='store_true')
    try: backup(parser.parse_args().confirm_quiescent)
    except Exception:
        raise SystemExit('备份未完成；保留.incomplete供人工排查，不得作为可恢复备份。确认停写、权限、磁盘和工具。') from None
