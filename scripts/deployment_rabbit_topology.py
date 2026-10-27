"""部署独立凭据的启动前拓扑，禁止从开发环境复制秘密。"""
import base64, hashlib, secrets

VHOST = '/tradeflow'
CONSUMER_ROUTES = {
    'order.payment': ('payment.succeeded.v1', 'payment.failed.v1', 'payment.closed.v1'),
    'order.refund': ('refund.succeeded.v1', 'refund.failed.v1'),
    'aftersale.refund': ('refund.succeeded.v1', 'refund.failed.v1'),
    'order.audit': ('order.paid.v1', 'order.closed.v1', 'aftersale.completed.v1'),
}


def expected_topology():
    """发布与只读核验共用无凭据契约；完整绑定事实不能从Broker ACK反推。"""
    queues, bindings = [], []
    for consumer, keys in CONSUMER_ROUTES.items():
        queue = 'q.' + consumer
        queues.append({'name': queue, 'vhost': VHOST, 'durable': True, 'auto_delete': False,
                       'arguments': {'x-queue-type': 'classic', 'x-dead-letter-exchange': 'tradeflow.dlx', 'x-dead-letter-routing-key': queue + '.dlq'}})
        queues.append({'name': queue + '.dlq', 'vhost': VHOST, 'durable': True, 'auto_delete': False, 'arguments': {'x-queue-type': 'classic'}})
        for key in keys:
            bindings.append({'source': 'tradeflow.events', 'vhost': VHOST, 'destination': queue, 'destination_type': 'queue', 'routing_key': key, 'arguments': {}})
        bindings.append({'source': 'tradeflow.dlx', 'vhost': VHOST, 'destination': queue + '.dlq', 'destination_type': 'queue', 'routing_key': queue + '.dlq', 'arguments': {}})
    return {'exchanges': [{'name': name, 'vhost': VHOST, 'type': 'topic', 'durable': True, 'auto_delete': False, 'internal': False, 'arguments': {}}
                          for name in ('tradeflow.events', 'tradeflow.dlx')], 'queues': queues, 'bindings': bindings}

def password_hash(value):
    """定义导入使用Rabbit salted SHA256哈希，文件中不放明文业务密码。"""
    salt=secrets.token_bytes(4)
    return base64.b64encode(salt+hashlib.sha256(salt+value.encode()).digest()).decode()

def definitions(credentials,env):
    """在业务启动前声明全拓扑与最小运行能力，避免mandatory有交换机却无正确消费者。"""
    users=[{'name':credentials['user'],'password_hash':password_hash(credentials['password']),'hashing_algorithm':'rabbit_password_hashing_sha256','tags':['administrator']}]
    permissions=[{'user':credentials['user'],'vhost':'/tradeflow','configure':'.*','write':'.*','read':'.*'}]
    # monitoring标签只允许管理API读取元数据；空资源权限禁止消费、发布及声明。
    monitor=env['TRADEFLOW_RABBITMQ_MONITOR_USER']
    users.append({'name':monitor,'password_hash':password_hash(env['TRADEFLOW_RABBITMQ_MONITOR_PASSWORD']),'hashing_algorithm':'rabbit_password_hashing_sha256','tags':['monitoring']})
    permissions.append({'user':monitor,'vhost':'/tradeflow','configure':'^$','write':'^$','read':'^$'})
    topic=[]
    for service in ('PAYMENT','ORDER','AFTERSALE'):
        user=env[f'TRADEFLOW_{service}_MQ_USER']
        users.append({'name':user,'password_hash':password_hash(env[f'TRADEFLOW_{service}_MQ_PASSWORD']),'hashing_algorithm':'rabbit_password_hashing_sha256','tags':[]})
        read={'PAYMENT':'^$','ORDER':r'^q\.order\.(payment|refund|audit)$','AFTERSALE':r'^q\.aftersale\.refund$'}[service]
        permissions.append({'user':user,'vhost':'/tradeflow','configure':'^$','write':r'^tradeflow\.(events|dlx)$','read':read})
        routes={'PAYMENT':r'^(payment\.(succeeded|failed|closed)|refund\.(succeeded|failed))\.v1$','ORDER':r'^order\.(paid|closed)\.v1$','AFTERSALE':r'^aftersale\.completed\.v1$'}[service]
        dead={'PAYMENT':'^$','ORDER':r'^q\.order\.(payment|refund|audit)\.dlq$','AFTERSALE':r'^q\.aftersale\.refund\.dlq$'}[service]
        for exchange,write in [('tradeflow.events',routes),('tradeflow.dlx',dead)]:topic.append({'user':user,'vhost':'/tradeflow','exchange':exchange,'write':write,'read':'.*'})
    return {'users':users,'vhosts':[{'name':VHOST}],'permissions':permissions,'topic_permissions':topic, **expected_topology()}
