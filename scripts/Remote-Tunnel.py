"""通过一条 SSH 连接转发远程中间件，并反向转发 Sentinel 客户端端口。

SSH 密码只经交互输入和父子进程管道传递，不写文件、不加入命令行。
服务端密钥沿用 OpenSSH known_hosts，拒绝未知主机；后台日志不记录凭据。
"""
import argparse
import ctypes
import getpass
import json
import os
from pathlib import Path
import select
import socket
import socketserver
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / '.local'
STATUS = RUNTIME / 'remote-tunnel.json'
FORWARDS = (18848, 19848, 18880, 18871)
REVERSES = (28719, 28720, 28721, 28722, 28723, 28724)


def process_started_ticks(handle=None):
    """读取 Windows 进程创建时间，用于防止停止时因 PID 复用误杀其他程序。"""
    from ctypes import wintypes
    created, exited, kernel, user = (wintypes.FILETIME() for _ in range(4))
    kernel32 = ctypes.windll.kernel32
    kernel32.GetCurrentProcess.restype = wintypes.HANDLE
    kernel32.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    kernel32.GetProcessTimes.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
    process = handle or kernel32.GetCurrentProcess()
    if not kernel32.GetProcessTimes(process, ctypes.byref(created), ctypes.byref(exited), ctypes.byref(kernel), ctypes.byref(user)):
        raise OSError('无法读取隧道进程创建时间')
    return (created.dwHighDateTime << 32) + created.dwLowDateTime + 504911232000000000


def state(phase, host, **extra):
    """原子保存非敏感连接状态，供启动和停止命令核对归属。"""
    value = dict(pid=os.getpid(), startedTicks=process_started_ticks(), host=host,
                 phase=phase, localPorts=list(FORWARDS), reversePorts=list(REVERSES), **extra)
    temporary = STATUS.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(STATUS)


def relay(left, right):
    """双向传输 TCP 字节；任一端结束即关闭本连接，不重试业务请求。"""
    try:
        while True:
            readable, _, _ = select.select([left, right], [], [], 30)
            for source in readable:
                block = source.recv(65536)
                if not block:
                    return
                (right if source is left else left).sendall(block)
    except (OSError, EOFError):
        pass
    finally:
        left.close()
        right.close()


class ForwardServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def worker():
    """建立并维持一个复用 SSH 传输，管理组件上传、正向与反向隧道。"""
    import paramiko
    secret = json.loads(sys.stdin.readline())
    host = secret['host']
    client = paramiko.SSHClient()
    client.load_system_host_keys()
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    servers = []
    try:
        state('connecting', host)
        client.connect(hostname=host, username='root', password=secret.pop('password'),
                       allow_agent=False, look_for_keys=False, timeout=15, auth_timeout=20)
        secret.clear()
        transport = client.get_transport()
        transport.set_keepalive(30)
        state('preparing', host)
        # 只上传本项目官方控制台 JAR，不上传或读取其他项目目录。
        with client.open_sftp() as sftp:
            artifact = ROOT / '.local/artifacts/sentinel-dashboard-1.8.9.jar'
            target = '/opt/tradeflow-infra/artifacts/' + artifact.name
            try:
                upload_needed = sftp.stat(target).st_size != artifact.stat().st_size
            except OSError:
                upload_needed = True
            if upload_needed:
                sftp.put(str(artifact), target)
            with sftp.open('/opt/tradeflow-infra/.env') as stream:
                remote = dict(line.split('=', 1) for line in stream.read().decode().splitlines() if '=' in line)
        # 只保存组件账号供本机开发；不保存 SSH 密码、Nacos 签名密钥。
        values = {'NACOS_SERVER_ADDR': '127.0.0.1:18848',
                  'NACOS_USERNAME': remote['NACOS_ADMIN_USERNAME'], 'NACOS_PASSWORD': remote['NACOS_ADMIN_PASSWORD'],
                  'SENTINEL_USERNAME': remote['SENTINEL_USERNAME'], 'SENTINEL_PASSWORD': remote['SENTINEL_PASSWORD'],
                  'SENTINEL_DASHBOARD': '127.0.0.1:18871',
                  'TRADEFLOW_NACOS_CONFIG_ENABLED': 'true', 'TRADEFLOW_SENTINEL_ENABLED': 'true',
                  'NACOS_CONFIG_IMPORT': 'nacos:${spring.application.name}.yaml?group=TRADEFLOW'}
        credential_path = RUNTIME / 'remote-env.json'
        credential_path.write_text(json.dumps(values, ensure_ascii=False, indent=2), encoding='utf-8')
        subprocess.run(['icacls', str(credential_path), '/inheritance:r', '/grant:r', getpass.getuser() + ':F'],
                       capture_output=True, check=True)
        remote.clear()
        values.clear()
        for port in FORWARDS:
            class Handler(socketserver.BaseRequestHandler):
                def handle(self):
                    try:
                        channel = transport.open_channel('direct-tcpip', ('127.0.0.1', self.server.server_address[1]), self.client_address)
                        relay(self.request, channel)
                    except (OSError, paramiko.SSHException):
                        self.request.close()
            server = ForwardServer(('127.0.0.1', port), Handler)
            servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
        def reverse_handler(channel, origin, server_address):
            # forwarded-tcpip 的目标端口来自回调 server_address；getpeername 是 SSH 服务器的 22 端口。
            def connect_local():
                try:
                    destination = socket.create_connection(('127.0.0.1', server_address[1]), timeout=3)
                    destination.settimeout(None)
                    relay(channel, destination)
                except OSError:
                    channel.close()
            threading.Thread(target=connect_local, daemon=True).start()
        for port in REVERSES:
            transport.request_port_forward('127.0.0.1', port, handler=reverse_handler)
        state('ready', host)
        while transport.is_active():
            time.sleep(1)
        state('disconnected', host)
    except Exception as error:
        state('error', host, errorType=type(error).__name__, message='连接或端口转发失败，请检查 SSH/端口/known_hosts；密码未保存。')
        print(type(error).__name__, str(error), flush=True)
        raise
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
        client.close()


