"""运行校验过的官方Nacos隔离fixture；仅关闭本脚本创建的Java进程。"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import re
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OFFICIAL_URL = 'https://github.com/alibaba/nacos/releases/download/3.0.3/nacos-server-3.0.3.zip'
OFFICIAL_SHA256 = '801b694fed684daef926915a1632b7df056debbf48449a3ce140a3f4243d4978'
RELEASES = {
    '3.0.3': OFFICIAL_SHA256,
    # 官方GitHub Release资产API给出的SHA256；RC仅用于本地隔离验证，不是部署基线。
    '3.3.0-RC': 'bc0a9e942b32efbc6ecebeaaadee4f823b1f16b008f9ad67e98f06149a7f5879',
}


def protect(path, content):
    path.write_text(content, encoding='utf-8')
    if os.name == 'nt':
        import getpass
        subprocess.run(['icacls', str(path), '/inheritance:r', '/grant:r', getpass.getuser() + ':F'],
                       capture_output=True, check=True)
    else:
        path.chmod(0o600)


def ports():
    """同时预留HTTP、SDK/集群gRPC、Raft、console候选，启动后仍核对真实进程监听归属。"""
    for _ in range(100):
        first = secrets.randbelow(20000) + 25000
        candidates = [first, first + 1000, first + 1001, first - 1000, first + 2000]
        held = []
        try:
            for port in candidates:
                sock = socket.socket()
                held.append(sock)
                sock.bind(('127.0.0.1', port))
            return first, first + 2000
        except OSError:
            pass
        finally:
            for sock in held:
                sock.close()
    raise RuntimeError('没有可用的隔离端口组')


def request(port, path, method='GET', params=None):
    data = urllib.parse.urlencode(params).encode() if params is not None else None
    req = urllib.request.Request(f'http://127.0.0.1:{port}' + path, data=data, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def listeners(pid):
    """读取Windows真实监听地址；拒绝任何非回环监听，证据不含进程命令行和秘密。"""
    if os.name != 'nt':
        raise RuntimeError('本次fixture监听归属检查只支持Windows，不降低检查标准')
    command = f'@(Get-NetTCPConnection -State Listen -OwningProcess {pid} -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess) | ConvertTo-Json -Compress'
    result = subprocess.run(['powershell', '-NoProfile', '-Command', command], capture_output=True, timeout=15, check=True)
    rows = json.loads(result.stdout.decode('utf-8-sig') or '[]')
    if isinstance(rows, dict):
        rows = [rows]
    if any(row['LocalAddress'] not in ('127.0.0.1', '::1') for row in rows):
        raise RuntimeError('Nacos存在非回环监听，拒绝继续：' + json.dumps(rows))
    return rows


def process_identity(pid):
    """记录本次Popen创建的PID与操作系统创建时间，供保留运行期间确认归属。"""
    result = subprocess.run(['powershell', '-NoProfile', '-Command',
        f'(Get-Process -Id {pid}).StartTime.ToUniversalTime().ToString("o")'],
        capture_output=True, timeout=15, check=True)
    return {'pid': pid, 'processCreatedAtUtc': result.stdout.decode('utf-8-sig').strip()}


def probe_sdk(root, home, java, runtime):
    """直接使用官方原版发行包SDK验证真实gRPC链路；失败只输出阶段，不回显凭据或SDK日志。"""
    libs = root / 'sdk-libs'
    libs.mkdir()
    with zipfile.ZipFile(home / 'target' / 'nacos-server.jar') as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libs / Path(name).name).write_bytes(archive.read(name))
    source = root / 'FixtureSdkProbe.java'
    source.write_text(r'''
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Properties;

class FixtureSdkProbe {
    // 只访问调用方新建的独立namespace；不读取其它registry，也不输出凭据。
    public static void main(String[] args) throws Exception {
        var credentials = new ObjectMapper().readTree(Path.of(args[2]).toFile());
        Properties p = new Properties();
        p.setProperty("serverAddr", args[0]);
        p.setProperty("namespace", args[1]);
        p.setProperty("username", credentials.get("username").asText());
        p.setProperty("password", credentials.get("password").asText());
        p.setProperty("namingLoadCacheAtStart", "false");
        NamingService publisher = NacosFactory.createNamingService(p);
        NamingService consumer = NacosFactory.createNamingService(p);
        p.setProperty("namespace", args[1] + "-other");
        NamingService other = NacosFactory.createNamingService(p);
        String service = "fixture-real-sdk-" + java.util.UUID.randomUUID();
        boolean registered = false;
        try {
            Instance instance = new Instance();
            instance.setIp("127.0.0.1"); instance.setPort(12345); instance.setEphemeral(true);
            publisher.registerInstance(service, instance); registered = true;
            boolean discovered = false;
            for (int i = 0; i < 100; i++) {
                if (consumer.selectInstances(service, true).stream().anyMatch(x -> x.getPort() == 12345)) {
                    discovered = true; break;
                }
                Thread.sleep(200);
            }
            if (!discovered) throw new IllegalStateException("real SDK discovery timeout");
            if (!other.selectInstances(service, true).isEmpty()) throw new IllegalStateException("namespace isolation failed");
            publisher.deregisterInstance(service, instance); registered = false;
            boolean removed = false;
            for (int i = 0; i < 100; i++) {
                if (consumer.selectInstances(service, true).isEmpty()) { removed = true; break; }
                Thread.sleep(200);
            }
            if (!removed) throw new IllegalStateException("real SDK deregistration timeout");
            System.out.println("FIXTURE_SDK_PROBE_PASSED");
        } finally {
            if (registered) publisher.deregisterInstance(service, "127.0.0.1", 12345);
            publisher.shutDown(); consumer.shutDown(); other.shutDown();
        }
    }
}
''', encoding='utf-8')
    with (root / 'sdk-probe.log').open('wb') as output:
        process = subprocess.Popen([java, '-Xms32m', '-Xmx192m', '-XX:ActiveProcessorCount=2',
            f'-Duser.home={root}', f'-DJM.LOG.PATH={root / "sdk-logs"}', '--class-path', str(libs / '*'),
            str(source), runtime['endpoint'], runtime['namespace'], runtime['credentialFilePath']],
            cwd=root, stdout=output, stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        identity = {'pid': process.pid}
        try:
            identity.update(process_identity(process.pid))
            identity['initialLoopbackListeners'] = listeners(process.pid)
            protect(root / 'sdk-process.json', json.dumps(identity, indent=2))
            process.wait(timeout=120)
            identity['loopbackListeners'] = listeners(process.pid)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=30)
            identity['ownedSdkStopped'] = process.poll() is not None
            identity['exitCode'] = process.returncode
            protect(root / 'sdk-process.json', json.dumps(identity, indent=2))
    if process.returncode or b'FIXTURE_SDK_PROBE_PASSED' not in (root / 'sdk-probe.log').read_bytes():
        raise RuntimeError('官方发行包真实SDK注册/发现探针失败；日志保留，不回显凭据')
    return {'passed': True, 'sdkVersion': runtime['serverVersion'], 'registration': True,
            'discovery': True, 'deregistration': True, 'namespaceIsolation': True,
            'businessApplicationSdkTested': False}


def run(distribution, maven=False, version='3.0.3', hold_seconds=0, sdk_probe=False):
    distribution = distribution.resolve()
    package = distribution / f'nacos-server-{version}.zip'
    with package.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != RELEASES[version]:
            raise RuntimeError('官方发行包SHA256不符')
    original_jar = distribution / 'nacos' / 'target' / 'nacos-server.jar'
    with zipfile.ZipFile(package) as archive, archive.open('nacos/target/nacos-server.jar') as original, original_jar.open('rb') as extracted:
        if hashlib.file_digest(original, 'sha256').hexdigest() != hashlib.file_digest(extracted, 'sha256').hexdigest():
            raise RuntimeError('已解压服务器JAR与官方发行包不符')
    root = ROOT / '.local' / ('nacos-run-' + uuid.uuid4().hex)
    root.mkdir(exist_ok=False)
    if os.name == 'nt':
        import getpass
        subprocess.run(['icacls', str(root), '/inheritance:r', '/grant:r', getpass.getuser() + ':(OI)(CI)F'],
                       capture_output=True, check=True)
    else:
        root.chmod(0o700)
    home = root / 'nacos'
    shutil.copytree(distribution / 'nacos' / 'conf', home / 'conf')
    (home / 'target').mkdir()
    shutil.copy2(distribution / 'nacos' / 'target' / 'nacos-server.jar', home / 'target' / 'nacos-server.jar')
    # 3.3官方发行包把鉴权/数据源实现拆到plugins；直接取已校验ZIP内原件，保持官方插件布局。
    with zipfile.ZipFile(package) as archive:
        for name in archive.namelist():
            if name.startswith('nacos/plugins/') and not name.endswith('/'):
                target = home / Path(name).relative_to('nacos')
                if not target.resolve().is_relative_to(home.resolve()):
                    raise RuntimeError('官方包插件路径越界')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
    main, console = ports()
    namespace = 'tradeflow-fixture-' + uuid.uuid4().hex
    password = 'Fixture!9' + secrets.token_hex(24)
    protect(root / 'fixture-secret.json', json.dumps({'username': 'nacos', 'password': password}))
    identity_key, identity_value = 'fixtureIdentity', secrets.token_hex(32)
    token_secret = base64.b64encode(secrets.token_bytes(48)).decode()
    extra = f'''
nacos.server.main.port={main}
server.address=127.0.0.1
nacos.console.port={console}
nacos.inetutils.ip-address=127.0.0.1
nacos.core.auth.enabled=true
nacos.core.auth.admin.enabled=true
nacos.core.auth.console.enabled=true
nacos.core.auth.server.identity.key={identity_key}
nacos.core.auth.server.identity.value={identity_value}
nacos.core.auth.plugin.nacos.token.secret.key={token_secret}
nacos.ai.mcp.registry.enabled=false
nacos.istio.mcp.server.enabled=false
nacos.k8s.sync.enabled=false
nacos.core.api.compatibility.console.enabled=true
nacos.core.api.compatibility.admin.enabled=true
nacos.core.api.compatibility.enabled=true
nacos.core.api.compatibility.client.enabled=true
'''
    if version == '3.3.0-RC':
        extra += f'\nnacos.plugin.auth.type=nacos\nnacos.plugin.auth.nacos.token.secret.key={token_secret}\n'
    conf = home / 'conf' / 'application.properties'
    protect(conf, conf.read_text(encoding='utf-8') + extra)
    runtime = {'fixtureOnly': True, 'endpoint': f'127.0.0.1:{main}', 'namespace': namespace,
               'consolePort': console, 'serverVersion': version, 'prerelease': 'RC' in version,
               'credentialFilePath': str(root / 'fixture-secret.json'),
               'stopFilePath': str(root / 'stop-requested'),
               'officialUrl': f'https://github.com/alibaba/nacos/releases/download/{version}/{package.name}',
               'officialZipSha256': RELEASES[version]}
    protect(root / 'runtime.json', json.dumps(runtime, indent=2))
    # Oracle javapath可能是另起子进程的shim；直接调用java.home/bin，确保Popen PID就是服务器PID。
    probe = subprocess.run([shutil.which('java'), '-XshowSettings:properties', '-version'], capture_output=True, timeout=15)
    java_home = re.search(r'^\s*java.home\s*=\s*(.+)$', probe.stderr.decode(errors='replace'), re.MULTILINE)
    if java_home is None:
        raise RuntimeError('无法确认实际JDK目录')
    java = str(Path(java_home.group(1).strip()) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
    command = [java, '-Xms128m', '-Xmx512m', '-XX:ActiveProcessorCount=2', '-Dnacos.standalone=true',
               '-Dloader.path=' + ','.join((home / part).as_posix() for part in
                   ('plugins', 'plugins/health', 'plugins/cmdb', 'plugins/selector')),
               '-Dnacos.remote.grpc.listen.ip=127.0.0.1', f'-Dnacos.home={home}',
               '-Djava.net.preferIPv4Stack=true', '-jar', str(home / 'target' / 'nacos-server.jar'),
               '--spring.config.additional-location=file:' + (home / 'conf').as_posix() + '/',
               '--logging.config=' + str(home / 'conf' / 'nacos-logback.xml')]
    with (root / 'server-output.log').open('wb') as output:
        process = subprocess.Popen(command, cwd=root, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        runtime['pid'] = process.pid
        report = {**runtime, 'result': 'starting', 'mavenExecuted': False}
        try:
            runtime.update(process_identity(process.pid))
            report.update(runtime)
            protect(root / 'runtime.json', json.dumps(runtime, indent=2))
            print(json.dumps({'phase': 'starting', 'fixture': str(root), 'endpoint': runtime['endpoint']}), flush=True)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('隔离Nacos提前退出；日志保留，不回显可能含秘密的日志')
                # 首次HTTP写入前先证明该端口属于本进程，避免端口争用时误初始化其它registry。
                bound = listeners(process.pid)
                if not any(row['LocalPort'] == main for row in bound):
                    time.sleep(.5)
                    continue
                try:
                    init_path = '/nacos/v3/auth/user/admin' if version == '3.3.0-RC' else '/nacos/v1/auth/users/admin'
                    code, body = request(main, init_path, 'POST', {'password': password})
                    if code == 200:
                        break
                except (OSError, TimeoutError):
                    pass
                time.sleep(.5)
            else:
                raise RuntimeError('隔离Nacos初始化超时')
            bound = listeners(process.pid)
            if not bound:
                raise RuntimeError('没有归属于本进程的回环监听')
            # 未携带token的命名查询必须被拒绝，避免将无鉴权registry暴露为可用fixture。
            naming_path = '/nacos/v3/client/ns/instance/list' if version == '3.3.0-RC' else '/nacos/v1/ns/instance/list'
            code, _ = request(main, naming_path + '?serviceName=tradeflow-order')
            if code not in (401, 403):
                raise RuntimeError('隔离Nacos未拒绝未认证命名请求')
            code, body = request(main, '/nacos/v1/auth/login', 'POST', {'username': 'nacos', 'password': password})
            if code != 200:
                raise RuntimeError('隔离Nacos鉴权失败')
            token = json.loads(body)['accessToken']
            for ns in (namespace, namespace + '-other'):
                code, body = request(main, '/nacos/v3/admin/core/namespace', 'POST',
                    {'namespaceId': ns, 'namespaceName': ns, 'namespaceDesc': 'isolated local fixture', 'accessToken': token})
                if code != 200 or json.loads(body).get('data') is not True:
                    raise RuntimeError('隔离namespace创建失败')
            report = {**runtime, 'loopbackListeners': bound, 'unauthenticatedNamingRejected': True,
                      'authenticationSucceeded': True, 'independentNamespacesCreated': True, 'mavenExecuted': False}
            print(json.dumps({'phase': 'authenticated', 'fixture': str(root), 'authVerified': True}), flush=True)
            if sdk_probe:
                report['sdkProbe'] = probe_sdk(root, home, java, runtime)
                protect(root / 'sdk-probe-result.json', json.dumps(report['sdkProbe'], indent=2))
            if maven:
                env = os.environ.copy()
                env['TRADEFLOW_NACOS_FIXTURE_DIR'] = str(root)
                with (root / 'maven.log').open('wb') as log:
                    report['mavenExecuted'] = True
                    result = subprocess.run(['cmd', '/c', str(ROOT / 'mvnw.cmd'), '-B', '-ntp', '-pl',
                        'tradeflow-payment-simulator', '-am', '-Dtest=NacosDiscoveryIntegrationTest',
                        '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], cwd=ROOT, env=env,
                        stdout=log, stderr=subprocess.STDOUT, timeout=240)
                report['mavenExitCode'] = result.returncode
                if result.returncode:
                    protect(root / 'result.json', json.dumps(report, indent=2))
                    raise RuntimeError('真实Nacos Maven测试失败；日志保留')
                report['discoveryTest'] = json.loads((root / 'java-result.json').read_text())
            report['finalLoopbackListeners'] = listeners(process.pid)
            report['result'] = 'passed'
            protect(root / 'ready.json', json.dumps({**runtime, 'loopbackListeners': report['finalLoopbackListeners'],
                                                   'sdkProbe': report.get('sdkProbe')}, indent=2))
            print(json.dumps({'phase': 'ready', 'fixture': str(root), 'holdSeconds': hold_seconds,
                              'sdkProbePassed': report.get('sdkProbe', {}).get('passed', False)}), flush=True)
            # 保留模式也由本进程持有Popen句柄；调用方写stop文件或有界超时即关闭，仅操作本次实例。
            deadline = time.monotonic() + hold_seconds
            while hold_seconds and time.monotonic() < deadline and not (root / 'stop-requested').exists():
                if process.poll() is not None:
                    raise RuntimeError('保留中的隔离Nacos提前退出')
                listeners(process.pid)
                time.sleep(1)
            report['finalLoopbackListeners'] = listeners(process.pid)
            report['stopReason'] = ('requested' if (root / 'stop-requested').exists()
                                    else 'hold-expired' if hold_seconds else 'default-cleanup')
        except RuntimeError as error:
            report['result'] = 'blocked'
            report['reason'] = str(error)
            raise
        except Exception as error:
            report['result'] = 'failed'
            report['reason'] = type(error).__name__
            raise
        finally:
            # Popen句柄只指向本脚本直接创建的Java进程，不按端口/名称查杀其它服务。
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=30)
            report['ownedServerStopped'] = process.poll() is not None
            (root / 'ready.json').unlink(missing_ok=True)
            protect(root / 'result.json', json.dumps(report, indent=2))
        print(json.dumps({'phase': 'completed', 'report': str(root / 'result.json'),
                          'mavenExecuted': maven, 'ownedServerStopped': report['ownedServerStopped']}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distribution', type=Path, required=True, help='含官方ZIP及已解压nacos的专属下载目录')
    parser.add_argument('--maven', action='store_true', help='仅在已协调Maven构建窗口时启用')
    parser.add_argument('--version', choices=RELEASES, default='3.0.3', help='允许的官方原版发行包；RC只用于本地隔离验证')
    parser.add_argument('--hold-seconds', type=int, default=0, help='成功后有界保留，由专属stop-requested文件提前关闭，默认立即停止')
    parser.add_argument('--sdk-probe', action='store_true', help='使用官方发行包SDK执行真实注册、发现、注销及namespace隔离；不运行Maven')
    args = parser.parse_args()
    try:
        if not 0 <= args.hold_seconds <= 3600:
            raise RuntimeError('hold-seconds必须在0到3600之间')
        run(args.distribution, args.maven, args.version, args.hold_seconds, args.sdk_probe)
    except RuntimeError as error:
        raise SystemExit(str(error) + '；未输出秘密，启动后的停止结果见专属result.json。') from None
    except Exception:
        raise SystemExit('隔离Nacos验证未完成；未输出秘密。检查专属目录日志与身份；不代表真实发现通过。') from None
