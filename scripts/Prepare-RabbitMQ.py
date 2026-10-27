"""准备TradeFlow专用本机RabbitMQ，绝不连接远程或接管其他节点。"""
import argparse, base64, hashlib, importlib.util, json, os, secrets, shutil, socket, subprocess, time, urllib.request, urllib.error, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
LOCAL=ROOT/'.local'
BROKER=LOCAL/'rabbitmq'
VERSION='4.1.8'
PORT=5678
MANAGEMENT=15678
NODE='tradeflow_local@localhost'
CREDENTIALS=LOCAL/'rabbitmq-admin.json'
spec=importlib.util.spec_from_file_location('tradeflow_dev',ROOT/'scripts/Prepare-Dev.py')
dev=importlib.util.module_from_spec(spec);spec.loader.exec_module(dev)


def occupied(port):
    """探测本机端口只判断监听，不能据此认领已有Rabbit节点。"""
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=1): return True
    except OSError:return False


def request(credentials,path,method='GET',body=None):
    """管理员HTTP仅本机回环，凭据不在命令行/URL或输出中出现。"""
    payload=None if body is None else json.dumps(body).encode()
    token=base64.b64encode((credentials['user']+':'+credentials['password']).encode()).decode()
    req=urllib.request.Request(f'http://127.0.0.1:{MANAGEMENT}/api/'+path,data=payload,method=method,headers={'Authorization':'Basic '+token,'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=5) as response:
        data=response.read()
        return json.loads(data) if data else None


def password_hash(value):
    """定义导入使用Rabbit salted SHA256哈希，文件中不放明文业务密码。"""
    salt=secrets.token_bytes(4)
    return base64.b64encode(salt+hashlib.sha256(salt+value.encode()).digest()).decode()


def settings():
    """增量生成专用凭据，复用本机原密码；不会轮换其他项目或既有应用密钥。"""
    _,env=dev.settings()
    if CREDENTIALS.exists(): credentials=json.loads(CREDENTIALS.read_text(encoding='utf-8'))
    else:
        credentials={'user':'tf_broker_admin','password':secrets.token_hex(32),'cookie':secrets.token_hex(32)}
        dev.protected_write(CREDENTIALS,json.dumps(credentials,indent=2))
    env.setdefault('TRADEFLOW_MONITORING_TOKEN',secrets.token_hex(32))
    env.setdefault('TRADEFLOW_RABBITMQ_MONITOR_USER','tf_monitoring')
    env.setdefault('TRADEFLOW_RABBITMQ_MONITOR_PASSWORD',secrets.token_hex(32))
    env.update(RABBITMQ_HOST='127.0.0.1',RABBITMQ_PORT=str(PORT),RABBITMQ_VHOST='/tradeflow')
    for service in ('PAYMENT','ORDER','AFTERSALE'):
        env.setdefault(f'TRADEFLOW_{service}_MQ_USER','tf_'+service.lower()+'_mq')
        env.setdefault(f'TRADEFLOW_{service}_MQ_PASSWORD',secrets.token_hex(32))
    dev.protected_write(dev.ENV,json.dumps(env,indent=2))
    return credentials,env


def definitions(credentials,env):
    """在业务启动前声明全拓扑与最小运行能力，避免mandatory有交换机却无正确消费者。"""
    users=[{'name':credentials['user'],'password_hash':password_hash(credentials['password']),'hashing_algorithm':'rabbit_password_hashing_sha256','tags':['administrator']}]
    permissions=[{'user':credentials['user'],'vhost':'/tradeflow','configure':'.*','write':'.*','read':'.*'}]
    # management标签提供指定vhost管理API可见性；空资源权限禁止消费、发布及声明。
    monitor=env['TRADEFLOW_RABBITMQ_MONITOR_USER']
    users.append({'name':monitor,'password_hash':password_hash(env['TRADEFLOW_RABBITMQ_MONITOR_PASSWORD']),'hashing_algorithm':'rabbit_password_hashing_sha256','tags':['management']})
    permissions.append({'user':monitor,'vhost':'/tradeflow','configure':'^$','write':'^$','read':'^$'})
    topic=[]
    for service in ('PAYMENT','ORDER','AFTERSALE'):
        user=env[f'TRADEFLOW_{service}_MQ_USER']
        users.append({'name':user,'password_hash':password_hash(env[f'TRADEFLOW_{service}_MQ_PASSWORD']),'hashing_algorithm':'rabbit_password_hashing_sha256','tags':[]})
        read={'PAYMENT':'^$','ORDER':r'^q\.order\.(payment|refund|audit)$','AFTERSALE':r'^q\.aftersale\.refund$'}[service]
        permissions.append({'user':user,'vhost':'/tradeflow','configure':'^$','write':r'^tradeflow\.(events|dlx)$','read':read})
        routes={'PAYMENT':r'^(payment\.(succeeded|failed|closed)|refund\.(succeeded|failed))\.v1$','ORDER':r'^order\.(paid|closed)\.v1$','AFTERSALE':r'^aftersale\.completed\.v1$'}[service]
        dead={'PAYMENT':'^$','ORDER':r'^q\.order\.(payment|refund|audit)\.dlq$','AFTERSALE':r'^q\.aftersale\.refund\.dlq$'}[service]
        for exchange,write in [('tradeflow.events',routes),('tradeflow.dlx',dead)]:topic.append({'user':user,'vhost':'/tradeflow','exchange':exchange,'write':write,'read':'.*'})
    queues=[];bindings=[]
    routes={'order.payment':['payment.succeeded.v1','payment.failed.v1','payment.closed.v1'],'order.refund':['refund.succeeded.v1','refund.failed.v1'],'aftersale.refund':['refund.succeeded.v1','refund.failed.v1'],'order.audit':['order.paid.v1','order.closed.v1','aftersale.completed.v1']}
    for consumer,keys in routes.items():
        queue='q.'+consumer
        queues.append({'name':queue,'vhost':'/tradeflow','durable':True,'auto_delete':False,'arguments':{'x-queue-type':'classic','x-dead-letter-exchange':'tradeflow.dlx','x-dead-letter-routing-key':queue+'.dlq'}})
        queues.append({'name':queue+'.dlq','vhost':'/tradeflow','durable':True,'auto_delete':False,'arguments':{'x-queue-type':'classic'}})
        for key in keys:bindings.append({'source':'tradeflow.events','vhost':'/tradeflow','destination':queue,'destination_type':'queue','routing_key':key,'arguments':{}})
        bindings.append({'source':'tradeflow.dlx','vhost':'/tradeflow','destination':queue+'.dlq','destination_type':'queue','routing_key':queue+'.dlq','arguments':{}})
    return {'users':users,'vhosts':[{'name':'/tradeflow'}],'permissions':permissions,'topic_permissions':topic,'exchanges':[{'name':name,'vhost':'/tradeflow','type':'topic','durable':True,'auto_delete':False,'internal':False,'arguments':{}} for name in ('tradeflow.events','tradeflow.dlx')],'queues':queues,'bindings':bindings}


def runtime_env(credentials):
    """所有端口、数据和cookie属专用节点，不改系统服务/全局Erlang配置。"""
    env=os.environ.copy()
    erl=shutil.which('erl.exe')
    if not erl:raise RuntimeError('未找到本机Erlang erl.exe，请设置PATH。')
    env.update(ERLANG_HOME=str(Path(erl).parents[1]),RABBITMQ_BASE=str(BROKER),RABBITMQ_MNESIA_BASE=str(BROKER/'db'),RABBITMQ_LOG_BASE=str(BROKER/'log'),RABBITMQ_CONFIG_FILE=str(BROKER/'rabbitmq'),RABBITMQ_ENABLED_PLUGINS_FILE=str(BROKER/'enabled_plugins'),RABBITMQ_NODENAME=NODE,RABBITMQ_NODE_PORT=str(PORT),RABBITMQ_DIST_PORT='25678',RABBITMQ_ERLANG_COOKIE=credentials['cookie'],ERL_EPMD_ADDRESS='127.0.0.1',ERL_EPMD_PORT='43678',RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='-kernel inet_dist_use_interface {127,0,0,1} +S 2:2')
    return env


def install():
    """只从官方固定release下载，校验GitHub公布摘要，安全展开到专用tools目录。"""
    target=LOCAL/'tools'/f'rabbitmq_server-{VERSION}'
    if (target/'sbin/rabbitmq-server.bat').exists():return target
    tools=LOCAL/'tools';tools.mkdir(parents=True,exist_ok=True)
    req=urllib.request.Request(f'https://api.github.com/repos/rabbitmq/rabbitmq-server/releases/tags/v{VERSION}',headers={'User-Agent':'TradeFlow-local-development'})
    with urllib.request.urlopen(req,timeout=30) as response: release=json.load(response)
    name=f'rabbitmq-server-windows-{VERSION}.zip'
    asset=next(a for a in release['assets'] if a['name']==name)
    archive=tools/name
    with urllib.request.urlopen(asset['browser_download_url'],timeout=60) as response,archive.open('wb') as file:shutil.copyfileobj(response,file)
    digest=asset.get('digest')
    if digest and digest.startswith('sha256:') and hashlib.sha256(archive.read_bytes()).hexdigest()!=digest[7:]:raise RuntimeError('RabbitMQ官方摘要不一致，拒绝解压。')
    with zipfile.ZipFile(archive) as source:
        for member in source.infolist():
            resolved=(tools/member.filename).resolve()
            if not resolved.is_relative_to(tools.resolve()):raise RuntimeError('归档包含越界路径，拒绝解压。')
        source.extractall(tools)
    if not (target/'sbin/rabbitmq-server.bat').exists():raise RuntimeError('官方归档结构与预期不同。')
    print('RabbitMQ '+VERSION+' official archive installed in project tools; global installation unchanged.')
    return target


def prepare(export_only=False):
    """校验专用节点身份后复用，首次启动前写入受保护配置/拓扑，无业务消息发送。"""
    credentials,env=settings();BROKER.mkdir(exist_ok=True)
    defs=definitions(credentials,env)
    dev.protected_write(BROKER/'definitions.json',json.dumps(defs))
    # Docker与本机节点二选一，沿用相同拓扑/用户，但容器监听其内部端口。
    compose_conf=f"listeners.tcp.default = 5672\nmanagement.tcp.port = 15672\nload_definitions = /etc/rabbitmq/definitions.json\ndefault_user = {credentials['user']}\ndefault_pass = {credentials['password']}\nvm_memory_high_watermark.absolute = 256MiB\n"
    dev.protected_write(BROKER/'compose-rabbitmq.conf',compose_conf)
    if export_only:
        print('Protected RabbitMQ definitions/config exported; no node started.')
        return
    if occupied(MANAGEMENT):
        nodes=request(credentials,'nodes')
        if len(nodes)!=1 or nodes[0]['name']!=NODE:raise RuntimeError('管理端口不是项目专用节点，拒绝接管。')
        request(credentials,'definitions','POST',defs)
        print('Dedicated RabbitMQ existing node reused; topology provisioned.')
        return
    for port in (PORT,MANAGEMENT,25678,43678):
        if occupied(port):raise RuntimeError(f'专用端口{port}已被占用，未接管或停止其他程序。')
    target=install()
    conf=f"""listeners.tcp.1 = 127.0.0.1:{PORT}
management.tcp.ip = 127.0.0.1
management.tcp.port = {MANAGEMENT}
loopback_users.guest = true
load_definitions = {(BROKER/'definitions.json').as_posix()}
default_user = {credentials['user']}
default_pass = {credentials['password']}
vm_memory_high_watermark.absolute = 256MiB
disk_free_limit.absolute = 256MB
log.console = false
"""
    dev.protected_write(BROKER/'rabbitmq.conf',conf)
    dev.protected_write(BROKER/'enabled_plugins','[rabbitmq_management].\n')
    with (BROKER/'launch.log').open('ab') as log:
        process=subprocess.Popen(['cmd.exe','/d','/c',str(target/'sbin/rabbitmq-server.bat')],cwd=ROOT,env=runtime_env(credentials),stdin=subprocess.DEVNULL,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        if occupied(MANAGEMENT):
            try:
                nodes=request(credentials,'nodes')
                if len(nodes)==1 and nodes[0]['name']==NODE and nodes[0].get('running'):
                    request(credentials,'definitions','POST',defs)
                    (LOCAL/'rabbitmq-process.json').write_text(json.dumps({'node':NODE,'port':PORT,'managementPort':MANAGEMENT,'pid':nodes[0].get('os_pid'),'dataDir':str(BROKER/'db')},indent=2))
                    print(f'Dedicated RabbitMQ {VERSION} ready at 127.0.0.1:{PORT}; management {MANAGEMENT}; credentials not printed.')
                    return
            except (OSError,ValueError):pass
        if process.poll() is not None:raise RuntimeError('专用Rabbit启动进程退出，请查看.local/rabbitmq/launch.log。')
        time.sleep(.5)
    raise RuntimeError('专用RabbitMQ就绪超时，保留日志和数据，不操作其他实例。')


def stop():
    """仅管理API确认专用节点后发起正常shutdown，不按名称终止其他Erlang进程。"""
    if not CREDENTIALS.exists() or not occupied(MANAGEMENT):print('Dedicated RabbitMQ not running.');return
    credentials=json.loads(CREDENTIALS.read_text())
    nodes=request(credentials,'nodes')
    if len(nodes)!=1 or nodes[0]['name']!=NODE:raise RuntimeError('节点身份不符，拒绝停止。')
    result=subprocess.run(['cmd.exe','/d','/c',str(LOCAL/'tools'/f'rabbitmq_server-{VERSION}'/'sbin/rabbitmqctl.bat'),'shutdown'],cwd=ROOT,env=runtime_env(credentials),capture_output=True,timeout=45)
    if result.returncode:raise RuntimeError('专用节点优雅关闭失败，未强制终止任何进程。')
    print('Dedicated RabbitMQ stopped; data retained.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',nargs='?',default='prepare',choices=['prepare','stop','export-compose']);args=parser.parse_args()
    stop() if args.action=='stop' else prepare(export_only=args.action=='export-compose')
