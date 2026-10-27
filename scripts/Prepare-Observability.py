"""本机独立监控生命周期：官方固定包摘要校验、精确进程归属、禁止接管占用端口。"""
import argparse, ctypes, getpass, hashlib, importlib.util, json, os, secrets, shutil, socket, subprocess, sys, time, urllib.request, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];LOCAL=ROOT/'.local';HOME=LOCAL/'observability';TOOLS=LOCAL/'tools'
spec=importlib.util.spec_from_file_location('rabbit_prepare',ROOT/'scripts/Prepare-RabbitMQ.py');rabbit=importlib.util.module_from_spec(spec);spec.loader.exec_module(rabbit);dev=rabbit.dev
RELEASES={
 'prometheus':('3.15.1','https://github.com/prometheus/prometheus/releases/download/v3.15.1/prometheus-3.15.1.windows-amd64.zip','ba25b5e590520cb9fb028edef99bdc8523244d2d489b8c629e0d453d94fb030b','prometheus-3.15.1.windows-amd64'),
 'grafana':('13.2.4','https://dl.grafana.com/oss/release/grafana-13.2.4.windows-amd64.zip','0eaddf3dbafa636e9da92e15fe29062958061dc30a73aa1761a7a36d7d019823','grafana-13.2.4')}
PORTS={'prometheus':19090,'grafana':13000,'rabbit-exporter':15698}


def occupied(port):
    """只探测回环监听；监听存在本身不是进程所有权证明。"""
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=.3):return True
    except OSError:return False


