"""本机真实服务联调；数据带it标识，凭据只在内存，报告不含密码、Token或真实个人资料。"""
import concurrent.futures
import datetime
import json
from pathlib import Path
import secrets
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]
RUN='it_'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S')+'_'+secrets.token_hex(3)
OUT=ROOT/'.local/integration'/RUN
REPORT={'run':RUN,'checks':[],'resources':{},'requests':[],'status':'RUNNING'}
LOCK=threading.Lock()
CLEANUP=[]


def save():
    """仅保存白名单结果与业务编号；不保存认证正文、令牌和运行环境。"""
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'report.json').write_text(json.dumps(REPORT,ensure_ascii=False,indent=2),encoding='utf-8')


def check(name,condition):
    with LOCK:
        REPORT['checks'].append({'name':name,'passed':bool(condition)})
    if not condition:raise AssertionError(name)
    print('PASS '+name,flush=True)


def call(method,path,token=None,body=None,key=None,expected=(200,201,202)):
    """只访问固定loopback网关；原键显式传入，网络错误不自动重投写入。"""
    headers={'Accept':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    if key:headers['Idempotency-Key']=key
    raw=None
    if body is not None:headers['Content-Type']='application/json';raw=json.dumps(body).encode()
    request=urllib.request.Request('http://127.0.0.1:8080/api/v1'+path,data=raw,headers=headers,method=method)
    try:
        with urllib.request.urlopen(request,timeout=20) as response:status=response.status;payload=json.load(response)
    except urllib.error.HTTPError as error:
        status=error.code
        try:payload=json.load(error)
        except ValueError:payload={}
    code=payload.get('code')
    with LOCK:REPORT['requests'].append({'method':method,'path':path,'status':status,'code':code,'requestId':payload.get('requestId')})
    if status not in expected or (status<400 and code!='OK'):raise AssertionError(f'HTTP {method} {path}: {status} {code}')
    return payload.get('data') if status<400 else {'httpStatus':status,'code':code}


def key():return RUN+'_'+secrets.token_hex(6)


def wait(name,loader,predicate,timeout=70):
    """轮询仅GET并设上限；超时记录最后业务状态，不把受理当成功。"""
    deadline=time.monotonic()+timeout
    while True:
        value=loader()
        if predicate(value):return value
        if time.monotonic()>deadline:raise AssertionError(name+' timeout, state='+str(value.get('status',value.get('state'))) + ', error='+str(value.get('lastError')))
        time.sleep(.6)


def run():
    env=json.loads((ROOT/'.local/dev-env.json').read_text(encoding='utf-8-sig'))
    admin=call('POST','/auth/login',body={'username':'platform_admin','password':env['TRADEFLOW_BOOTSTRAP_PASSWORD']})['accessToken']
    password='It!'+secrets.token_urlsafe(20)+'9aZ'
    users={}
    for role in ['buyer','outsider','merchant']:
        username=RUN+'_'+role
        user=call('POST','/auth/register',body={'username':username,'password':password,'displayName':'联调_'+role})
        users[role]={'username':username,'id':user['userId']}
    merchant_id=users['merchant']['id']
    call('PUT','/admin/users/'+merchant_id+'/roles',admin,{'roles':['USER','MERCHANT_ADMIN'],'reason':RUN})
    CLEANUP.append((admin,merchant_id))
    call('POST','/admin/merchant-members',admin,{'userId':merchant_id,'merchantId':'merchant_demo','status':'ACTIVE','reason':RUN})
    for role,user in users.items():user['token']=call('POST','/auth/login',body={'username':user['username'],'password':password})['accessToken']
    buyer=users['buyer']['token'];merchant=users['merchant']['token'];outsider=users['outsider']['token']
    REPORT['resources']['users']={k:{'username':v['username'],'id':v['id']} for k,v in users.items()}
    check('最新商家能力', 'MANAGE_INVENTORY' in call('GET','/users/me/capabilities',merchant)['allowedActions'])
    denied=call('GET','/admin/users',buyer,expected=(403,))
    check('普通用户不能管理账号',denied['httpStatus']==403)
    product=call('POST','/merchant/products',merchant,{'name':RUN,'description':'自动联调商品，仅供本次验证','status':'ON_SALE','skus':[{'skuCode':RUN,'specification':'单件100元','priceCents':10000}]},key())
    pid=product['productId'];sku=product['skus'][0]['skuId']
    REPORT['resources'].update(productId=pid,skuId=sku)
    inventory={'warehouseId':'warehouse_main','reason':RUN,'items':[{'skuId':sku,'quantityDelta':20}]}
    ik=key();receipt=call('POST','/merchant/inventory/adjustments',merchant,inventory,ik)
    check('库存入库同键重放',call('POST','/merchant/inventory/adjustments',merchant,inventory,ik)['businessNo']==receipt['businessNo'])
    def stock():return next(s for s in call('GET','/merchant/products',merchant)['items'] if s['productId']==pid)['skus'][0]
    check('初始库存20且无预占',stock()['availableQty']==20 and stock()['reservedQty']==0)
    address={'receiverName':'联调收件人','receiverPhone':'13800000000','region':'联调测试区','detailAddress':'联调专用虚构地址'}
    def place(quantity=1,stable=None):return call('POST','/orders',buyer,{'items':[{'skuId':sku,'quantity':quantity,'expectedUnitPriceCents':10000}],'address':address},stable or key())
    def order(no):return call('GET','/orders/'+no,buyer)
    def ready(no):
        wait('创建订单',lambda:call('GET','/orders/requests/'+no,buyer),lambda p:p['state'] in ['PENDING_PAYMENT','REJECTED','CLOSED'])
        return order(no)
    def pay(no):
        pk=key();p=call('POST','/payments',buyer,{'orderNo':no},pk)
        check('支付同键重放 '+no,call('POST','/payments',buyer,{'orderNo':no},pk)['paymentNo']==p['paymentNo'])
        recovered=call('GET','/payments/requests/by-key?orderNo='+no,buyer,key=pk)
        check('支付按原键恢复 '+no,recovered['paymentNo']==p['paymentNo'])
        wait('支付与库存确认',lambda:order(no),lambda o:o['status']=='PAID' and o['stockSyncStatus']=='CONFIRMED')
        return p
    def delivered(no):
        call('POST','/merchant/orders/'+no+'/ship',merchant,{'carrier':'联调物流','trackingNo':'IT'+uuid.uuid4().hex},key())
        call('POST','/orders/'+no+'/receive',buyer,key=key())
        check('确认收货 '+no,order(no)['status']=='COMPLETED')
    def sale(no,kind,amount,stable=None):
        o=order(no);unit={'orderItemId':o['items'][0]['orderItemId'],'unitNo':1,'amountCents':amount}
        body={'orderNo':no,'type':kind,'reason':RUN,'allocations':[unit],'evidenceKeys':[]}
        sk=stable or key();a=call('POST','/aftersales',buyer,body,sk)
        call('GET','/aftersales/requests/by-key?orderNo='+no,buyer,key=sk)
        return a,body,sk
    def reviewed(a):
        no=a['aftersaleNo']
        wait('售后授权',lambda:call('GET','/aftersales/'+no,buyer),lambda v:v['status'] in ['PENDING_REVIEW','REJECTED'])
        return call('POST','/merchant/aftersales/'+no+'/review',merchant,{'approved':True,'reason':RUN},key())
    def sale_done(a):return wait('售后完成',lambda:call('GET','/aftersales/'+a['aftersaleNo'],buyer),lambda v:v['status']=='COMPLETED')
    ok=key();accepted=place(stable=ok);no=accepted['businessNo'];REPORT['resources']['mainOrder']=no
    check('下单同键同参返回原号',place(stable=ok)['businessNo']==no)
    lookup=call('GET','/orders/requests/by-key',buyer,key=ok)
    check('下单原键恢复',lookup['found'] and lookup['progress']['businessNo']==no)
    ready(no)
    check('他人订单不可见',call('GET','/orders/'+no,outsider,expected=(404,))['httpStatus']==404)
    pay(no)
    call('GET','/merchant/orders/'+no+'/shipping-address',merchant)
    check('普通用户不能查看商家地址',call('GET','/merchant/orders/'+no+'/shipping-address',buyer,expected=(403,))['httpStatus']==403)
    delivered(no)
    check('完成订单不再披露地址',call('GET','/merchant/orders/'+no+'/shipping-address',merchant,expected=(409,))['httpStatus']==409)
    a,body,sk=sale(no,'REFUND_ONLY',2000);REPORT['resources']['partialAftersale']=a['aftersaleNo']
    check('售后创建同键同参返回原号',call('POST','/aftersales',buyer,body,sk)['aftersaleNo']==a['aftersaleNo'])
    reviewed(a);sale_done(a)
    unit=order(no)['items'][0]['units'][0]
    check('100元仅退20元后剩余80元',unit['succeededRefundCents']==2000 and unit['remainingRefundCents']==8000 and unit['reservedRefundCents']==0)
    check('仅退款不回库存',stock()['availableQty']==19)
    a,body,sk=sale(no,'RETURN_REFUND',8000);REPORT['resources']['returnAftersale']=a['aftersaleNo'];reviewed(a)
    wait('等待寄回',lambda:call('GET','/aftersales/'+a['aftersaleNo'],buyer),lambda v:v['status']=='WAITING_RETURN')
    call('POST','/aftersales/'+a['aftersaleNo']+'/return-shipment',buyer,{'carrier':'联调退货物流','trackingNo':'RET'+uuid.uuid4().hex},key())
    call('POST','/merchant/aftersales/'+a['aftersaleNo']+'/return-inspection',merchant,{'accepted':True,'reason':RUN,'evidenceKeys':[]},key())
    sale_done(a);unit=order(no)['items'][0]['units'][0]
    check('20元补偿再退货80元累计恰好100元',unit['succeededRefundCents']==10000 and unit['remainingRefundCents']==0 and unit['reservedRefundCents']==0)
    check('退货验收后库存回到20',stock()['availableQty']==20 and stock()['reservedQty']==0)
    # 并发同键下单应共用业务号；随后取消并等待释放，不直接修改数据库。
    concurrent_key=key()
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:copies=list(executor.map(lambda _:place(stable=concurrent_key),range(4)))
    check('四个并发同键下单只产生一个业务号',len({v['businessNo'] for v in copies})==1)
    cancel_no=copies[0]['businessNo'];REPORT['resources']['cancelOrder']=cancel_no;ready(cancel_no)
    call('POST','/orders/'+cancel_no+'/cancel',buyer,{'reason':RUN},key())
    wait('关闭与释放',lambda:order(cancel_no),lambda o:o['status']=='CLOSED' and o['stockSyncStatus']=='RELEASED')
    check('取消后库存守恒',stock()['availableQty']==20 and stock()['reservedQty']==0)
    sealed_key=key();sealed=call('POST','/orders/requests/seal',buyer,{'reason':RUN},sealed_key)
    check('未受理原键被封住',sealed['state']=='CANCELLED' and sealed['resultCode']=='REQUEST_SEALED')
    check('封键后迟到下单不创建新订单',place(stable=sealed_key)['businessNo']==sealed['businessNo'])
    check('封键无库存副作用',stock()['availableQty']==20 and stock()['reservedQty']==0)
    # 两笔不同键、同购买件退款竞争；异步受理不算通过，要等授权仲裁并核验可退额度。
    no2=place()['businessNo'];REPORT['resources']['concurrentRefundOrder']=no2;ready(no2);pay(no2);delivered(no2)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:claims=list(executor.map(lambda _:sale(no2,'REFUND_ONLY',6000)[0],range(2)))
    settled=[wait('同件售后竞争',lambda n=a['aftersaleNo']:call('GET','/aftersales/'+n,buyer),lambda v:v['status'] in ['PENDING_REVIEW','REJECTED']) for a in claims]
    winners=[a for a in settled if a['status']=='PENDING_REVIEW']
    check('同购买件两笔并发售后仅一笔获得权益',len(winners)==1 and sum(a['status']=='REJECTED' for a in settled)==1)
    reviewed(winners[0]);sale_done(winners[0]);unit=order(no2)['items'][0]['units'][0]
    check('并发后仅退60元余40元无占用',unit['succeededRefundCents']==6000 and unit['remainingRefundCents']==4000 and unit['reservedRefundCents']==0)
    over={'orderNo':no2,'type':'REFUND_ONLY','allocations':[{'orderItemId':order(no2)['items'][0]['orderItemId'],'unitNo':1,'amountCents':5000}]}
    check('超过剩余额度的预览被拒绝',call('POST','/aftersales/preview',buyer,over,expected=(409,422))['httpStatus'] in (409,422))
    # 故障只绑定本次新建支付/退款号，不改通道事实或其他测试数据。
    def scenario(resource,no,mode):
        path='/simulator-admin/v1/'+('scenarios' if resource=='payment' else 'refund-scenarios')
        body={('paymentNo' if resource=='payment' else 'refundNo'):no,'mode':mode,'actorId':'integration','reason':RUN}
        req=urllib.request.Request('http://127.0.0.1:8086'+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','X-Simulator-Admin-Token':env['TRADEFLOW_SIMULATOR_ADMIN_TOKEN']},method='POST')
        with urllib.request.urlopen(req,timeout=15) as response:result=json.load(response)
        check(resource+'场景绑定 '+mode,result['code']=='OK')
    fault_orders=[]
    for payment_mode,refund_mode in [('RESPONSE_TIMEOUT','RESPONSE_TIMEOUT'),('CALLBACK_DUPLICATE','CALLBACK_DUPLICATE'),('PROCESSING','REJECT')]:
        fn=place()['businessNo'];fo=ready(fn);scenario('payment',fo['paymentNo'],payment_mode);pay(fn);fault_orders.append(fn)
        a=call('POST','/aftersales',buyer,{'orderNo':fn,'type':'UNSHIPPED_FULL','reason':RUN,'allocations':[],'evidenceKeys':[]},key())
        wait('整单退款授权',lambda:call('GET','/aftersales/'+a['aftersaleNo'],buyer),lambda v:v['status']=='PENDING_REVIEW')
        scenario('refund',a['refundNo'],refund_mode);reviewed(a)
        if refund_mode=='REJECT':
            failed=wait('明确退款失败',lambda:call('GET','/aftersales/'+a['aftersaleNo'],buyer),lambda v:v['refundStatus']=='FAILED')
            retry_key=key();body={'reason':RUN}
            fresh=call('POST','/merchant/aftersales/'+a['aftersaleNo']+'/retry-refund',merchant,body,retry_key)
            check('明确失败后新尝试换退款号',fresh['refundNo']!=failed['refundNo'])
            check('退款重试同键保持原新号',call('POST','/merchant/aftersales/'+a['aftersaleNo']+'/retry-refund',merchant,body,retry_key)['refundNo']==fresh['refundNo'])
        done=sale_done(a)
        check('故障恢复后整单仅退100元 '+payment_mode,done['amountCents']==10000 and done['refundStatus']=='SUCCEEDED')
        check('故障恢复库存守恒 '+payment_mode,stock()['availableQty']==19 and stock()['reservedQty']==0)
    REPORT['resources']['faultOrders']=fault_orders
    # 定向对账可覆盖刚产生的事实；完整账单单独选已结束且满足五分钟宽限的范围。
    payment_no=order(no2)['paymentNo']
    action=call('POST','/admin/reconciliation/checks',admin,{'type':'PAYMENT','businessNo':payment_no,'reason':RUN},key())
    checked=wait('定向对账',lambda:call('GET','/admin/reconciliation/actions/'+action['task_no'],admin),lambda v:v['state'] in ['SUCCEEDED','MANUAL_REVIEW'])
    check('支付对账事实和唯一流水一致',checked['state']=='SUCCEEDED')
    now=datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    end=now-datetime.timedelta(minutes=6)
    body={'start':(end-datetime.timedelta(hours=1)).isoformat().replace('+00:00','Z'),'end':end.isoformat().replace('+00:00','Z'),'cutoff':now.isoformat().replace('+00:00','Z'),'reason':RUN}
    rk=key();run_record=call('POST','/admin/reconciliation/runs',admin,body,rk)
    check('对账创建原键重放',call('POST','/admin/reconciliation/runs',admin,body,rk)['task_no']==run_record['task_no'])
    reconciled=wait('完整账单对账',lambda:call('GET','/admin/reconciliation/runs/'+run_record['task_no'],admin),lambda v:v['state'] in ['SUCCEEDED','INCOMPLETE','MANUAL_REVIEW'],timeout=110)
    check('冻结完整账单对账完成',reconciled['state']=='SUCCEEDED')
    REPORT['resources']['reconciliationRun']=run_record['task_no']
    for service in ['order','payment','aftersale']:
        for kind in ['outbox','deferred','failure']:
            messages=call('GET',f'/admin/{service}-messages/{kind}',admin)
            check('消息管理可读取 '+service+'/'+kind,isinstance(messages.get('items'),list))
    call('PUT','/admin/users/'+merchant_id+'/roles',admin,{'roles':['USER'],'reason':RUN})
    check('撤权后旧商家Token立即失效',call('GET','/merchant/products',merchant,expected=(401,))['httpStatus']==401)
    REPORT['status']='PASSED'


def restore_test_roles():
    """成功或失败都撤销本次临时商家权限，保留订单证据；不停用真实账号或删除交易记录。"""
    results=[]
    for admin,uid in CLEANUP:
        try:
            call('PUT','/admin/users/'+uid+'/roles',admin,{'roles':['USER'],'reason':RUN+' 联调结束撤销临时权限'})
            call('POST','/admin/merchant-members',admin,{'userId':uid,'merchantId':'merchant_demo','status':'DISABLED','reason':RUN+' 联调结束撤销临时成员'})
            results.append({'userId':uid,'restored':True})
        except Exception:
            results.append({'userId':uid,'restored':False})
            REPORT['status']='FAILED';REPORT['failure']='临时测试权限恢复失败，需人工核对'
    REPORT['cleanup']=results


if __name__=='__main__':
    try:run()
    except Exception as error:
        REPORT['status']='FAILED';REPORT['failure']=str(error);print('FAIL '+str(error),flush=True)
    finally:restore_test_roles();save();print('REPORT '+str(OUT/'report.json'),flush=True)
    raise SystemExit(0 if REPORT['status']=='PASSED' else 1)
