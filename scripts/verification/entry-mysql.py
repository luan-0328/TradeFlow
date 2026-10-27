"""登录入口SQL的真实MySQL隔离验证；只创建/停止本脚本独占实例，不读取已有业务连接。"""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def statement(statement_id, parameters):
    """复用生产XML的同一statement及驼峰参数，避免离线验证持有已过时的注解SQL副本。"""
    mapper = ET.parse(ROOT / 'tradeflow-user/src/main/resources/mapper/user/LoginEntryMapper.xml').getroot()
    assert mapper.attrib['namespace'] == 'com.tradeflow.user.infrastructure.LoginEntryMapper'
    matches = [node for node in mapper if node.attrib.get('id') == statement_id]
    assert len(matches) == 1, f'登录入口SQL不存在或重名: {statement_id}'
    node = matches[0]
    assert not list(node), '动态MyBatis SQL需通过MyBatis解析，不能在隔离脚本中直接拼接'
    text = node.text.strip()
    return re.sub(r'#\{(\w+)\}', lambda match: "'" + parameters[match[1]].replace("'", "''") + "'"
                  if isinstance(parameters[match[1]], str) else str(parameters[match[1]]), text)


def main():
    binary = Path(os.environ.get('TRADEFLOW_MYSQL_BIN', str(Path(shutil.which('mysqld')).parent)))
    local = ROOT / '.local' / 'verification'
    local.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='entry-mysql-', dir=local))
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    config = work / 'my.ini'
    config.write_text(f'''[mysqld]
basedir={binary.parent.as_posix()}
datadir={(work / 'data').as_posix()}
port={port}
bind-address=127.0.0.1
mysqlx=0
default-time-zone=+00:00
transaction-isolation=READ-COMMITTED
innodb-buffer-pool-size=32M
log-error={(work / 'mysql-error.log').as_posix()}
''', encoding='utf-8')
    quiet = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    subprocess.run([str(binary / 'mysqld'), f'--defaults-file={config}', '--initialize-insecure'], check=True, capture_output=True, **quiet)
    process = subprocess.Popen([str(binary / 'mysqld'), f'--defaults-file={config}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **quiet)

    def sql(statement):
        result = subprocess.run([str(binary / 'mysql'), '--no-defaults', '--protocol=TCP', '-h127.0.0.1', f'-P{port}', '-uroot', '-N', '-B', '--default-character-set=utf8mb4'],
                                input=statement, text=True, encoding='utf-8', capture_output=True, **quiet)
        if result.returncode:
            raise RuntimeError('隔离入口SQL验证失败：' + result.stderr.strip())
        return result.stdout.strip()

    result = {'kind': 'real-mysql-sql', 'checks': []}
    try:
        last_probe_error = ''
        for _ in range(30):
            if process.poll() is not None:
                raise RuntimeError('独占测试MySQL提前退出')
            try:
                sql('SELECT 1;')
                break
            except RuntimeError as failure:
                last_probe_error = str(failure)
                time.sleep(.2)
        else:
            raise RuntimeError('独占测试MySQL启动超时: ' + last_probe_error)
        actual = Path(sql('SELECT @@datadir;')).resolve()
        assert actual == (work / 'data').resolve(), '拒绝访问非本脚本datadir'
        sql('CREATE DATABASE entry_test; USE entry_test;' + (ROOT / 'tradeflow-user/src/main/resources/db/migration/V5__login_entry_protection.sql').read_text(encoding='utf-8'))
        key = 'a' * 64
        parameters = {'attemptKey': key, 'windowSeconds': 3600, 'maximumAttempts': 10}
        reserve = statement('reserve', parameters)
        current = statement('current', parameters)

        def attempt(_):
            return int(sql(f'USE entry_test; BEGIN; {reserve}; {current}; COMMIT;').split('\t')[0])

        with ThreadPoolExecutor(max_workers=12) as pool:
            attempts = list(pool.map(attempt, range(32)))
        assert sum(value <= 10 for value in attempts) == 10, attempts
        assert max(attempts) == 11
        assert sql(f"USE entry_test;SELECT attempts FROM login_entry_window WHERE attempt_key='{key}';") == '11'
        result['checks'].append('32并发账号准入恰好10次，计数饱和11且拒绝独立提交')
        window = int(sql(f"USE entry_test; SELECT window_id FROM login_entry_window WHERE attempt_key='{key}';"))
        release = statement('release', {'attemptKey': key, 'windowId': window})
        sql(f'USE entry_test;{release};')
        assert sql(f"USE entry_test;SELECT attempts FROM login_entry_window WHERE attempt_key='{key}';") == '10'
        result['checks'].append('成功只减自身占用，不删除其他失败或并发预留')
        sql(f"USE entry_test;UPDATE login_entry_window SET window_id=window_id-1,attempts=11 WHERE attempt_key='{key}';{reserve};")
        assert sql(f"USE entry_test;SELECT attempts FROM login_entry_window WHERE attempt_key='{key}';") == '1'
        stale = statement('release', {'attemptKey': key, 'windowId': window - 1})
        sql(f'USE entry_test;{stale};')
        assert sql(f"USE entry_test;SELECT attempts FROM login_entry_window WHERE attempt_key='{key}';") == '1'
        result['checks'].append('过窗原子重置，旧窗口迟到成功不能减少新窗口次数')
        # MySQL时间函数固定为语句开始时刻；模拟旧语句等锁后才执行，窗口不许倒退。
        stale_reserve = reserve.replace('FLOOR(UNIX_TIMESTAMP()/3600)', str(window - 1))
        sql(f'USE entry_test;{stale_reserve};')
        assert sql(f"USE entry_test;SELECT CONCAT(window_id,':',attempts) FROM login_entry_window WHERE attempt_key='{key}';") == f'{window}:2'
        result['checks'].append('等待行锁跨窗的旧时间语句只能累加当前窗口，不能倒退重置')
        comments = sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='entry_test' AND table_name='login_entry_window' AND column_comment<>'';")
        assert comments == '4'
        result['checks'].append('新增表全部四字段有中文COMMENT')
        result['status'] = 'PASS'
    finally:
        if process.poll() is None:
            subprocess.run([str(binary / 'mysqladmin'), '--no-defaults', '--protocol=TCP', '-h127.0.0.1', f'-P{port}', '-uroot', 'shutdown'], capture_output=True, **quiet)
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        result['owned_process_stopped'] = process.poll() is not None
        (work / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'result': str(work / 'result.json'), **result}, ensure_ascii=False))


if __name__ == '__main__':
    main()
