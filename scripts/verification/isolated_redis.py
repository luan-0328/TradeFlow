"""验证专用Redis：新目录、随机回环端口、关闭持久化，只回收自己创建的进程。"""
import os
from pathlib import Path
import socket
import subprocess
import time


class IsolatedRedis:
    def __init__(self, work):
        self.directory = Path(work).resolve() / 'redis'
        self.directory.mkdir()
        binary = Path(os.environ.get('TRADEFLOW_REDIS_BIN', 'D:/Redis/Redis-7.0.15-Windows-x64-msys2')).resolve()
        suffix = '.exe' if os.name == 'nt' else ''
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            self.port = reservation.getsockname()[1]
        self.quiet = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
        self.client = [str(binary / ('redis-cli' + suffix)), '-h', '127.0.0.1', '-p', str(self.port), '--raw']
        self.process = subprocess.Popen([str(binary / ('redis-server' + suffix)), '--bind', '127.0.0.1', '--port', str(self.port),
            '--dir', self.directory.as_posix(), '--save', '', '--appendonly', 'no', '--daemonize', 'no',
            '--logfile', (self.directory / 'redis.log').as_posix()], cwd=self.directory,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **self.quiet)
        try:
            for _ in range(100):
                if self.process.poll() is not None:
                    raise RuntimeError('本次隔离Redis提前退出')
                try:
                    if self.call('PING') == 'PONG':
                        assert Path(self.call('CONFIG', 'GET', 'dir').splitlines()[1]).resolve() == self.directory
                        return
                except (RuntimeError, IndexError):
                    pass
                time.sleep(.1)
            raise RuntimeError('本次隔离Redis启动超时')
        except BaseException:
            self.close()
            raise

    def call(self, *arguments):
        result = subprocess.run(self.client + list(arguments), capture_output=True, text=True, timeout=5, **self.quiet)
        if result.returncode:
            raise RuntimeError('本次隔离Redis命令失败')
        return result.stdout.strip()

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        return self.process.poll() is not None
