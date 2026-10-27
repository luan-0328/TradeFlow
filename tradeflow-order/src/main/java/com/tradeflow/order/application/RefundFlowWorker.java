package com.tradeflow.order.application;
import com.tradeflow.common.*;
import com.tradeflow.contracts.*;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.order.infrastructure.*;
import com.tradeflow.order.infrastructure.client.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** B4持久恢复：领取事务先提交，再网络操作，回写前重验任务栅栏；库存和资金各自幂等。 */
@Component
public class RefundFlowWorker {
 private final FulfillmentMapper db;private final FulfillmentService service;private final OrderMapper orders;private final TaskMapper original;private final OrderTransactions tx;private final InventoryGateway inventory;private final ProductClient product;private final RefundClient payment;private final com.tradeflow.messaging.MessageOutbox outbox;private final String token,owner=TraceIds.newId();
 public RefundFlowWorker(FulfillmentMapper db,FulfillmentService service,OrderMapper orders,TaskMapper original,OrderTransactions tx,InventoryGateway inventory,ProductClient product,RefundClient payment,Environment env,com.tradeflow.messaging.MessageOutbox outbox){this.db=db;this.service=service;this.orders=orders;this.original=original;this.tx=tx;this.inventory=inventory;this.product=product;this.payment=payment;this.outbox=outbox;token=env.getRequiredProperty("tradeflow.internal.order-token");}
 /** 兼容B3留下的人工迟到支付记录，先按原任务→意图→订单加锁，再建立固定来源。 */
 @Scheduled(fixedDelay=5000,initialDelay=5000)
 public void admitLate(){for(String no:db.lateOrders())tx.write(()->{original.lockByOrder(no);orders.lockIntent(no);var o=orders.lockOrder(no);if(o!=null&&"LATE_PAYMENT".equals(o.closePhase()))service.lateLocked(o);return null;});}
 /** 每秒最多一个恢复步骤；预算耗尽保留任务和权益，绝不把未知当失败。 */
 @Scheduled(fixedDelay=1000,initialDelay=6000)
 public void recover(){
  var task=tx.write(()->{var t=db.due();if(t==null)return null;String source=(String)t.get("source_no");db.claim(source,owner);t.put("claim_version",num(t,"claim_version")+1);return t;});if(task==null)return;
  String source=(String)task.get("source_no");long claim=num(task,"claim_version");
  try{
   var a=service.authorization(source);if("COMPLETED".equals(a.state())){finish(source,claim,"SUCCEEDED","SETTLE",null,null);return;}
   if(num(task,"attempt_count")>=30){finish(source,claim,"MANUAL_REVIEW",(String)task.get("step"),"RECOVERY_BUDGET_EXHAUSTED",null);return;}
   if("EXECUTING".equals(a.state())&&"LATE_PAYMENT".equals(a.sourceType())){
    String refund=task.get("refund_no")==null?"rfd_"+TradingContracts.sha256(source).substring(0,32):(String)task.get("refund_no");View v;
    try{v=data(payment.query(refund,"order",token));}catch(FeignPolicy.RemoteFailure e){if(e.status()!=404||!"REFUND_NOT_FOUND".equals(e.code()))throw e;v=data(payment.submit("order",token,new Submit(refund,source,a.orderNo())));}
    if(!refund.equals(v.refundNo())||!source.equals(v.sourceNo())||!a.paymentNo().equals(v.paymentNo())||!a.orderNo().equals(v.orderNo())||a.amountCents()!=v.amountCents()||!a.currency().equals(v.currency()))throw FulfillmentService.fail("REFUND_BINDING_CONFLICT");
    // 成功仍等待资金Outbox持久结算；不能用本地查询再发另一笔退款。
    finish(source,claim,"FAILED".equals(v.status())?"MANUAL_REVIEW":"READY","EXECUTE","FAILED".equals(v.status())?"REFUND_FAILED_REQUIRES_REVIEW":"WAIT_REFUND_EVENT",null);return;
   }
   if(!"SETTLING".equals(a.state()))throw FulfillmentService.fail("AUTHORIZATION_STATE_CONFLICT");
   var o=orders.order(a.orderNo());if(o==null)throw FulfillmentService.fail("ORDER_NOT_FOUND");
   if("UNSHIPPED_FULL".equals(a.sourceType())){
    var confirmed=inventory.confirm(o.reservationNo(),o.orderNo());if(!o.reservationNo().equals(confirmed.reservationNo())||!o.orderNo().equals(confirmed.orderNo())||!"CONFIRMED".equals(confirmed.status()))throw FulfillmentService.fail("INVENTORY_INVARIANT_BROKEN");
    String receipt="CAN:"+source;var receiptView=data(product.restock("order",token,new Restock(receipt,o.orderNo(),o.reservationNo(),source)));
    if(!receipt.equals(receiptView.receiptNo())||!o.orderNo().equals(receiptView.orderNo())||!o.reservationNo().equals(receiptView.reservationNo())||!source.equals(receiptView.sourceNo())||!"APPLIED".equals(receiptView.status()))throw FulfillmentService.fail("RESTOCK_BINDING_CONFLICT");
    finish(source,claim,"SUCCEEDED","SETTLE",null,()->{var latest=orders.lockOrder(o.orderNo());var auth=db.lockAuth(source);if("COMPLETED".equals(auth.state()))return;
      if(!"SETTLING".equals(auth.state())||!"PAID".equals(latest.status())||db.cancelled(o.orderNo(),source)!=1)throw FulfillmentService.fail("ENTITLEMENT_INVARIANT_BROKEN");db.transition(source,"SETTLING","COMPLETED");orders.log(o.orderNo(),"PAID","CANCELLED","UNSHIPPED_REFUNDED","SYSTEM",source,"全额退款与独立库存回补已确认");});
   }else{
    var released=inventory.release(o.reservationNo(),o.orderNo());if(!o.reservationNo().equals(released.reservationNo())||!o.orderNo().equals(released.orderNo())||!Set.of("RELEASED","REJECTED").contains(released.status()))throw FulfillmentService.fail("INVENTORY_INVARIANT_BROKEN");
    finish(source,claim,"SUCCEEDED","SETTLE",null,()->{var latest=orders.lockOrder(o.orderNo());var auth=db.lockAuth(source);if("COMPLETED".equals(auth.state()))return;if(!"SETTLING".equals(auth.state())||db.closeLate(o.orderNo())!=1)throw FulfillmentService.fail("ORDER_STATE_CONFLICT");db.transition(source,"SETTLING","COMPLETED");if(!"CLOSED".equals(latest.status()))outbox.enqueue("ORDER",o.orderNo(),orders.currentVersion(o.orderNo()),"OrderClosed",o.orderNo(),source,Map.of("orderNo",o.orderNo(),"reason","LATE_PAYMENT_REFUNDED"));orders.log(o.orderNo(),latest.status(),"CLOSED","LATE_PAYMENT_REFUNDED","SYSTEM",source,"迟到支付已退款并确认原库存释放");});
   }
  }catch(Exception e){String code=e instanceof FeignPolicy.RemoteFailure f?f.code():e instanceof BusinessException f?f.code():"RECOVERY_DEPENDENCY_ERROR";boolean manual=Set.of("REFUND_BINDING_CONFLICT","RESTOCK_BINDING_CONFLICT","INVENTORY_INVARIANT_BROKEN","PAYMENT_MANUAL_REVIEW","REFUND_MANUAL_REVIEW","AUTHORIZATION_STATE_CONFLICT","ORDER_STATE_CONFLICT","ENTITLEMENT_INVARIANT_BROKEN").contains(code);finish(source,claim,manual?"MANUAL_REVIEW":"READY",(String)task.get("step"),code,null);}
 }
 /** 本地应用与完成围栏同事务，旧执行者即使RPC成功也不能覆盖新租约。 */
 private void finish(String source,long claim,String state,String step,String error,Runnable action){tx.write(()->{if(db.fence(source,claim)==null)return null;if(action!=null)action.run();if(db.taskResult(source,claim,state,step,error)!=1)throw new IllegalStateException("退款恢复栅栏失效");return null;});}
 /** 信封成功与非空数据共同构成RPC协议，不把HTTP200当资金成功。 */
 private <T>T data(ApiResponse<T> response){if(response==null||!"OK".equals(response.code())||response.data()==null)throw FulfillmentService.fail("INVALID_REMOTE_RESPONSE");return response.data();}
 private long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
}