def start(host):
    """交互读取密码后交给后台进程；就绪后退出前台，隧道继续保持。"""
    RUNTIME.mkdir(exist_ok=True)
    if STATUS.exists():
        previous = json.loads(STATUS.read_text(encoding='utf-8'))
        if previous['phase'] == 'ready':
            from ctypes import wintypes
            ctypes.windll.kernel32.OpenProcess.restype = wintypes.HANDLE
            handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, previous['pid'])
            if handle:
                try:
                    if process_started_ticks(handle) == previous['startedTicks']:
                        raise SystemExit('本项目隧道已在运行，不重复连接。')
                finally:
                    ctypes.windll.kernel32.CloseHandle(handle)
    password = getpass.getpass('SSH root password (not saved): ')
    with (RUNTIME / 'remote-tunnel.log').open('ab') as log:
        process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '_worker'],
                                   stdin=subprocess.PIPE, stdout=log, stderr=log,
                                   creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)
    process.stdin.write((json.dumps({'host': host, 'password': password}) + '\n').encode())
    process.stdin.close()
    password = None
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if STATUS.exists():
            current = json.loads(STATUS.read_text(encoding='utf-8'))
            if current['pid'] == process.pid:
                if current['phase'] == 'ready':
                    print('SSH tunnel ready. Nacos: http://localhost:18880/  Sentinel: http://localhost:18871/')
                    return
                if current['phase'] == 'error':
                    raise SystemExit(current['message'])
        if process.poll() is not None:
            raise SystemExit('隧道进程退出，请查看 .local/remote-tunnel.log。')
        time.sleep(0.25)
    raise SystemExit('后台连接仍未就绪，查看 .local/remote-tunnel.json；不要重复启动。')


def stop():
    """仅停止记录中且创建时间匹配的隧道进程，不触碰其他 SSH 会话。"""
    from ctypes import wintypes
    current = json.loads(STATUS.read_text(encoding='utf-8'))
    ctypes.windll.kernel32.OpenProcess.restype = wintypes.HANDLE
    handle = ctypes.windll.kernel32.OpenProcess(0x1001, False, current['pid'])
    if not handle:
        print('隧道进程已退出。')
        return
    try:
        if process_started_ticks(handle) != current['startedTicks']:
            raise SystemExit('PID 已被复用，拒绝停止。')
        if not ctypes.windll.kernel32.TerminateProcess(handle, 0):
            raise OSError('停止失败')
    finally:
        ctypes.windll.kernel32.CloseHandle(handle)
    current['phase'] = 'stopped'
    STATUS.write_text(json.dumps(current, ensure_ascii=False, indent=2), encoding='utf-8')
    print('本项目 SSH 隧道已停止，服务器容器保持运行。')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['start', 'stop', '_worker'])
    parser.add_argument('--host', default=os.environ.get('TRADEFLOW_SSH_HOST'))
    args = parser.parse_args()
    if args.mode == 'start' and not args.host:
        parser.error('请通过 --host 或 TRADEFLOW_SSH_HOST 指定服务器，不在源码保存个人服务器地址')
    {'start': lambda: start(args.host), 'stop': stop, '_worker': worker}[args.mode]()
