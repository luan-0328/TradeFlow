"""本机项目 Redis 生命周期；仅认领精确身份的回环实例，凭据不进入命令行或输出。"""
import argparse
import base64
import ctypes
from ctypes import wintypes
import hashlib
import json
import msvcrt
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RUNTIME = ROOT / '.local/redis'
HIDDEN = subprocess.CREATE_NO_WINDOW


def powershell(source):
    source = '[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);' + source
    # 调用 Windows PowerShell 5 时重建其自身模块路径，避免继承 PowerShell 7 模块导致归属查询失效。
    environment = {k: v for k, v in os.environ.items() if k.upper() != 'PSMODULEPATH'}
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-EncodedCommand',
                             base64.b64encode(source.encode('utf-16-le')).decode()], capture_output=True,
                            text=True, encoding='utf-8', errors='replace', creationflags=HIDDEN, env=environment)
    if result.returncode:
        raise RuntimeError('Windows 归属或 ACL 检查失败；拒绝继续，未输出受保护内容。')
    return result.stdout.strip()


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def redis_path(path):
    """现有 Redis 为 MSYS2 构建，参数和配置内文件路径使用其驱动器挂载写法。"""
    value = Path(path).resolve().as_posix()
    return '/cygdrive/' + value[0].lower() + value[2:] if len(value) > 2 and value[1] == ':' else value


def protect(path, directory=False):
    """一次替换 DACL 为当前 Windows 身份独占，写入秘密前完成，不沿用宽泛显式授权。"""
    security = 'DirectorySecurity' if directory else 'FileSecurity'
    io_type = 'Directory' if directory else 'File'
    inheritance = ',[System.Security.AccessControl.InheritanceFlags]"ContainerInherit,ObjectInherit",[System.Security.AccessControl.PropagationFlags]::None' if directory else ''
    powershell(f'''$ErrorActionPreference='Stop';$sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
$acl=New-Object System.Security.AccessControl.{security};$acl.SetAccessRuleProtection($true,$false)
$rule=[System.Security.AccessControl.FileSystemAccessRule]::new($sid,[System.Security.AccessControl.FileSystemRights]::FullControl{inheritance},[System.Security.AccessControl.AccessControlType]::Allow)
$acl.AddAccessRule($rule);[System.IO.{io_type}]::SetAccessControl({quote(path)},$acl)''')


def protected_write(path, content):
    """空文件先收紧权限再写秘密，原子替换；不将密码放进 PowerShell 参数。"""
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    temporary.touch(exist_ok=False)
    protect(temporary)
    temporary.write_text(content, encoding='utf-8')
    os.replace(temporary, path)


def identity(pid):
    """内核创建时间与映像路径共同防 PID 复用；查询失败不视为已归属。"""
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        return None
    try:
        created, exited, kt, ut = (wintypes.FILETIME() for _ in range(4))
        buffer = ctypes.create_unicode_buffer(32768)
        size = wintypes.DWORD(len(buffer))
        if not kernel.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited), ctypes.byref(kt), ctypes.byref(ut)):
            raise RuntimeError('无法核验 Redis 创建时间。')
        if not kernel.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(size)):
            raise RuntimeError('无法核验 Redis 映像。')
        return {'pid': pid, 'creation': (created.dwHighDateTime << 32) | created.dwLowDateTime,
                'exe': str(Path(buffer.value).resolve())}
    finally:
        kernel.CloseHandle(handle)


def command_args(pid):
    """只读取目标 PID 命令行并按 Windows 原生规则解析；配置参数必须精确指向本归属目录。"""
    raw = powershell(f'$p=Get-CimInstance Win32_Process -Filter "ProcessId={int(pid)}";if(!$p){{exit 2}};$p.CommandLine | ConvertTo-Json -Compress')
    command = json.loads(raw)
    shell = ctypes.WinDLL('shell32', use_last_error=True)
    shell.CommandLineToArgvW.argtypes = [wintypes.LPCWSTR, ctypes.POINTER(ctypes.c_int)]
    shell.CommandLineToArgvW.restype = ctypes.POINTER(wintypes.LPWSTR)
    count = ctypes.c_int()
    argv = shell.CommandLineToArgvW(command, ctypes.byref(count))
    if not argv:
        raise RuntimeError('无法解析 Redis 配置路径。')
    try:
        return [argv[i] for i in range(count.value)]
    finally:
        kernel = ctypes.WinDLL('kernel32');kernel.LocalFree.argtypes = [wintypes.HLOCAL];kernel.LocalFree(argv)


