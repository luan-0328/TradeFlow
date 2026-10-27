"""初始化本项目 Nacos 账号与配置；仅填充不存在的配置，不覆盖已有规则。"""
from pathlib import Path
import json
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
credentials = json.loads((ROOT / '.local/remote-env.json').read_text(encoding='utf-8'))
BASE = 'http://' + credentials['NACOS_SERVER_ADDR'] + '/nacos'


def request(path, parameters, method='POST'):
    """只返回解析结果；不打印账号、密码、访问令牌或带令牌的 URL。"""
    encoded = urlencode(parameters).encode()
    target = BASE + path
    call = Request(target + ('?' + encoded.decode() if method == 'GET' else ''),
                   data=None if method == 'GET' else encoded, method=method)
    with urlopen(call, timeout=15) as response:
        return json.loads(response.read())


try:
    initialized = request('/v3/auth/user/admin', {'password': credentials['NACOS_PASSWORD']})
    print('Nacos admin initialization requested; credentials not printed.')
except HTTPError as error:
    # 已初始化时保留原账号；随后必须用已保存密码实际登录成功，不能偷偷重置。
    if error.code not in (400, 409):
        raise RuntimeError('Nacos admin initialization HTTP ' + str(error.code)) from None
login = request('/v3/auth/user/login', {'username': credentials['NACOS_USERNAME'], 'password': credentials['NACOS_PASSWORD']})
token = login.get('accessToken') or login.get('data', {}).get('accessToken')
if not token:
    raise RuntimeError('Nacos login did not return a token; values not printed')


def ensure(data_id, content, data_type):
    """只为 TRADEFLOW 组创建缺失配置，尊重用户后续通过 Nacos 编辑的规则。"""
    parameters = {'dataId': data_id, 'groupName': 'TRADEFLOW', 'accessToken': token}
    try:
        existing = request('/v3/admin/cs/config', parameters, 'GET')
        if existing.get('code') in (0, 200) and existing.get('data'):
            print('Retained:', data_id)
            return
    except HTTPError as error:
        if error.code != 404:
            raise RuntimeError('Read config HTTP ' + str(error.code)) from None
    parameters.update(content=content, type=data_type)
    result = request('/v3/admin/cs/config', parameters)
    if result.get('code') not in (0, 200):
        raise RuntimeError('Config publish failed for ' + data_id + ', code=' + str(result.get('code')))
    print('Created:', data_id)


services = ['gateway', 'user', 'product', 'order', 'payment', 'aftersale', 'payment-simulator']
for service in services:
    app = 'tradeflow-' + service
    ensure(app + '.yaml', '# TradeFlow dev：只标记配置来源，不包含密码。\ntradeflow:\n  config-source: remote-nacos\n', 'yaml')
    if service == 'gateway':
        resources = ['info-' + code for code in ['user', 'product', 'order', 'payment', 'aftersale', 'payment-simulator']]
        flow = [dict(resource=resource, resourceMode=0, grade=1, count=20, intervalSec=1) for resource in resources]
    else:
        resources = ['/system/info']
        flow = [dict(resource='/system/info', grade=1, count=50, strategy=0, controlBehavior=0)]
    degrade = [dict(resource=resource, grade=1, count=0.5, timeWindow=10,
                    minRequestAmount=5, statIntervalMs=10000) for resource in resources]
    ensure(app + '-flow.json', json.dumps(flow), 'json')
    ensure(app + '-degrade.json', json.dumps(degrade), 'json')
print('Nacos project configuration is ready; no test or load generation performed.')