def identity(pid):
    """读取内核创建时间与可执行文件，PID重用或无权查询一律不认领。"""
    from ctypes import wintypes
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.GetProcessTimes.argtypes=[wintypes.HANDLE,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p]
    kernel.QueryFullProcessImageNameW.argtypes=[wintypes.HANDLE,wintypes.DWORD,wintypes.LPWSTR,ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=kernel.OpenProcess(0x1000,False,pid)
    if not handle:return None
    try:
        creation=wintypes.FILETIME();exit_time=wintypes.FILETIME();kt=wintypes.FILETIME();ut=wintypes.FILETIME()
        if not kernel.GetProcessTimes(handle,ctypes.byref(creation),ctypes.byref(exit_time),ctypes.byref(kt),ctypes.byref(ut)):return None
        buffer=ctypes.create_unicode_buffer(32768);size=wintypes.DWORD(len(buffer))
        if not kernel.QueryFullProcessImageNameW(handle,0,buffer,ctypes.byref(size)):return None
        return {'pid':pid,'creation':(creation.dwHighDateTime<<32)|creation.dwLowDateTime,'exe':str(Path(buffer.value).resolve())}
    finally:kernel.CloseHandle(handle)


def owned(name):
    """受保护记录与当前内核身份完全一致才认定为本脚本进程。"""
    path=HOME/(name+'-process.json')
    if not path.exists():return None
    record=json.loads(path.read_text());return record if identity(record['pid'])==record else None


def listener_owned(name):
    """监听地址必须全部为127.0.0.1，且所属PID与精确进程记录一致。"""
    record=owned(name)
    if not record:return False
    query=f"$r=@(Get-NetTCPConnection -State Listen -LocalPort {PORTS[name]} -ErrorAction SilentlyContinue); $r | Select-Object LocalAddress,OwningProcess | ConvertTo-Json -Compress"
    result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',query],capture_output=True,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode or not result.stdout.strip():return False
    rows=json.loads(result.stdout);rows=rows if isinstance(rows,list) else [rows]
    return all(row['LocalAddress']=='127.0.0.1' and row['OwningProcess']==record['pid'] for row in rows)


def install(name):
    """每次准备校验缓存归档摘要；仅解压经过固定官方SHA256验证的包。"""
    version,url,digest,folder=RELEASES[name];TOOLS.mkdir(parents=True,exist_ok=True)
    archive=TOOLS/url.rsplit('/',1)[-1]
    if not archive.exists():
        partial=archive.with_suffix(archive.suffix+'.download')
        with urllib.request.urlopen(url,timeout=60) as response,partial.open('wb') as output:shutil.copyfileobj(response,output)
        with partial.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
        if actual!=digest:raise RuntimeError(name+' downloaded archive digest mismatch; refused extraction')
        partial.replace(archive)
    with archive.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
    if actual!=digest:raise RuntimeError(name+' cached archive digest mismatch; refused extraction')
    target=TOOLS/folder
    marker=target/'.tradeflow-sha256'
    if not marker.exists() or marker.read_text().strip()!=digest:
        with zipfile.ZipFile(archive) as package:
            for item in package.infolist():
                if not (TOOLS/item.filename).resolve().is_relative_to(TOOLS.resolve()):raise RuntimeError('Unsafe archive path')
            package.extractall(TOOLS)
        marker.write_text(digest,encoding='ascii')
    executable=target/('prometheus.exe' if name=='prometheus' else 'bin/grafana.exe')
    if not executable.is_file():raise RuntimeError(name+' archive structure differs from pinned layout')
    return target


def prepare():
    """增量保留原凭据，仅导入专用只读监控账户，不声明/更改业务拓扑。"""
    credentials,env=rabbit.settings();HOME.mkdir(parents=True,exist_ok=True)
    subprocess.run(['icacls',str(HOME),'/inheritance:r','/grant:r',getpass.getuser()+':(OI)(CI)F'],check=True,capture_output=True)
    monitor=env['TRADEFLOW_RABBITMQ_MONITOR_USER']
    if rabbit.occupied(rabbit.MANAGEMENT):
        nodes=rabbit.request(credentials,'nodes')
        if len(nodes)!=1 or nodes[0]['name']!=rabbit.NODE:raise RuntimeError('RabbitMQ node identity mismatch')
        defs={'users':[{'name':monitor,'password_hash':rabbit.password_hash(env['TRADEFLOW_RABBITMQ_MONITOR_PASSWORD']),'hashing_algorithm':'rabbit_password_hashing_sha256','tags':['management']}], 'permissions':[{'user':monitor,'vhost':'/tradeflow','configure':'^$','write':'^$','read':'^$'}]}
        rabbit.request(credentials,'definitions','POST',defs)
    else:print('RabbitMQ absent; monitor grant deferred to project broker preparation; collector will report failure until available.')
    dev.protected_write(HOME/'monitoring-token',env['TRADEFLOW_MONITORING_TOKEN'])
    dev.protected_write(HOME/'rabbit-exporter.json',json.dumps({'user':monitor,'password':env['TRADEFLOW_RABBITMQ_MONITOR_PASSWORD'],'token':env['TRADEFLOW_MONITORING_TOKEN']}))
    admin=HOME/'grafana-admin.json'
    if not admin.exists():dev.protected_write(admin,json.dumps({'user':'tf_observer_admin','password':secrets.token_urlsafe(36)}))
    for name in RELEASES:install(name)
    source=(ROOT/'deploy/prometheus/prometheus.yml').read_text(encoding='utf-8').replace('__MONITOR_TOKEN_FILE__',(HOME/'monitoring-token').as_posix()).replace('__RULES_FILE__',(ROOT/'deploy/prometheus/alerts.yml').as_posix())
    dev.protected_write(HOME/'prometheus.yml',source)
    provision=HOME/'grafana-provisioning';shutil.copytree(ROOT/'deploy/grafana/provisioning',provision,dirs_exist_ok=True)
    (provision/'dashboards/tradeflow.yml').write_text((provision/'dashboards/tradeflow.yml').read_text().replace('__DASHBOARD_PATH__',(ROOT/'deploy/grafana/dashboards').as_posix()))
    subprocess.run([str(TOOLS/RELEASES['prometheus'][3]/'promtool.exe'),'check','config',str(HOME/'prometheus.yml')],check=True)
    subprocess.run([str(TOOLS/RELEASES['prometheus'][3]/'promtool.exe'),'check','rules',str(ROOT/'deploy/prometheus/alerts.yml')],check=True)
    print('Official fixed packages verified; protected credentials retained; configuration validated.')


def start():
    """只启动无占用的专用回环端口；记录内核身份后才允许后续停止。"""
    if not (HOME/'prometheus.yml').exists():raise RuntimeError('Run prepare first')
    for directory in ('grafana-data','grafana-logs','grafana-plugins','prometheus-data'):(HOME/directory).mkdir(parents=True,exist_ok=True)
    for name,port in PORTS.items():
        if occupied(port) and not listener_owned(name):raise RuntimeError(f'Port {port} occupied by unowned process; refused')
    # 子进程只继承Windows运行必需环境；不继承数据库/业务/管理员凭据。
    allowed={'SYSTEMROOT','WINDIR','PATH','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA','PROGRAMDATA','COMSPEC','SYSTEMDRIVE','HOMEDRIVE','HOMEPATH','NUMBER_OF_PROCESSORS','PROCESSOR_ARCHITECTURE','PATHEXT'}
    clean={k:v for k,v in os.environ.items() if k.upper() in allowed}
    for name,port in PORTS.items():
        if owned(name):
            if not listener_owned(name):raise RuntimeError(name+' owned process alive without expected listener; inspect logs or stop it first')
            continue
        env=clean.copy()
        if name=='prometheus':
            cmd=[str(TOOLS/RELEASES[name][3]/'prometheus.exe'),'--config.file='+str(HOME/'prometheus.yml'),'--storage.tsdb.path='+str(HOME/'prometheus-data'),'--web.listen-address=127.0.0.1:19090','--storage.tsdb.retention.time=7d'];cwd=ROOT
        elif name=='grafana':
            target=TOOLS/RELEASES[name][3];admin=json.loads((HOME/'grafana-admin.json').read_text());env.update(GF_SECURITY_ADMIN_USER=admin['user'],GF_SECURITY_ADMIN_PASSWORD=admin['password'],GF_SERVER_HTTP_ADDR='127.0.0.1',GF_SERVER_HTTP_PORT='13000',GF_PATHS_DATA=str(HOME/'grafana-data'),GF_PATHS_LOGS=str(HOME/'grafana-logs'),GF_PATHS_PLUGINS=str(HOME/'grafana-plugins'),GF_PATHS_PROVISIONING=str(HOME/'grafana-provisioning'),GF_ANALYTICS_REPORTING_ENABLED='false',GF_ANALYTICS_CHECK_FOR_UPDATES='false',GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES='false',GF_AUTH_ANONYMOUS_ENABLED='false',GF_USERS_ALLOW_SIGN_UP='false',GF_PLUGINS_PREINSTALL_DISABLED='true',GF_NEWS_NEWS_FEED_ENABLED='false',GF_SECURITY_DISABLE_GRAVATAR='true',GF_UNIFIED_ALERTING_ENABLED='false');cmd=[str(target/'bin/grafana.exe'),'server','--homepath='+str(target)];cwd=target
        else:cmd=[sys.executable,str(ROOT/'scripts/Rabbit-Exporter.py'),str(HOME/'rabbit-exporter.json')];cwd=ROOT
        with (HOME/(name+'.log')).open('ab') as log:process=subprocess.Popen(cmd,cwd=cwd,env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
        record=identity(process.pid)
        if not record:raise RuntimeError('Cannot establish process identity')
        dev.protected_write(HOME/(name+'-process.json'),json.dumps(record))
        deadline=time.monotonic()+120
        while not occupied(port):
            if process.poll() is not None or time.monotonic()>deadline:raise RuntimeError(name+' startup failed; inspect protected local logs')
            time.sleep(.3)
        if not listener_owned(name):raise RuntimeError('Listener identity/address mismatch; refused ownership')
        print(name+' listening on 127.0.0.1:'+str(port))


def stop():
    """仅按保存的PID、创建时间和映像路径停止，禁止按进程名称批量杀进程。"""
    for name in reversed(PORTS):
        record=owned(name)
        if record:
            # 再次核验后用同一内核句柄终止，避免PID复用窗口。
            from ctypes import wintypes
            kernel=ctypes.WinDLL('kernel32',use_last_error=True);kernel.OpenProcess.restype=wintypes.HANDLE
            kernel.TerminateProcess.argtypes=[wintypes.HANDLE,wintypes.UINT]
            kernel.CloseHandle.argtypes=[wintypes.HANDLE]
            handle=kernel.OpenProcess(0x1001,False,record['pid'])
            try:
                if not handle or identity(record['pid'])!=record:raise RuntimeError(name+' process identity changed; refused stop')
                if not kernel.TerminateProcess(handle,0):raise RuntimeError(name+' stop failed; no other process touched')
            finally:
                if handle:kernel.CloseHandle(handle)
            deadline=time.monotonic()+5
            while identity(record['pid'])==record and time.monotonic()<deadline:time.sleep(.1)
            if identity(record['pid'])==record:raise RuntimeError(name+' process termination not confirmed')
            print(name+' owned process stopped; data retained')
        elif occupied(PORTS[name]):print(name+' unowned listener left unchanged')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['prepare','start','stop','status']);args=parser.parse_args()
    if args.action=='status':
        for name,port in PORTS.items():print(name+': '+('owned' if listener_owned(name) else 'unowned' if occupied(port) else 'process alive without listener' if owned(name) else 'stopped'))
    else:globals()[args.action]()

