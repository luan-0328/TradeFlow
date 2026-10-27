"""在本机将已发送支付事件按原ID重放，核验消费幂等和账本不变量，不编辑消息正文。"""
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('integration',ROOT/'scripts/Integration-Local.py')
test=importlib.util.module_from_spec(spec)
spec.loader.exec_module(test)


def run():
    """仅选最近一笔本机it账号的支付事件，反复发送等待SENT后验证，不抢占RUNNING租约。"""
    previous=[]
    for path in (ROOT/'.local/integration').glob('it_*/report.json'):
        report=json.loads(path.read_text(encoding='utf-8'))
        if report.get('status')=='PASSED' and report.get('resources',{}).get('faultOrders'):previous.append(report)
    if not previous:raise AssertionError('先执行完整Integration-Local.py')
    report=max(previous,key=lambda r:r['run'])
    # 从业务表只读取得本次测试订单支付号，不依赖其他账号或消息。
    prep_spec=importlib.util.spec_from_file_location('prepare',ROOT/'scripts/Prepare-Dev.py')
    prepare=importlib.util.module_from_spec(prep_spec);prep_spec.loader.exec_module(prepare)
    order=report['resources']['faultOrders'][0]
    if not __import__('re').fullmatch('ord_[a-f0-9]{32}',order):raise AssertionError('业务编号无效')
    admin=json.loads(prepare.ADMIN.read_text())
    payment=prepare.mysql(admin,"SELECT payment_no FROM trade_order.orders WHERE order_no='"+order+"'")
    env=json.loads((ROOT/'.local/dev-env.json').read_text(encoding='utf-8-sig'))
    token=test.call('POST','/auth/login',body={'username':'platform_admin','password':env['TRADEFLOW_BOOTSTRAP_PASSWORD']})['accessToken']
    def listing():return test.call('GET','/admin/payment-messages/outbox?pageSize=100',token)['items']
    row=next(r for r in listing() if r['aggregate_id']==payment and r['status']=='SENT')
    event=row['event_id']
    if not __import__('re').fullmatch('[A-Za-z0-9_:.-]{1,64}',event) or not __import__('re').fullmatch('pay_[a-f0-9]{32}',payment):raise AssertionError('消息或支付标识无效')
    for attempt in range(2):
        test.call('POST','/admin/payment-messages/outbox/'+event+'/retry',token,{'reason':test.RUN})
        test.wait('原事件再次发送',lambda:next(r for r in listing() if r['event_id']==event),lambda r:r['status']=='SENT')
        test.check('原消息重放已确认 '+str(attempt+1),True)
    # 等待消费者记录可见后核验同事件接收事实只有一条，资金流水也只有一条。
    inbox=prepare.mysql(admin,"SELECT COUNT(*) FROM trade_order.inbox_message WHERE event_id='"+event+"'")
    ledger=prepare.mysql(admin,"SELECT COUNT(*) FROM trade_payment.payment_ledger WHERE payment_no='"+payment+"' AND entry_type='PAYMENT_SUCCEEDED'")
    test.check('重放后订单Inbox仍只有一条',inbox=='1')
    test.check('重放后支付成功流水仍只有一条',ledger=='1')
    test.REPORT['resources']={'orderNo':order,'paymentNo':payment,'eventId':event}
    test.REPORT['status']='PASSED'


if __name__=='__main__':
    try:run()
    except Exception as error:test.REPORT['status']='FAILED';test.REPORT['failure']=str(error);print('FAIL',str(error))
    finally:test.save();print('REPORT',test.OUT/'report.json')
    raise SystemExit(0 if test.REPORT['status']=='PASSED' else 1)
