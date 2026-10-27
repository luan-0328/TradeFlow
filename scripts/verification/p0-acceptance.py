"""仅针对p0-stack新实例的真实八服务HTTP验收；不读取既有开发凭据，不重试未知写请求。"""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]


class Acceptance:
    def __init__(self, ready):
        self.ready = json.loads(ready.read_text(encoding='utf-8'))
        self.root = Path(self.ready['root']).resolve()
        if not self.ready.get('fixtureOnly') or self.root.parent != (ROOT / '.local').resolve() or not self.root.name.startswith('p0-stack-'):
            raise RuntimeError('必须使用本任务p0-stack专属ready，禁止现有开发环境')
        self.secret = json.loads(Path(self.ready['credentialFilePath']).read_text(encoding='utf-8'))
        self.base = self.ready['gatewayUrl']
        if not re.fullmatch(r'http://127\.0\.0\.1:[0-9]+', self.base):
            raise RuntimeError('网关必须为回环随机端口')
        self.suffix = uuid.uuid4().hex[:12]
        self.report = {'kind': 'eight-services-real-nacos-mq-http', 'checks': [], 'resources': {}, 'status': 'RUNNING'}
        self.accounts = {'platform': {'username': self.secret['bootstrapUsername'], 'password': self.secret['bootstrapPassword']}}
        self.tokens = {}
        self.merchants = {name: 'merchant_p0_' + self.suffix + '_' + name for name in ('a', 'b')}
        self.products = {}
        self.orders = {}

    def save(self):
        (self.root / 'api-result.json').write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding='utf-8')

    def check(self, name, condition):
        self.report['checks'].append({'name': name, 'passed': bool(condition)})
        self.save()
        if not condition:
            raise AssertionError(name)
        print('PASS ' + name, flush=True)

    def call(self, method, path, who=None, body=None, key=None, merchant=None, expected=(200, 201, 202), absolute=None, extra=None):
        headers = {'Accept': 'application/json'}
        if who:
            headers['Authorization'] = 'Bearer ' + self.tokens[who]
        if merchant:
            headers['X-Merchant-Id'] = merchant
        if key:
            headers['Idempotency-Key'] = key
        if extra:
            headers.update(extra)
        data = None
        if body is not None:
            data = json.dumps(body).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        url = (absolute or self.base) + '/api/v1' + path
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                status, value = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            status = error.code
            try:
                value = json.load(error)
            except ValueError:
                value = {}
        if status not in expected or status < 400 and value.get('code') != 'OK':
            raise AssertionError(f'{method} {path}: HTTP {status}, code={value.get("code")}')
        return value.get('data') if status < 400 else {'httpStatus': status, 'code': value.get('code')}

    def login(self, name):
        self.tokens[name] = self.call('POST', '/auth/login', body=self.accounts[name])['accessToken']

    def key(self):
        return 'p0_' + uuid.uuid4().hex

    def wait(self, name, loader, predicate, timeout=150):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = loader()
            if predicate(value):
                return value
            time.sleep(.7)
        raise AssertionError(name + '未在有界时间内收敛')

    def place(self, merchant, product, shipping=0, stable=None):
        body = {'merchantId': merchant, 'expectedShippingCents': shipping,
                'items': [{'skuId': product['skuId'], 'quantity': 1, 'expectedUnitPriceCents': product['priceCents']}],
                'address': {'receiverName': '验收收件人', 'receiverPhone': '13800000000', 'region': '隔离测试区', 'detailAddress': '仅用于隔离验收'}}
        return self.call('POST', '/orders', 'buyer', body, stable or self.key())

    def order(self, no):
        return self.call('GET', '/orders/' + no, 'buyer')

    def pending(self, no):
        self.wait('订单创建', lambda: self.call('GET', '/orders/requests/' + no, 'buyer'), lambda row: row['state'] in ('PENDING_PAYMENT', 'REJECTED', 'CLOSED'))
        result = self.order(no)
        self.check('创建成功 ' + no, result['status'] == 'PENDING_PAYMENT')
        return result

    def pay(self, no):
        key = self.key()
        fact = self.call('POST', '/payments', 'buyer', {'orderNo': no}, key)
        self.orders['replayPaymentNo'] = fact['paymentNo']
        replay = self.call('POST', '/payments', 'buyer', {'orderNo': no}, key)
        self.check('支付原键保持原号 ' + no, replay['paymentNo'] == fact['paymentNo'])
        return self.wait('真实MQ支付与库存确认', lambda: self.order(no), lambda row: row['status'] == 'PAID' and row['stockSyncStatus'] == 'CONFIRMED')

    def receive(self, no, name, merchant):
        self.call('POST', '/merchant/orders/' + no + '/ship', name, {'carrier': '验收物流', 'trackingNo': 'P0' + uuid.uuid4().hex}, self.key(), merchant)
        self.call('POST', '/orders/' + no + '/receive', 'buyer', key=self.key())
        self.check('真实履约完成 ' + no, self.order(no)['status'] == 'COMPLETED')

    def refund(self, no, amount, kind, merchant):
        unit = self.order(no)['items'][0]
        body = {'orderNo': no, 'type': kind, 'reason': '隔离P0售后', 'evidenceKeys': [],
                'allocations': [{'orderItemId': unit['orderItemId'], 'unitNo': 1, 'amountCents': amount}]}
        key = self.key()
        row = self.call('POST', '/aftersales', 'buyer', body, key)
        self.check('售后原键保持原号', self.call('POST', '/aftersales', 'buyer', body, key)['aftersaleNo'] == row['aftersaleNo'])
        sale = row['aftersaleNo']
        self.wait('原号权益授权', lambda: self.call('GET', '/aftersales/' + sale, 'buyer'), lambda item: item['status'] == 'PENDING_REVIEW')
        self.check('另一商家不能读取售后', self.call('GET', '/merchant/aftersales/' + sale, 'ownerB', merchant=self.merchants['b'], expected=(404,))['httpStatus'] == 404)
        self.call('POST', '/merchant/aftersales/' + sale + '/review', 'ownerA', {'approved': True, 'reason': '真实受控审核'}, self.key(), merchant)
        if kind == 'RETURN_REFUND':
            self.call('POST', '/aftersales/' + sale + '/return-shipment', 'buyer', {'carrier': '验收退货', 'trackingNo': 'RET' + uuid.uuid4().hex}, self.key())
            self.call('POST', '/merchant/aftersales/' + sale + '/return-inspection', 'ownerA',
                      {'accepted': True, 'reason': '实际逐件验收', 'evidenceKeys': [],
                       'dispositions': [{'orderItemId': unit['orderItemId'], 'unitNo': 1, 'disposition': 'SALEABLE'}]}, self.key(), merchant)
        return self.wait('退款/入库最终完成', lambda: self.call('GET', '/aftersales/' + sale, 'buyer'), lambda item: item['status'] == 'COMPLETED')

    def settlement_item(self, no):
        rows = self.call('GET', '/admin/settlement/items?page=1&pageSize=100', 'platform')['items']
        return next((row for row in rows if row['order_no'] == no), None)

    def sql(self, schema, statement):
        if not statement.lstrip().upper().startswith('SELECT '):
            raise RuntimeError('业务验收SQL只读')
        db = self.secret['database']
        client = Path(os.environ.get('TRADEFLOW_MYSQL_BIN', 'D:/mysql-8.0.46-winx64/bin')) / 'mysql.exe'
        environment = os.environ.copy() | {'MYSQL_PWD': db.get('rootPassword', '')}
        command = [str(client), '--no-defaults', '--protocol=TCP', '-h127.0.0.1', f'--port={db["port"]}', '--user=' + db.get('rootUser', 'root'), '-N', '-B', '--default-character-set=utf8mb4', 'trade_' + schema]
        result = subprocess.run(command, input=statement.encode('utf-8'), capture_output=True, timeout=15, env=environment, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        if result.returncode:
            raise RuntimeError('只读不变量查询失败，未输出SQL或凭据')
        return result.stdout.decode('utf-8').strip()

    def run(self):
        try:
            self.login('platform')
            for code in ('user', 'product', 'order', 'payment', 'aftersale', 'settlement', 'payment-simulator'):
                row = self.call('GET', '/' + code + '/system/info')
                self.check('真实Nacos网关路由 ' + code, row['serviceName'] == 'tradeflow-' + code)
            ids = {}
            for name in ('ownerA', 'ownerB', 'operator', 'buyer', 'outsider', 'browserOwner'):
                account = {'username': 'p0_' + self.suffix + '_' + name.lower() + '@example.test', 'password': 'P0!aZ9' + secrets.token_hex(14)}
                self.accounts[name] = account
                self.call('POST', '/auth/email-code', body={'email': account['username']})
                mailbox = Path(self.ready['smtpMessagesPath']).resolve()
                if mailbox.parent != self.root:
                    raise RuntimeError('邮件捕获文件必须属于本次fixture')
                code = json.loads(mailbox.read_text(encoding='utf-8'))[account['username']]
                ids[name] = self.call('POST', '/auth/register', body={'email': account['username'], 'password': account['password'], 'displayName': '验收' + name, 'code': code})['userId']
                self.login(name)
            for label, name in [('a', 'ownerA'), ('b', 'ownerB')]:
                application = self.call('POST', '/merchant-applications', name, {'merchantId': self.merchants[label], 'name': 'P0商家' + label, 'description': '隔离验收经营资料'})
                self.call('POST', '/admin/merchant-applications/' + str(application['id']) + '/review', 'platform', {'approved': True, 'reason': 'P0真实审核', 'version': application['version']})
                self.check('入驻撤销原会话 ' + label, self.call('GET', '/users/me', name, expected=(401,))['httpStatus'] == 401)
                self.login(name)
            for label, role in [('a', 'ADMIN'), ('b', 'OPERATOR')]:
                self.call('POST', '/admin/merchant-members', 'platform', {'userId': ids['operator'], 'merchantId': self.merchants[label], 'status': 'ACTIVE', 'role': role, 'reason': '独立商家职级'})
            self.call('PUT', '/admin/users/' + ids['operator'] + '/roles', 'platform', {'roles': ['USER', 'MERCHANT_ADMIN'], 'reason': '故意保留旧全局角色验证不提权'})
            self.login('operator')
            capabilities = self.call('GET', '/users/me/capabilities', 'operator')
            self.check('同账号A店管理员B店操作员', capabilities['merchantRoles'][self.merchants['a']] == 'ADMIN' and capabilities['merchantRoles'][self.merchants['b']] == 'OPERATOR')
            self.check('B店操作员不能自提权', self.call('PUT', '/merchants/' + self.merchants['b'] + '/members', 'operator', {'userId': ids['operator'], 'merchantId': self.merchants['b'], 'status': 'ACTIVE', 'role': 'ADMIN', 'reason': '拒绝自提权'}, merchant=self.merchants['b'], expected=(403,))['httpStatus'] == 403)
            for label, name, price in [('a', 'ownerA', 10000), ('b', 'ownerB', 5000)]:
                body = {'name': 'P0商品' + self.suffix + label, 'description': '真实八服务商品', 'status': 'ON_SALE', 'skus': [{'skuCode': 'p0_' + self.suffix + '_' + label, 'specification': '验收标准款', 'priceCents': price}]}
                product = self.call('POST', '/merchant/products', name, body, self.key(), self.merchants[label])
                self.products[label] = {'productId': product['productId'], 'skuId': product['skus'][0]['skuId'], 'priceCents': price, 'name': product['name']}
                self.call('POST', '/merchant/inventory/adjustments', name, {'warehouseId': 'warehouse_main', 'reason': '初始验收入库', 'items': [{'skuId': self.products[label]['skuId'], 'quantityDelta': 8}]}, self.key(), self.merchants[label])
            self.check('旧global ADMIN不能管理B店商品', self.call('POST', '/merchant/products/' + self.products['b']['productId'] + '/skus', 'operator', {'skuCode': 'denied_' + self.suffix, 'specification': '不应创建', 'priceCents': 100}, self.key(), self.merchants['b'], expected=(403,))['httpStatus'] == 403)
            self.check('A店不能编辑B店资源', self.call('PATCH', '/merchant/products/' + self.products['b']['productId'], 'operator', {'name': '不应写入', 'description': '隔离', 'status': 'ON_SALE', 'version': 0}, merchant=self.merchants['a'], expected=(403, 404))['httpStatus'] in (403, 404))
            self.call('PUT', '/merchant/shipping-rule', 'ownerA', {'feeCents': 500, 'version': 1}, self.key(), self.merchants['a'])
            requests = []
            for label in ('a', 'b'):
                product = self.products[label]
                requests.append({'merchantId': self.merchants[label], 'expectedShippingCents': 500 if label == 'a' else 0, 'items': [{'skuId': product['skuId'], 'quantity': 1, 'expectedUnitPriceCents': product['priceCents']}], 'address': {'receiverName': '验收买家', 'receiverPhone': '13800000000', 'region': '隔离测试区', 'detailAddress': '虚构验收地址'}})
            key = self.key()
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                groups = list(pool.map(lambda _: self.call('POST', '/trade-groups', 'buyer', {'orders': requests}, key), range(2)))
            self.check('真实HTTP并发原键只创建同一交易组', groups[0]['groupNo'] == groups[1]['groupNo'] and len(groups[0]['children']) == 2)
            self.check('交易组原键恢复', self.call('GET', '/trade-groups/requests/by-key', 'buyer', key=key)['groupNo'] == groups[0]['groupNo'])
            self.check('他人不能读取交易组', self.call('GET', '/trade-groups/' + groups[0]['groupNo'], 'outsider', expected=(404,))['httpStatus'] == 404)
            for child in groups[0]['children']:
                label = next(label for label in self.merchants if self.merchants[label] == child['merchantId'])
                self.orders[label] = child['orderNo']
                order = self.pending(child['orderNo'])
                self.check('含运费权威金额 ' + label, order['amountCents'] == (10500 if label == 'a' else 5000))
                self.pay(child['orderNo'])
                self.receive(child['orderNo'], 'ownerA' if label == 'a' else 'ownerB', self.merchants[label])
            self.wait('实际资金导入和模拟结算', lambda: self.settlement_item(self.orders['a']), lambda item: item is not None and item['status'] == 'SIMULATED_COMPLETED')
            self.refund(self.orders['a'], 2000, 'REFUND_ONLY', self.merchants['a'])
            unit = self.order(self.orders['a'])['items'][0]['units'][0]
            self.check('105元实付补偿20后剩85元', unit['succeededRefundCents'] == 2000 and unit['remainingRefundCents'] == 8500)
            self.wait('结算后成功退款冲正', lambda: self.call('GET', '/admin/settlement/summary?merchantId=' + self.merchants['a'], 'platform'), lambda row: row['debtCents'] == 1800)
            self.refund(self.orders['a'], 8500, 'RETURN_REFUND', self.merchants['a'])
            self.check('先补偿再退货累计不超实付', self.order(self.orders['a'])['items'][0]['units'][0]['succeededRefundCents'] == 10500)
            self.wait('全额退款结算冲正', lambda: self.call('GET', '/admin/settlement/summary?merchantId=' + self.merchants['a'], 'platform'), lambda row: row['debtCents'] == 9450 and row['feeCents'] == 0)
            repeat = self.place(self.merchants['a'], self.products['a'], 500)['businessNo']
            self.pending(repeat); self.pay(repeat); self.receive(repeat, 'ownerA', self.merchants['a'])
            self.wait('后批抵扣历史退款欠款', lambda: self.call('GET', '/admin/settlement/summary?merchantId=' + self.merchants['a'], 'platform'), lambda row: row['debtCents'] == 0)
            batches = self.call('GET', '/admin/settlement/batches?merchantId=' + self.merchants['a'], 'platform')['items']
            self.check('真实后批结算抵扣9450分而不重复模拟出款', any(row['debt_offset_cents'] == 9450 and row['simulated_payout_cents'] == 0 for row in batches))
            self.check('实际结算本地核对通过', self.call('POST', '/admin/settlement/reconcile', 'platform')['result'] == 'PASS')
            self.check('库存余额可由流水重放守恒', self.sql('product', 'SELECT COUNT(*) FROM inventory i LEFT JOIN (SELECT warehouse_id,sku_id,SUM(available_delta) a,SUM(reserved_delta) r FROM inventory_ledger GROUP BY warehouse_id,sku_id) l ON l.warehouse_id=i.warehouse_id AND l.sku_id=i.sku_id WHERE i.available_qty<>COALESCE(l.a,0) OR i.reserved_qty<>COALESCE(l.r,0)') == '0')
            self.check('购买件实付与行实付一致', self.sql('order', 'SELECT COUNT(*) FROM order_item i JOIN (SELECT order_item_id,SUM(paid_amount_cents) paid FROM order_item_unit GROUP BY order_item_id) u ON u.order_item_id=i.id WHERE i.line_amount_cents<>u.paid') == '0')
            self.check('所有订单行实付与订单金额一致', self.sql('order', 'SELECT COUNT(*) FROM orders o JOIN (SELECT order_no,SUM(line_amount_cents) paid FROM order_item GROUP BY order_no) i ON i.order_no=o.order_no WHERE o.total_amount_cents<>i.paid') == '0')
            seeds = {'accounts': self.accounts, 'merchants': self.merchants, 'products': self.products, 'orders': self.orders}
            (self.root / 'browser-secret.json').write_text(json.dumps(seeds, ensure_ascii=False, indent=2), encoding='utf-8')
            self.report['resources'] = {'merchants': self.merchants, 'products': self.products, 'orders': self.orders, 'groupNo': groups[0]['groupNo']}
            self.report['status'] = 'PASS'
        except Exception as error:
            self.report['status'] = 'FAILED'
            self.report['failure'] = type(error).__name__ + ': ' + str(error)
            raise
        finally:
            self.save()
            print(json.dumps({'status': self.report['status'], 'checks': len(self.report['checks']), 'report': str(self.root / 'api-result.json')}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ready', type=Path, required=True)
    Acceptance(parser.parse_args().ready).run()
