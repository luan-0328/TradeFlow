"""只生成独立容器部署资料；不连接数据库、不启动Docker、不读取开发秘密。"""
import getpass
import json
import os
from pathlib import Path
import secrets
import subprocess
import uuid
from deployment_runtime_grants import runtime_grants
from deployment_rabbit_topology import definitions

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / '.local/deployment'
SERVICES = ('user', 'product', 'order', 'payment', 'aftersale', 'payment_simulator')


def protect(path, directory=False):
    """创建时先收紧目录权限；秘密文件不经过公共临时目录。"""
    if directory:
        path.mkdir(parents=True, exist_ok=True)
    else:
        path.touch(exist_ok=True)
    if os.name == 'nt':
        subprocess.run(['icacls', str(path), '/inheritance:r', '/grant:r', getpass.getuser()+':(OI)(CI)F' if directory else getpass.getuser()+':F'], check=True, capture_output=True)
    else:
        path.chmod(0o700 if directory else 0o600)


def write(path, content):
    """所有生成配置均视作秘密；失败不打印内容。"""
    protect(path)
    path.write_text(content, encoding='utf-8', newline='\n')


def prepare():
    """重复执行保留密钥；缺失配套JWT时拒绝静默轮换已有签名身份。"""
    # 新版本强制使用服务发现：外部注册中心必须显式提供，不生成不存在的nacos容器地址。
    if not os.environ.get('NACOS_SERVER_ADDR','').strip():
        raise RuntimeError('必须先设置容器网络可达的NACOS_SERVER_ADDR并准备注册中心。')
    for name in ('NACOS_SERVER_ADDR','NACOS_USERNAME','NACOS_PASSWORD','NACOS_NAMESPACE','NACOS_GROUP'):
        if any(ch in os.environ.get(name,'') for ch in ('\r','\n')):
            raise RuntimeError('注册中心连接参数不允许包含换行。')
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization
    protect(DEST, True)
    state = DEST/'credentials.json'
    c = json.loads(state.read_text(encoding='utf-8')) if state.exists() else {}
    for name in ('mysql_root', 'rabbit_admin', 'rabbit_cookie', 'rabbit_monitor_password', 'monitoring', 'redis_password'):
        c.setdefault(name, secrets.token_hex(32))
    # 应用账号密码需满足大小写、数字、符号及BCrypt72字节边界，不能复用纯hex用途令牌规则。
    c.setdefault('bootstrap', 'Tf!9'+secrets.token_hex(24))
    initial=c['bootstrap']
    if not (12<=len(initial.encode('utf-8'))<=72 and any(ch.isupper() for ch in initial)
            and any(ch.islower() for ch in initial) and any(ch.isdigit() for ch in initial)
            and any(not ch.isalnum() for ch in initial)):
        raise RuntimeError('已存引导密码不符合账号策略；须核验账号初始化状态后显式修正，不自动轮换已有凭据。')
    # 部署只读监控账户独立于开发节点与管理员；升级准备不轮换已有秘密。
    c.setdefault('deployment_id', str(uuid.uuid4()))
    c.setdefault('rabbit_monitor_user', 'tf_deployment_monitor')
    for service in SERVICES:
        for purpose in ('migration', 'runtime', 'mq'):
            c.setdefault(service+'_'+purpose, secrets.token_hex(32))
    for purpose in ('PRODUCT_SERVICE_TOKEN','ORDER_SERVICE_TOKEN','PAYMENT_SERVICE_TOKEN','AFTERSALE_SERVICE_TOKEN','CHANNEL_SERVICE_TOKEN','CALLBACK_SECRET','SIMULATOR_ADMIN_TOKEN'):
        c.setdefault(purpose, secrets.token_hex(32))
    private, public = DEST/'jwt-private.pem', DEST/'jwt-public.pem'
    if private.exists() != public.exists():
        raise RuntimeError('JWT文件不成对；人工恢复配套密钥，不自动轮换。')
    if not private.exists():
        if state.exists(): raise RuntimeError('已有部署记录但JWT丢失；先从受保护备份恢复。')
        key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
        write(private,key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode())
        write(public,key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode())
    write(state,json.dumps(c,indent=2))
    write(DEST/'deployment-id',c['deployment_id']+'\n')
    # 只在全新MySQL卷初始化时记录归属；旧卷缺标记时不自动认领。
    write(DEST/'ownership.sh','#!/bin/sh\nset -eu\ncp /run/tradeflow/deployment-id /var/lib/mysql/.tradeflow-deployment-id\n')
    env={'TRADEFLOW_'+k:c[k] for k in c if k.isupper()}
    env['TRADEFLOW_RABBITMQ_MONITOR_USER']=c['rabbit_monitor_user']
    env['TRADEFLOW_RABBITMQ_MONITOR_PASSWORD']=c['rabbit_monitor_password']
    for service in SERVICES:
        env[f'TRADEFLOW_{service.upper()}_DB_USER']='tf_'+service+'_app'
        env[f'TRADEFLOW_{service.upper()}_DB_PASSWORD']=c[service+'_runtime']
        env[f'TRADEFLOW_{service.upper()}_MQ_USER']='tf_'+service+'_mq'
        env[f'TRADEFLOW_{service.upper()}_MQ_PASSWORD']=c[service+'_mq']
    init=[]; grants=[]
    for service in SERVICES:
        db='trade_'+service
        # 仅官方镜像全新卷首次初始化执行；已有同名库报错，禁止baseline掩盖来源。
        init += [f'CREATE DATABASE {db} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;',
                 f"CREATE USER 'tf_{service}_migrate'@'%' IDENTIFIED BY '{c[service+'_migration']}';",
                 f"GRANT ALL PRIVILEGES ON {db}.* TO 'tf_{service}_migrate'@'%';",
                 f"CREATE USER 'tf_{service}_app'@'%' IDENTIFIED BY '{c[service+'_runtime']}';"]
        grants.append(runtime_grants(service,env))
        write(DEST/(service+'-migration.env'),f'MYSQL_HOST=mysql\nMYSQL_PORT=3306\nTRADEFLOW_{service.upper()}_MIGRATION_USER=tf_{service}_migrate\nTRADEFLOW_{service.upper()}_MIGRATION_PASSWORD={c[service+"_migration"]}\n')
    write(DEST/'bootstrap.sql','\n'.join(init))
    write(DEST/'runtime-grants.sql','\n'.join(grants))
    write(DEST/'mysql-root',c['mysql_root'])
    write(DEST/'mysql-client.cnf',f'[client]\nuser=root\npassword={c["mysql_root"]}\n')
    write(DEST/'rabbit-definitions.json',json.dumps(definitions({'user':'tf_deployment_admin','password':c['rabbit_admin']},env),indent=2))
    write(DEST/'rabbitmq.conf','listeners.tcp.default = 5672\nmanagement.tcp.port = 15672\nload_definitions = /etc/rabbitmq/definitions.json\nloopback_users.guest = true\n')
    # Redis用于入口临时配额及商品展示缓存，不作为资金/库存权威；认证材料不进入命令行。
    write(DEST/'redis.conf','bind 0.0.0.0\nprotected-mode yes\nport 6379\nrequirepass '+c['redis_password']+'\nappendonly yes\nmaxmemory 128mb\nmaxmemory-policy noeviction\n')
    write(DEST/'redis-password',c['redis_password'])
    write(DEST/'rabbit.env','RABBITMQ_ERLANG_COOKIE='+c['rabbit_cookie']+'\n')
    # 仅导出到基础设施用途文件；不注入任何业务JVM，也不修改宿主监控配置。
    write(DEST/'rabbit-monitor.env','TRADEFLOW_RABBITMQ_MONITOR_USER='+c['rabbit_monitor_user']+'\nTRADEFLOW_RABBITMQ_MONITOR_PASSWORD='+c['rabbit_monitor_password']+'\n')
    tokens={
        'gateway':[], 'user':['PRODUCT','ORDER','PAYMENT','AFTERSALE'], 'product':['PRODUCT','ORDER'],
        'order':['PRODUCT','ORDER','PAYMENT','AFTERSALE'], 'payment':['PAYMENT','ORDER','AFTERSALE','CHANNEL'],
        'aftersale':['AFTERSALE'], 'payment_simulator':['CHANNEL']}
    for service in ('gateway',)+SERVICES:
        app={'SPRING_PROFILES_ACTIVE':'dev','TRADEFLOW_JWT_PUBLIC_KEY':'/keys/jwt-public.pem',
             'TRADEFLOW_JWT_ISSUER':'tradeflow','TRADEFLOW_MONITORING_TOKEN':c['monitoring']}
        if service!='gateway':
            app.update(MYSQL_HOST='mysql',MYSQL_PORT='3306')
            app.update({k:v for k,v in env.items() if k.startswith('TRADEFLOW_'+service.upper()+'_DB_')})
            # simulator dev配置原来写死回环，用标准Spring环境变量覆盖，不改应用配置文件。
            app['SPRING_DATASOURCE_URL']=f'jdbc:mysql://mysql:3306/trade_{service}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
        for token in tokens[service]:app['TRADEFLOW_'+token+'_SERVICE_TOKEN']=c[token+'_SERVICE_TOKEN']
        if service in ('gateway','product','user'):app.update(REDIS_HOST='redis',REDIS_PORT='6379',REDIS_PASSWORD=c['redis_password'])
        if service=='user':
            for name in ('TRADEFLOW_MAIL_USERNAME','TRADEFLOW_MAIL_PASSWORD'):
                value=os.environ.get(name,'')
                if any(ch in value for ch in ('\r','\n')):raise RuntimeError('邮件配置不允许换行')
                app[name]=value
        # 可配置额度只传所属进程；拒绝换行避免生成env文件时注入其他配置项。
        limits=('TRADEFLOW_LOGIN_WINDOW_SECONDS','TRADEFLOW_LOGIN_ACCOUNT_LIMIT','TRADEFLOW_LOGIN_SOURCE_LIMIT') if service=='user' else (
          'TRADEFLOW_ENTRY_KEY_PREFIX','TRADEFLOW_ENTRY_TIMEOUT_MILLIS','TRADEFLOW_ENTRY_WINDOW_SECONDS',
          'TRADEFLOW_ENTRY_LOGIN_LIMIT','TRADEFLOW_ENTRY_LOGIN_TOTAL','TRADEFLOW_ENTRY_ORDER_LIMIT','TRADEFLOW_ENTRY_ORDER_TOTAL',
          'TRADEFLOW_ENTRY_QUERY_LIMIT','TRADEFLOW_ENTRY_QUERY_TOTAL','TRADEFLOW_ENTRY_RECOVERY_LIMIT','TRADEFLOW_ENTRY_RECOVERY_TOTAL',
          'TRADEFLOW_ENTRY_WRITE_LIMIT','TRADEFLOW_ENTRY_WRITE_TOTAL') if service=='gateway' else ()
        for name in limits:
            if name in os.environ:
                if any(ch in os.environ[name] for ch in ('\r','\n')):raise RuntimeError('入口保护参数包含非法换行。')
                app[name]=os.environ[name]

        if service=='user':app.update(TRADEFLOW_JWT_PRIVATE_KEY='/keys/jwt-private.pem',TRADEFLOW_BOOTSTRAP_ENABLED='true',TRADEFLOW_BOOTSTRAP_USERNAME='platform_admin',TRADEFLOW_BOOTSTRAP_PASSWORD=c['bootstrap'])
        if service in ('payment','payment_simulator'):app['TRADEFLOW_CALLBACK_SECRET']=c['CALLBACK_SECRET']
        if service=='payment_simulator':app['TRADEFLOW_SIMULATOR_ADMIN_TOKEN']=c['SIMULATOR_ADMIN_TOKEN']
        if service=='aftersale':app['TRADEFLOW_EVIDENCE_DIRECTORY']='/evidence'
        if service in ('order','payment','aftersale'):
            app.update(RABBITMQ_HOST='rabbitmq',RABBITMQ_PORT='5672',RABBITMQ_VHOST='/tradeflow')
            app.update({k:v for k,v in env.items() if k.startswith('TRADEFLOW_'+service.upper()+'_MQ_')})
        # 所有调用通过Nacos；容器内注册地址自动探测，不注册宿主回环IP。
        nacos_keys=('NACOS_USERNAME','NACOS_PASSWORD','NACOS_NAMESPACE','NACOS_GROUP')
        app.update({name:os.environ.get(name,'TRADEFLOW' if name=='NACOS_GROUP' else '') for name in nacos_keys})
        app.update(NACOS_SERVER_ADDR=os.environ['NACOS_SERVER_ADDR'],NACOS_DISCOVERY_IP='',
                   TRADEFLOW_NACOS_CONFIG_ENABLED='false')
        write(DEST/(service+'.env'),'\n'.join(k+'='+v for k,v in app.items())+'\n')
    # Rabbit以镜像内专用UID读取挂载文件；宿主父目录仍700，其他宿主用户不可遍历。
    if os.name!='nt':
        for name in ('rabbit-definitions.json','rabbitmq.conf','deployment-id','ownership.sh','redis.conf','redis-password'):
            (DEST/name).chmod(0o644)
        # 固定MySQL8.0.46官方镜像初始化会降权到mysql(UID/GID999)，需只读根密码与bootstrap。
        # 仅这两个挂载文件授予该专用组读取；宿主父目录仍700，其他宿主用户无法遍历。
        for name in ('bootstrap.sql','mysql-root'):
            os.chown(DEST/name,0,999)
            (DEST/name).chmod(0o640)
    protect(DEST/'evidence',True)
    print('Protected deployment material prepared in .local/deployment; no service started; no secrets printed.')


if __name__=='__main__':
    try: prepare()
    except Exception:
        raise SystemExit('部署准备失败；未回显秘密，请检查依赖、文件权限和配套密钥。') from None
