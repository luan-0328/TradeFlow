package com.tradeflow.product.application;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.product.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** 已确认销售只能以独立UNSHIPPED_CANCEL依据回补，不能把CONFIRMED改成RELEASED。 */
@Service public class RestockService {
 private final ReservationMapper reservations;private final ProductMapper products;private final RestockMapper receipts;private final TransactionTemplate tx;private final ReturnRestockMapper returns;
 public RestockService(ReservationMapper reservations,ProductMapper products,RestockMapper receipts,ReturnRestockMapper returns,PlatformTransactionManager manager){this.reservations=reservations;this.products=products;this.receipts=receipts;this.returns=returns;tx=new TransactionTemplate(manager);}
 /** order专用凭据已由Controller验证；数量只取原已确认预占，不接受远端自报SKU/数量。 */
 public RestockView restock(Restock c){
  if(c==null||c.orderNo()==null||!c.orderNo().matches("ord_[a-f0-9]{32}")||c.reservationNo()==null||!c.reservationNo().matches("res_[a-f0-9]{32}")||c.sourceNo()==null||!c.sourceNo().matches("as_[a-f0-9]{32}")||!("CAN:"+c.sourceNo()).equals(c.receiptNo()))throw invalid("INVALID_RESTOCK");
  for(int attempt=0;attempt<3;attempt++)try{return tx.execute(t->{
   var header=reservations.lock(c.reservationNo());if(header==null||!c.orderNo().equals(header.orderNo())||!"CONFIRMED".equals(header.status())||!"COMMIT".equals(header.decision()))throw invalid("STOCK_NOT_CONFIRMED");
   var old=receipts.byReservation(c.reservationNo());if(old!=null){if(!old.receiptNo().equals(c.receiptNo())||!old.sourceNo().equals(c.sourceNo())||!old.orderNo().equals(c.orderNo()))throw invalid("RESTOCK_BINDING_CONFLICT");return old;}
   if(returns.count(c.reservationNo())>0)throw invalid("RESTOCK_BINDING_CONFLICT");
   var items=reservations.items(c.reservationNo());if(items.isEmpty())throw invalid("INVENTORY_INVARIANT_BROKEN");
   for(var i:items){var before=products.lockInventory("warehouse_main",i.skuId());if(before==null||Math.addExact(Math.addExact(before.availableQty(),before.reservedQty()),i.quantity())>1000000000||products.adjust("warehouse_main",i.skuId(),i.quantity())!=1)throw invalid("STOCK_CAPACITY_EXCEEDED");reservations.ledger(c.receiptNo(),"UNSHIPPED_CANCEL",i.skuId(),i.quantity(),0,Math.addExact(before.availableQty(),i.quantity()),before.reservedQty());}
   receipts.insert(c);products.audit("order","UNSHIPPED_CANCEL",c.orderNo(),c.receiptNo(),c.receiptNo(),"未发货退款成功后销售回补","{\"originalReservationPreserved\":true}");return new RestockView(c.receiptNo(),c.orderNo(),c.reservationNo(),c.sourceNo(),"APPLIED");
  });}catch(org.springframework.dao.TransientDataAccessException e){if(attempt==2)throw new BusinessException(503,"DATABASE_BUSY","回补竞争，请按原收据查询或重试");}
  throw new IllegalStateException("不可达");
 }
 private BusinessException invalid(String code){return new BusinessException(409,code,"回补依据或库存状态不符合约束");}
}
