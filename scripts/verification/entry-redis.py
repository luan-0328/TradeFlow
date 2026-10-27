"""在独占临时Redis上验证生产Lua原子双桶；不连接已有Redis、不读取原dump。"""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import textwrap
import time
import sys

ROOT = Path(__file__).resolve().parents[2]


def main():
    binary = Path(os.environ.get('TRADEFLOW_REDIS_BIN', 'D:/Redis/Redis-7.0.15-Windows-x64-msys2')).resolve()
    suffix = '.exe' if os.name == 'nt' else ''
    local = ROOT / '.local' / 'verification'
    local.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='entry-redis-', dir=local)).resolve()
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    quiet = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    command = [str(binary / ('redis-server' + suffix)), '--bind', '127.0.0.1', '--port', str(port), '--dir', work.as_posix(),
               '--save', '', '--appendonly', 'no', '--daemonize', 'no', '--logfile', (work / 'redis.log').as_posix()]
    process = subprocess.Popen(command, cwd=work, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **quiet)
    client = [str(binary / ('redis-cli' + suffix)), '-h', '127.0.0.1', '-p', str(port), '--raw']

    def redis(*arguments):
        response = subprocess.run(client + list(arguments), capture_output=True, text=True, encoding='utf-8', timeout=5, **quiet)
        if response.returncode or response.stdout.startswith(('ERR ', 'WRONGTYPE', 'CROSSSLOT')):
            raise RuntimeError('独占Redis命令失败: ' + response.stderr.strip() + response.stdout.strip())
        return response.stdout.strip()

    result = {'kind': 'real-redis-lua', 'checks': []}
    try:
        for _ in range(100):
            if process.poll() is not None:
                raise RuntimeError('独占Redis提前退出，请查看保留日志')
            try:
                if redis('PING') == 'PONG':
                    break
            except RuntimeError:
                pass
            time.sleep(.1)
        else:
            raise RuntimeError('独占Redis就绪超时')
        # 启动后验证服务器目录归属，防止随机端口被其他进程抢先占用时误操作。
        assert Path(redis('CONFIG', 'GET', 'dir').splitlines()[1]).resolve() == work
        source = (ROOT / 'tradeflow-gateway/src/main/java/com/tradeflow/gateway/SharedEntryLimiter.java').read_text(encoding='utf-8')
        script = textwrap.dedent(re.search(r'static final String SCRIPT="""\n(.*?)""";', source, re.S).group(1))

        def acquire(principal, window='60', category='concurrent'):
            return redis('EVAL', script, '2', '{' + category + '}:total', '{' + category + '}:' + principal, '3', '2', window).splitlines()

        with ThreadPoolExecutor(max_workers=12) as pool:
            decisions = list(pool.map(lambda _: acquire('same'), range(32)))
        assert sum(value[0] == '1' for value in decisions) == 2
        assert redis('GET', '{concurrent}:total') == '2'
        assert redis('GET', '{concurrent}:same') == '2'
        result['checks'].append('32并发同主体恰好2准入，拒绝不增计数且返回正Retry-After')
        assert all(int(value[1]) > 0 for value in decisions if value[0] == '0')
        assert acquire('other')[0] == '1'
        assert acquire('third')[0] == '0'
        assert redis('EXISTS', '{concurrent}:third') == '0'
        assert redis('GET', '{concurrent}:total') == '3'
        result['checks'].append('主体切换不能绕过分类总额3；拒绝总额时不消耗另一个主体桶')
        assert acquire('same', '1', 'expiry')[0] == '1'
        assert acquire('same', '1', 'expiry')[0] == '1'
        assert acquire('same', '1', 'expiry')[0] == '0'
        deadline = time.monotonic() + 5
        while redis('EXISTS', '{expiry}:total') != '0':
            if time.monotonic() > deadline:
                raise RuntimeError('Redis窗口未按TTL过期')
            time.sleep(.1)
        assert acquire('same', '1', 'expiry')[0] == '1'
        result['checks'].append('窗口到期自动恢复准入，拒绝未延长TTL')
        if '--catalog-jvm' in sys.argv:
            environment = os.environ.copy()
            environment['TRADEFLOW_CACHE_REDIS_PORT'] = str(port)
            run = subprocess.run([str(ROOT / 'mvnw.cmd'), '-B', '-ntp', '-pl', 'tradeflow-product', '-am',
                                  '-Dtest=CatalogCacheTest,CatalogCacheRedisTest,ProductMapperXmlTest',
                                  '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], cwd=ROOT, env=environment,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **quiet)
            (work / 'catalog-maven.log').write_bytes(run.stdout)
            if run.returncode:
                print(run.stdout.decode(errors='replace'))
            run.check_returncode()
            result['checks'].append('商品缓存真实Redis/JVM：正负缓存、TTL、双实例热点互斥、代际/租约围栏和提交后失效')
        result['status'] = 'PASS'
    finally:
        if process.poll() is None:
            # 已确认归属才发送shutdown；否则只操作本脚本Popen句柄。
            try:
                if Path(redis('CONFIG', 'GET', 'dir').splitlines()[1]).resolve() == work:
                    redis('SHUTDOWN', 'NOSAVE')
                process.wait(timeout=10)
            except (RuntimeError, subprocess.TimeoutExpired, IndexError):
                process.kill()
                process.wait(timeout=5)
        result['owned_process_stopped'] = process.poll() is not None
        (work / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'result': str(work / 'result.json'), **result}, ensure_ascii=False))


if __name__ == '__main__':
    main()
