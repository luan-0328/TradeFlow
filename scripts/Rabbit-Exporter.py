"""独立monitor账号只读采集队列及预期拓扑；网络故障输出NaN，不推断消费者业务成功。"""
import base64, hmac, json, sys, threading, time, urllib.request, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from deployment_rabbit_topology import expected_topology, VHOST

EXPECTED = expected_topology()
QUEUES = tuple(q['name'] for q in EXPECTED['queues'])
FIELDS = {'messages':'messages', 'messages_ready':'messages_ready', 'messages_unacked':'messages_unacknowledged', 'queue_consumers':'consumers'}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs): return None


def topology_checks(actual):
    """逐个比对固定资源属性及绑定；缺一扇出目标也判漂移，默认交换机隐式绑定不计入。"""
    checks = {}
    for kind in ('exchanges', 'queues'):
        indexed = {row['name']: row for row in actual[kind] if row.get('vhost') == VHOST}
        for expected in EXPECTED[kind]:
            row = indexed.get(expected['name'])
            matches = row is not None and all(row.get(k) == v for k, v in expected.items() if k != 'arguments')
            # 额外TTL/容量/死信参数也可能静默改变可靠性语义，不能只做预期参数子集比较。
            matches = matches and row.get('arguments', {}) == expected['arguments']
            if kind == 'queues' and row is not None:
                matches = matches and not row.get('exclusive', False) and not row.get('effective_policy_definition', {})
            checks[(kind[:-1], expected['name'])] = int(matches)
    for expected in EXPECTED['bindings']:
        identity = expected['source'] + '>' + expected['destination'] + ':' + expected['routing_key']
        checks[('binding', identity)] = int(any(all(row.get(k) == v for k, v in expected.items()) for row in actual['bindings']))
    for exchange in ('tradeflow.events', 'tradeflow.dlx'):
        # 标签只来自固定契约，额外的任意Broker资源不能制造高基数指标。
        expected_rows = [row for row in EXPECTED['bindings'] if row['source'] == exchange]
        actual_rows = [row for row in actual['bindings'] if row.get('source') == exchange and row.get('vhost') == VHOST]
        checks[('binding-set', exchange)] = int(len(actual_rows) == len(expected_rows) and all(
            any(all(row.get(k) == v for k, v in expected.items()) for row in actual_rows) for expected in expected_rows))
    return checks


def collect_once(config):
    """三次只读管理请求得到同轮快照；非原子采样有竞态，告警恢复后仍须独立核验再处置历史事件。"""
    port = int(config.get('managementPort', 15678))
    if not 1 <= port <= 65535: raise ValueError('managementPort')
    auth = base64.b64encode((config['user'] + ':' + config['password']).encode()).decode()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    actual = {}
    for kind in ('queues', 'exchanges', 'bindings'):
        url = f'http://127.0.0.1:{port}/api/{kind}/' + urllib.parse.quote(VHOST, safe='')
        req = urllib.request.Request(url, headers={'Authorization': 'Basic ' + auth})
        with opener.open(req, timeout=2) as response: actual[kind] = json.load(response)
        if not isinstance(actual[kind], list): raise ValueError('metadata shape')
    queues = {row['name']: row for row in actual['queues'] if row.get('vhost') == VHOST}
    # 缺失资源是已成功采样的拓扑故障；队列业务数值不能伪造为0。
    values = {name: {field: float(queues[name][source]) for field, source in FIELDS.items()}
              for name in QUEUES if name in queues}
    return values, topology_checks(actual)


def render(snapshot):
    lines = []
    for field in FIELDS:
        metric = 'tradeflow_rabbitmq_queue_' + (field if field != 'queue_consumers' else 'consumers')
        lines.append('# TYPE ' + metric + ' gauge')
        for queue in QUEUES:
            value = snapshot['values'].get(queue, {}).get(field, 'NaN')
            lines.append(f'{metric}{{queue="{queue}",vhost="{VHOST}"}} {value}')
    lines.append('# TYPE tradeflow_rabbitmq_topology_match gauge')
    for (kind, resource), value in snapshot['topology'].items():
        lines.append(f'tradeflow_rabbitmq_topology_match{{kind="{kind}",resource="{resource}",vhost="{VHOST}"}} {value}')
    valid = int(all(snapshot['topology'].values())) if snapshot['success'] else 'NaN'
    lines.extend([f'tradeflow_rabbitmq_topology_valid {valid}', f'tradeflow_rabbitmq_collection_success {snapshot["success"]}',
                  f'tradeflow_rabbitmq_last_success_unixtime_seconds {snapshot["last"]}'])
    return ('\n'.join(lines) + '\n').encode()


def main():
    """后台轮询不阻塞HTTP；业务JVM不加载monitor密码，未知采样不显示拓扑已恢复。"""
    config = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    empty = {key: 'NaN' for key in topology_checks({kind: [] for kind in EXPECTED})}
    state = {'values': {}, 'topology': empty, 'success': 0, 'last': 0}
    lock = threading.Lock()
    def collect():
        while True:
            started = time.monotonic()
            try:
                values, topology = collect_once(config)
                update = dict(values=values, topology=topology, success=1, last=time.time())
            except Exception:
                update = dict(values={}, topology=empty, success=0)
            with lock: state.update(update)
            time.sleep(max(0, 30 - (time.monotonic() - started)))
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self):
            """仅回环Bearer指标端点，不披露凭据、原文或Rabbit响应。"""
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + config['token']): self.send_error(401); return
            if self.path == '/health': self.send_response(200); self.end_headers(); return
            if self.path != '/metrics': self.send_error(404); return
            with lock: snapshot = dict(state)
            body = render(snapshot)
            self.send_response(200); self.send_header('Content-Type', 'text/plain; version=0.0.4'); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
    threading.Thread(target=collect, daemon=True, name='rabbit-readonly-collector').start()
    ThreadingHTTPServer(('127.0.0.1', int(config.get('listenPort', 15698))), Handler).serve_forever()

if __name__ == '__main__': main()
