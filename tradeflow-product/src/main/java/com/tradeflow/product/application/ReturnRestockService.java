package com.tradeflow.product.application;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.product.infrastructure.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** 实物验收独立于退款结果；基于不可撤销订单收据逐件处置，只有可再售商品回补销售。 */
@Service public class ReturnRestockService {
 private final ReturnReceiptClient client;private final ReturnRestockMapper receipts;private final RestockMapper unshipped;private final ReservationMapper reservations;private final ProductMapper products;private final TransactionTemplate tx;private final InventoryConditionMapper conditions;
 public ReturnRestockService(ReturnReceiptClient client,ReturnRestockMapper receipts,RestockMapper unshipped,ReservationMapper reservations,ProductMapper products,PlatformTransactionManager manager,InventoryConditionMapper conditions){this.conditions=conditions;this.client=client;this.receipts=receipts;this.unshipped=unshipped;this.reservations=reservations;this.products=products;tx=new TransactionTemplate(manager);}
 /** 先在事务外核验权威收据与绑定；随后按预占头、升序SKU锁定库存，失败保持原收据可重试。 */
 public RestockView restock(ReturnRestock c){
  if(c==null||c.orderNo()==null||!c.orderNo().matches("ord_[a-f0-9]{32}")||c.reservationNo()==null||!c.reservationNo().matches("res_[a-f0-9]{32}")||c.sourceNo()==null||!c.sourceNo().matches("as_[a-f0-9]{32}")||!("RET:"+c.sourceNo()).equals(c.receiptNo()))throw invalid("INVALID_RETURN_RESTOCK");
  var receipt=client.load(c.receiptNo());
  if(!c.receiptNo().equals(receipt.receiptNo())||!c.sourceNo().equals(receipt.sourceNo())||!c.orderNo().equals(receipt.orderNo())||!c.reservationNo().equals(receipt.reservationNo())||!("RECEIVED".equals(receipt.status())||"APPLIED".equals(receipt.status()))||receipt.lines()==null||receipt.lines().isEmpty())throw invalid("RETURN_RECEIPT_CONFLICT");
  var lines=new ArrayList<>(receipt.lines());var identities=new HashSet<String>();
  for(var line:lines)if(line==null||!positiveId(line.orderItemId())||line.unitNo()<1||!positiveId(line.skuId())||line.disposition()==null||!com.tradeflow.contracts.ReturnDispositionPolicy.CONDITIONS.contains(line.disposition())||!identities.add(line.orderItemId()+":"+line.unitNo()))throw invalid("RETURN_RECEIPT_CONFLICT");
  lines.sort(Comparator.comparingLong((ReturnLine line)->Long.parseLong(line.orderItemId())).thenComparingInt(ReturnLine::unitNo));
  for(int attempt=0;attempt<3;attempt++)try{return tx.execute(t->{
   var header=reservations.lock(c.reservationNo());if(header==null||!c.orderNo().equals(header.orderNo())||!"CONFIRMED".equals(header.status())||!"COMMIT".equals(header.decision()))throw invalid("STOCK_NOT_CONFIRMED");
   var old=receipts.find(c.receiptNo());if(old!=null){if(!old.orderNo().equals(c.orderNo())||!old.reservationNo().equals(c.reservationNo())||!old.sourceNo().equals(c.sourceNo())||!receipts.lines(c.receiptNo()).equals(lines))throw invalid("RETURN_RECEIPT_CONFLICT");return old;}
   if(unshipped.byReservation(c.reservationNo())!=null)throw invalid("RESTOCK_BINDING_CONFLICT");
   var quantities=new TreeMap<String,Integer>(Comparator.comparingLong(Long::parseLong));for(var line:lines)quantities.merge(line.skuId(),1,Math::addExact);
   var sold=new HashMap<String,Integer>();for(var item:reservations.items(c.reservationNo()))sold.put(item.skuId(),item.quantity());
   for(var entry:quantities.entrySet())if(!sold.containsKey(entry.getKey())||(long)receipts.returned(c.reservationNo(),entry.getKey())+entry.getValue()>sold.get(entry.getKey()))throw invalid("RETURN_QUANTITY_EXCEEDED");
   receipts.insert(c);for(var line:lines)receipts.item(c,line);
   // 所有实物都保留唯一购买件处置，只有SALEABLE回补可售；残次、待检和报损不增加available。
   for(var entry:quantities.entrySet()) {
    String sku=entry.getKey();var before=products.lockInventory("warehouse_main",sku);
    if(before==null) throw invalid("INVENTORY_NOT_FOUND");
    long saleable=lines.stream().filter(line->sku.equals(line.skuId())&&"SALEABLE".equals(line.disposition())).count();
    if(saleable>0) {
     if(products.adjust("warehouse_main",sku,saleable)!=1) throw invalid("STOCK_CAPACITY_EXCEEDED");
     reservations.ledger(c.receiptNo(),"RETURN",sku,saleable,0,Math.addExact(before.availableQty(),saleable),before.reservedQty());
    }
    for(String condition:List.of("DAMAGED","QUARANTINE")) {
     long quantity=lines.stream().filter(line->sku.equals(line.skuId())&&condition.equals(line.disposition())).count();
     if(quantity>0) {
      conditions.ensure("warehouse_main",sku,condition);
      if(conditions.adjust("warehouse_main",sku,condition,quantity)!=1) throw invalid("STOCK_CAPACITY_EXCEEDED");
     }
    }
   }

   products.audit("order","RETURN_RESTOCK",c.orderNo(),c.receiptNo(),c.receiptNo(),"订单验收退货逐件回补","{\"originalReservationPreserved\":true}");return new RestockView(c.receiptNo(),c.orderNo(),c.reservationNo(),c.sourceNo(),"APPLIED");
  });}catch(org.springframework.dao.DuplicateKeyException e){throw invalid("RETURN_UNIT_ALREADY_RESTOCKED");}catch(org.springframework.dao.TransientDataAccessException e){if(attempt==2)throw new BusinessException(503,"DATABASE_BUSY","回补竞争，请按原收据重试");}
  throw new IllegalStateException("不可达");
 }
 /** 规范化数据库主键仅允许正整数原文，拒绝溢出与前导零以保持明细幂等比较一致。 */
 private boolean positiveId(String id){if(id==null||!id.matches("[1-9][0-9]{0,18}"))return false;try{return Long.parseLong(id)>0;}catch(NumberFormatException failure){return false;}}
 /** 业务冲突必须整体回滚，不能将部分库存变动当作已验收成功。 */
 private BusinessException invalid(String code){return new BusinessException(409,code,"退货回补依据或库存状态不符合约束");}
}