def listeners(port):
    raw = powershell(f'$ErrorActionPreference="Stop";if(!(Get-Command Get-NetTCPConnection -ErrorAction Stop)){{exit 2}};$r=@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq {port});$r | Select-Object LocalAddress,OwningProcess | ConvertTo-Json -Compress;exit 0')
    if not raw:
        return []
    result = json.loads(raw)
    return result if isinstance(result, list) else [result]


def resp(stream, *arguments):
    """直接通过本机 RESP 套接字鉴权；密码只在受保护文件和内存中，不传 CLI、不回显响应正文。"""
    encoded = [str(v).encode('utf-8') for v in arguments]
    stream.write(b'*' + str(len(encoded)).encode() + b'\r\n' + b''.join(b'$' + str(len(v)).encode() + b'\r\n' + v + b'\r\n' for v in encoded))
    stream.flush()
    line = stream.readline()
    if line.startswith(b'-'):
        raise RuntimeError('本机 Redis 拒绝受控命令；未输出响应正文。')
    return line


class RedisRuntime:
    def __init__(self, args):
        self.home = Path(args.runtime).resolve()
        self.env_file = (self.home / 'dev-env.json') if self.home != DEFAULT_RUNTIME.resolve() else ROOT / '.local/dev-env.json'
        binary = Path(args.redis_bin).resolve()
        self.exe = (binary / 'redis-server.exe') if binary.is_dir() else binary
        self.config = self.home / 'redis.conf'
        self.marker_path = self.home / 'owner.json'
        self.process_path = self.home / 'process.json'
        self.requested_port = args.port

    def marker(self):
        if not self.marker_path.is_file():
            raise RuntimeError('目录缺少项目归属标记；拒绝接管或读取已有数据。')
        saved = json.loads(self.marker_path.read_text(encoding='utf-8'))
        if saved.get('project') != str(ROOT.resolve()) or saved.get('runtime') != str(self.home) or saved.get('exe') != str(self.exe) or saved.get('config') != str(self.config) or not saved.get('owner'):
            raise RuntimeError('Redis 归属标记不匹配；拒绝继续。')
        if self.requested_port and saved.get('port') != self.requested_port:
            raise RuntimeError('端口与原归属标记不一致；拒绝修改原实例。')
        return saved

    def password(self):
        # 配置文件是本实例秘密权威；开发环境只接收相同密码，不因重复 prepare 轮换。
        lines = self.config.read_text(encoding='utf-8').splitlines()
        found = [line.removeprefix('requirepass ') for line in lines if line.startswith('requirepass ')]
        if len(found) != 1 or len(found[0]) < 32:
            raise RuntimeError('Redis 认证配置不合法，拒绝复用。')
        return found[0]

    def validate_process(self, marker):
        saved = json.loads(self.process_path.read_text(encoding='utf-8'))
        if saved.get('owner') != marker['owner'] or saved.get('config') != str(self.config) or saved.get('config_sha256') != hashlib.sha256(self.config.read_bytes()).hexdigest():
            raise RuntimeError('Redis 进程归属/配置摘要不匹配。')
        if saved.get('stopped'):
            return None
        actual = identity(saved['identity']['pid'])
        if actual is None:
            return None
        if actual != saved['identity'] or actual['exe'] != str(self.exe):
            raise RuntimeError('Redis PID 已复用或映像不匹配，拒绝操作。')
        argv = command_args(actual['pid'])
        if len(argv) != 2 or Path(argv[0]).resolve() != self.exe or argv[1] != redis_path(self.config):
            raise RuntimeError('Redis 进程命令行配置路径不匹配。')
        return saved

    def owned_listener(self, marker, saved):
        values = listeners(marker['port'])
        return bool(values) and all(v['LocalAddress'] == '127.0.0.1' and v['OwningProcess'] == saved['identity']['pid'] for v in values)

    def ping(self, marker):
        with socket.create_connection(('127.0.0.1', marker['port']), timeout=2) as connection:
            with connection.makefile('rwb') as stream:
                if resp(stream, 'AUTH', self.password()) != b'+OK\r\n' or resp(stream, 'PING') != b'+PONG\r\n':
                    raise RuntimeError('专用 Redis 鉴权或 PING 未通过。')

    def export_env(self, marker):
        env = json.loads(self.env_file.read_text(encoding='utf-8-sig')) if self.env_file.exists() else {}
        if not isinstance(env, dict):
            raise RuntimeError('开发环境文件不是对象；拒绝覆盖。')
        password = self.password()
        expected = {'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': str(marker['port']), 'REDIS_PASSWORD': password}
        # 已有 Redis 凭据不静默替换；操作者必须先核实其用途，避免破坏其他开发组件。
        if any(key in env and str(env[key]) != value for key, value in expected.items()):
            raise RuntimeError('开发环境已有不同 Redis 配置；拒绝覆盖原凭据。')
        env.update(expected)
        protected_write(self.env_file, json.dumps(env, ensure_ascii=False, indent=2))

    def prepare(self):
        if not self.exe.is_file():
            raise RuntimeError('未找到本机 redis-server.exe；请设置 TRADEFLOW_REDIS_BIN。')
        fresh = not self.home.exists()
        if fresh:
            port = self.requested_port
            if not port:
                with socket.socket() as sock:
                    sock.bind(('127.0.0.1', 0));port = sock.getsockname()[1]
            if listeners(port):
                raise RuntimeError('目标端口已有监听；拒绝接管。')
            self.home.mkdir(parents=True, exist_ok=False);protect(self.home, True)
            marker = {'project': str(ROOT.resolve()), 'runtime': str(self.home), 'exe': str(self.exe),
                      'config': str(self.config), 'owner': uuid.uuid4().hex, 'port': port}
            protected_write(self.marker_path, json.dumps(marker, indent=2))
            data = self.home / 'data';data.mkdir()
            password = secrets.token_hex(32)
            protected_write(self.config, f'bind 127.0.0.1\nport {port}\nprotected-mode yes\nrequirepass {password}\ndir "{redis_path(data)}"\ndbfilename dump.rdb\nappendonly yes\nsave 60 1\ndaemonize no\nlogfile "{redis_path(self.home / "redis.log")}"\n')
        else:
            marker = self.marker();protect(self.home, True);protect(self.config)
        saved = self.validate_process(marker) if self.process_path.exists() else None
        if listeners(marker['port']):
            if not saved or not self.owned_listener(marker, saved):
                raise RuntimeError('目标端口非本项目精确归属进程；拒绝接管。')
            self.ping(marker);self.export_env(marker)
            print(f'Redis reused on 127.0.0.1:{marker["port"]}; credentials protected.')
            return
        if saved:
            raise RuntimeError('原归属 Redis 进程存在但无预期监听；先检查日志，不自动重启。')
        # 启动前检查环境冲突，避免启动后才发现不能交付既有运行配置。
        self.export_env(marker)
        allowed = {'SYSTEMROOT', 'WINDIR', 'PATH', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA', 'COMSPEC', 'SYSTEMDRIVE'}
        env = {k: v for k, v in os.environ.items() if k.upper() in allowed}
        with (self.home / 'process.log').open('ab') as log:
            process = subprocess.Popen([str(self.exe), redis_path(self.config)], cwd=self.exe.parent, env=env,
                                       stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                       creationflags=HIDDEN | subprocess.CREATE_NEW_PROCESS_GROUP)
        actual = identity(process.pid)
        if actual is None:
            raise RuntimeError('无法建立新 Redis 进程身份；请检查独立日志。')
        saved = {'owner': marker['owner'], 'config': str(self.config), 'config_sha256': hashlib.sha256(self.config.read_bytes()).hexdigest(), 'identity': actual}
        protected_write(self.process_path, json.dumps(saved, indent=2))
        for _ in range(80):
            if process.poll() is not None:
                raise RuntimeError('独立 Redis 启动失败；查看受保护 runtime 日志。')
            if self.owned_listener(marker, saved):
                self.validate_process(marker);self.ping(marker)
                print(f'Redis ready on 127.0.0.1:{marker["port"]}; exact process identity recorded; credentials protected.')
                return
            time.sleep(.25)
        raise RuntimeError('独立 Redis 就绪超时；保留记录与日志，不操作其他进程。')

    def stop(self):
        if not self.home.exists():
            print('No project Redis runtime; nothing stopped.');return
        marker = self.marker()
        if not self.process_path.exists():
            if listeners(marker['port']):
                raise RuntimeError('无归属进程记录而端口被占用；拒绝停止。')
            print('No recorded Redis process; data preserved.');return
        saved = self.validate_process(marker)
        if not saved:
            print('Recorded Redis is not running; no process stopped; data preserved.');return
        if not self.owned_listener(marker, saved):
            raise RuntimeError('无法确认回环监听归属；拒绝发送停止命令。')
        with socket.create_connection(('127.0.0.1', marker['port']), timeout=10) as connection:
            self.validate_process(marker)
            with connection.makefile('rwb') as stream:
                if resp(stream, 'AUTH', self.password()) != b'+OK\r\n':
                    raise RuntimeError('Redis 停止鉴权失败。')
                resp(stream, 'SHUTDOWN', 'SAVE')
        for _ in range(80):
            actual = identity(saved['identity']['pid'])
            if actual != saved['identity']:
                saved['stopped'] = True;protected_write(self.process_path, json.dumps(saved, indent=2))
                print('Project Redis stopped gracefully; data and credentials preserved.');return
            time.sleep(.25)
        raise RuntimeError('Redis 优雅停止尚未确认；不会强制终止其他进程。')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'stop'])
    parser.add_argument('--runtime', default=str(DEFAULT_RUNTIME), help='独立验证请指定全新目录；凭据文件自动隔离在该目录')
    parser.add_argument('--port', type=int, default=None, help='默认16379；独立runtime可指定0申请随机端口')
    parser.add_argument('--redis-bin', default=os.environ.get('TRADEFLOW_REDIS_BIN', 'D:/Redis/Redis-7.0.15-Windows-x64-msys2'))
    args = parser.parse_args()
    if args.port is None:
        args.port = 16379 if Path(args.runtime).resolve() == DEFAULT_RUNTIME.resolve() else 0
    if not 0 <= args.port <= 65535 or (Path(args.runtime).resolve() == DEFAULT_RUNTIME.resolve() and args.port != 16379):
        parser.error('项目 runtime 固定16379；独立测试端口须为0至65535。')
    runtime = RedisRuntime(args)
    # 归属目录未创建时以同级独占锁串行；锁文件无秘密，不覆写已有业务数据。
    runtime.home.parent.mkdir(parents=True, exist_ok=True)
    with runtime.home.with_name(runtime.home.name + '.lifecycle.lock').open('a+b') as lock:
        lock.seek(0);lock.write(b'0');lock.flush();lock.seek(0)
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            raise RuntimeError('另一个 Redis 生命周期操作进行中，请稍后重试。')
        try:
            getattr(runtime, args.action)()
        finally:
            lock.seek(0);msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as failure:
        # 不打印底层命令响应、配置或凭据；固定业务异常均不包含密码。
        print('Redis lifecycle refused: ' + (str(failure) if isinstance(failure, RuntimeError) else type(failure).__name__))
        raise SystemExit(1)
