"""本机联调后的只读资金/权益/库存核验，不写表、不输出数据库凭据。"""
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('prepare',ROOT/'scripts/Prepare-Dev.py')
prepare=importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)
QUERIES={
 '购买件额度不变量违规数':"SELECT COUNT(*) FROM trade_order.order_item_unit WHERE succeeded_refund_cents+reserved_refund_cents>paid_amount_cents OR succeeded_refund_cents<0 OR reserved_refund_cents<0",
 '退款成功累计超支付本金数':"SELECT COUNT(*) FROM trade_payment.payment_order p WHERE (SELECT COALESCE(SUM(r.amount_cents),0) FROM trade_payment.refund_order r WHERE r.payment_no=p.payment_no AND r.status='SUCCEEDED')>p.amount_cents",
 '支付成功流水缺失或重复数':"SELECT COUNT(*) FROM trade_payment.payment_order p WHERE p.status='SUCCEEDED' AND (SELECT COUNT(*) FROM trade_payment.payment_ledger l WHERE l.payment_no=p.payment_no AND l.entry_type='PAYMENT_SUCCEEDED')<>1",
 '退款成功流水缺失或重复数':"SELECT COUNT(*) FROM trade_payment.refund_order r WHERE r.status='SUCCEEDED' AND (SELECT COUNT(*) FROM trade_payment.payment_ledger l WHERE l.refund_no=r.refund_no AND l.entry_type='REFUND_SUCCEEDED')<>1",
 '库存与流水delta不一致数':"SELECT COUNT(*) FROM trade_product.inventory i WHERE i.available_qty<>(SELECT COALESCE(SUM(l.available_delta),0) FROM trade_product.inventory_ledger l WHERE l.sku_id=i.sku_id AND l.warehouse_id=i.warehouse_id) OR i.reserved_qty<>(SELECT COALESCE(SUM(l.reserved_delta),0) FROM trade_product.inventory_ledger l WHERE l.sku_id=i.sku_id AND l.warehouse_id=i.warehouse_id)",
 '未解决对账差异数':"SELECT COUNT(*) FROM trade_payment.reconciliation_issue WHERE state<>'RESOLVED'",
}


def run():
    """只连接准备器固定的本机专用端口；每条SQL仅返回违规计数，不能代替并发全过程证明。"""
    admin=json.loads(prepare.ADMIN.read_text())
    results=[]
    for name,sql in QUERIES.items():
        count=int(prepare.mysql(admin,sql))
        results.append({'name':name,'violationCount':count})
        print(name,count)
    output=ROOT/'.local/integration/invariants.json'
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    raise SystemExit(0 if all(r['violationCount']==0 for r in results) else 1)


if __name__=='__main__':run()
