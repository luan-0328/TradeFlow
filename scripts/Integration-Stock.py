"""本机真实MySQL库存竞争联调：独立SKU、8笔不同键下单，不作为性能压测。"""
import concurrent.futures
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('integration',ROOT/'scripts/Integration-Local.py')
t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)


def run():
    """通过外部API准备10件库存并竞争预占，最后按业务取消释放，不直接改库存表。"""
    env=json.loads((ROOT/'.local/dev-env.json').read_text(encoding='utf-8-sig'))
    admin=t.call('POST','/auth/login',body={'username':'platform_admin','password':env['TRADEFLOW_BOOTSTRAP_PASSWORD']})['accessToken']
    password='Stock!'+t.secrets.token_urlsafe(20)+'Aa9';username=t.RUN+'_stock'
    user=t.call('POST','/auth/register',body={'username':username,'password':password,'displayName':'库存竞争联调'})
    uid=user['userId']
    t.call('PUT','/admin/users/'+uid+'/roles',admin,{'roles':['USER','MERCHANT_ADMIN'],'reason':t.RUN})
    t.CLEANUP.append((admin,uid))
    t.call('POST','/admin/merchant-members',admin,{'userId':uid,'merchantId':'merchant_demo','status':'ACTIVE','reason':t.RUN})
    token=t.call('POST','/auth/login',body={'username':username,'password':password})['accessToken']
    product=t.call('POST','/merchant/products',token,{'name':t.RUN,'description':'库存竞争专用SKU','status':'ON_SALE','skus':[{'skuCode':t.RUN,'specification':'并发预占','priceCents':100}]},t.key())
    sku=product['skus'][0]['skuId'];pid=product['productId']
    t.call('POST','/merchant/inventory/adjustments',token,{'warehouseId':'warehouse_main','reason':t.RUN,'items':[{'skuId':sku,'quantityDelta':10}]},t.key())
    def stock():return t.call('GET','/products/'+pid)['skus'][0]
    body={'items':[{'skuId':sku,'quantity':3,'expectedUnitPriceCents':100}],'address':{'receiverName':'库存联调','receiverPhone':'13800000000','region':'虚构测试区','detailAddress':'本机联调地址'}}
    stable=t.key();first=t.call('POST','/orders',token,body,stable)
    changed={**body,'items':[{**body['items'][0],'quantity':2}]}
    t.check('下单同键异参拒绝',t.call('POST','/orders',token,changed,stable,expected=(409,))['code']=='IDEMPOTENCY_CONFLICT')
    with concurrent.futures.ThreadPoolExecutor(max_workers=7) as pool:others=list(pool.map(lambda _:t.call('POST','/orders',token,body,t.key()),range(7)))
    orders=[first,*others]
    t.REPORT['resources']={'skuId':sku,'productId':pid,'orders':[o['businessNo'] for o in orders],'users':{'merchant':{'id':uid,'username':username}}}
    states=[]
    for item in orders:
        no=item['businessNo']
        result=t.wait('库存竞争落定',lambda n=no:t.call('GET','/orders/requests/'+n,token),lambda p:p['state'] in ['PENDING_PAYMENT','CLOSED','REJECTED'],timeout=100)
        states.append(result)
    pending=[o for o in states if o['state']=='PENDING_PAYMENT']
    balance=stock()
    t.check('10件库存8笔各3件仅3笔预占成功',len(pending)==3)
    t.check('竞争后可售1预占9无负库存',balance['availableQty']==1 and balance['reservedQty']==9)
    for item in pending:
        no=item['businessNo'];t.call('POST','/orders/'+no+'/cancel',token,{'reason':t.RUN},t.key())
        t.wait('竞争订单取消释放',lambda n=no:t.call('GET','/orders/'+n,token),lambda o:o['status']=='CLOSED' and o['stockSyncStatus']=='RELEASED')
    balance=stock();t.check('全部取消后恢复可售10预占0',balance['availableQty']==10 and balance['reservedQty']==0)
    t.REPORT['status']='PASSED'


if __name__=='__main__':
    try:run()
    except Exception as error:t.REPORT['status']='FAILED';t.REPORT['failure']=str(error);print('FAIL',str(error))
    finally:t.restore_test_roles();t.save();print('REPORT',t.OUT/'report.json')
    raise SystemExit(0 if t.REPORT['status']=='PASSED' else 1)
