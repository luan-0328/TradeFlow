package com.tradeflow.order.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.*;
import com.tradeflow.contracts.*;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.order.infrastructure.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;

/** 发货、收货和商品退款权益：所有远程身份查询先于订单短事务。 */
@Service
public class FulfillmentService {
 private final OrderService query;private final EntitlementService entitlement;private final EntitlementMapper b6;private final OrderMapper orders;private final FulfillmentMapper db;private final TaskMapper tasks;private final OrderTransactions tx;private final OrderAuthority authority;private final ObjectMapper json;
 public FulfillmentService(OrderMapper orders,FulfillmentMapper db,TaskMapper tasks,OrderTransactions tx,OrderAuthority authority,ObjectMapper json,EntitlementService entitlement,EntitlementMapper b6,OrderService query){this.query=query;this.entitlement=entitlement;this.b6=b6;this.orders=orders;this.db=db;this.tasks=tasks;this.tx=tx;this.authority=authority;this.json=json;}
 /** 商家只读自己的订单分页，不能通过请求头自报商家归属。 */
 public List<Map<String,Object>> merchantOrders(Jwt jwt,int page,int size){var identity=authority.merchant(jwt);if(page<1||size<1||size>100)throw fail("INVALID_PAGE");return tx.read(()->db.merchantList(identity.merchantIds().get(0),size,(long)(page-1)*size).stream().map(row->{var result=new LinkedHashMap<String,Object>(row);result.put("allowedActions",shipActions((String)row.get("status"),(String)row.get("stock_sync_status"),(String)row.get("fulfillment_lock")));return (Map<String,Object>)result;}).toList());}
 /** 最新商家身份在事务外查询，随后本库一致快照验证资源归属；详情只返回商品快照和安全物流。 */
 public com.tradeflow.order.api.OrderModels.OrderView merchantDetail(Jwt jwt,String no){var auth=authority.merchant(jwt);safeOrder(no);return tx.read(()->{var h=orders.order(no);if(h==null||!auth.merchantIds().contains(h.merchantId()))throw missing();var v=query.view(h,false,false);return new com.tradeflow.order.api.OrderModels.OrderView(v.orderNo(),v.paymentNo(),v.status(),v.stockSyncStatus(),v.fulfillmentLock(),v.amountCents(),v.currency(),v.payDeadline(),v.items(),shipActions(h.status(),h.stockSyncStatus(),h.fulfillmentLock()),v.createdAt(),v.shipment());});}
 /** 收货地址仅为所属商家当前履约按需披露；读取和不含明文的审计同事务提交。 */
 public com.tradeflow.order.api.OrderModels.ShippingAddress shippingAddress(Jwt jwt,String no,String request,String trace){
  var auth=authority.merchant(jwt);safeOrder(no);
  // 最新身份查询在事务外；订单锁将状态核验、读取和审计串行，关闭/售后抢先时不披露地址。
  return tx.write(()->{var o=orders.lockOrder(no);if(o==null||!auth.merchantIds().contains(o.merchantId()))throw missing();
   if(!addressAllowed(o.status(),o.stockSyncStatus(),o.fulfillmentLock()))throw fail("ADDRESS_NOT_AVAILABLE");
   var address=orders.shippingAddress(no);if(address==null)throw missing();
   orders.audit("MERCHANT",jwt.getSubject(),"VIEW_SHIPPING_ADDRESS",no,request,trace,"商家履约查看收货资料","{\"purpose\":\"FULFILLMENT\"}");return address;});
 }
 /** 仅已确认库存且未被售后锁住的待发货/运输订单允许查看，完成或关闭后不再披露。 */
 private boolean addressAllowed(String status,String stock,String lock){return Set.of("PAID","SHIPPED").contains(status)&&"CONFIRMED".equals(stock)&&"NONE".equals(lock);}
 /** 地址与发货动作都是提示；提交或查看仍在订单锁内核验当前事实。 */
 private List<String> shipActions(String status,String stock,String lock){var actions=new ArrayList<String>();if(addressAllowed(status,stock,lock))actions.add("VIEW_SHIPPING_ADDRESS");if("PAID".equals(status)&&"CONFIRMED".equals(stock)&&"NONE".equals(lock))actions.add("SHIP");return List.copyOf(actions);}
 /** 一次整单发货，同订单同物流重放幂等，售后锁与库存未确认均拒绝。 */
 public Map<String,Object> ship(Jwt jwt,String no,String key,String carrier,String tracking){
  var auth=authority.merchant(jwt);authority.requireTrading(auth.merchantIds().get(0));key(key);safeOrder(no);
  if(carrier==null||carrier.isBlank()||carrier.length()>80||tracking==null||!tracking.matches("[A-Za-z0-9_-]{1,80}"))throw fail("INVALID_SHIPMENT");String hash=hash(List.of(carrier.strip(),tracking));
  return tx.write(()->{bindOperation("MERCHANT",jwt.getSubject(),"SHIP",key,no,hash(List.of(no,carrier.strip(),tracking)));var o=orders.lockOrder(no);if(o==null||!auth.merchantIds().contains(o.merchantId()))throw missing();
   var old=db.shipmentByOrder(no);if(old!=null){if(!hash.equals(old.get("request_hash")))throw fail("SHIPMENT_CONFLICT");return shipmentView(no,old);}
   if(db.ship(no)!=1)throw fail("ORDER_NOT_SHIPPABLE");db.shipment(no,carrier.strip(),tracking,jwt.getSubject(),hash);orders.log(no,"PAID","SHIPPED","SHIP",jwt.getSubject(),key,"商家整单发货");
   orders.audit("MERCHANT",jwt.getSubject(),"SHIP",no,key,key,"整单发货","{\"logistics\":true}");return shipmentView(no,db.shipmentByOrder(no));
  });
 }
 /** 用户仅确认本人已发货订单，重复返回终态，不重复记录迁移。 */
 public Map<String,String> receive(Jwt jwt,String no,String key){authority.authenticate(jwt,false);key(key);safeOrder(no);return tx.write(()->{bindOperation("USER",jwt.getSubject(),"RECEIVE",key,no,hash(no));var o=orders.lockOrder(no);if(o==null||!jwt.getSubject().equals(o.userId()))throw missing();
  if("COMPLETED".equals(o.status()))return Map.of("orderNo",no,"status","COMPLETED");if(db.receive(no)!=1)throw fail("ORDER_NOT_RECEIVABLE");orders.log(no,"SHIPPED","COMPLETED","RECEIVE",jwt.getSubject(),key,"本人确认收货");return Map.of("orderNo",no,"status","COMPLETED");});}
 /** 售后意图查询由调用方验证用户归属，返回订单权威身份与金额。 */
 public PaymentContracts.Intent intent(String no){safeOrder(no);var o=orders.order(no);if(o==null)throw missing();return new PaymentContracts.Intent(no,o.paymentNo(),o.userId(),o.totalAmountCents(),o.currency(),o.payDeadline(),o.status(),o.merchantId());}
 /** B4只允许未发货整单全额：锁订单、商品行、全部购买件后保存来源，整单回滚保证不部分占用。 */
 public Authorization reserve(Reserve input){
  if(input!=null&&("REFUND_ONLY".equals(input.type())||"RETURN_REFUND".equals(input.type())))return entitlement.reserve(input);
  if(input==null||input.aftersaleNo()==null||!input.aftersaleNo().matches("as_[a-f0-9]{32}")||!"UNSHIPPED_FULL".equals(input.type()))throw fail("AFTERSALE_TYPE_NOT_ENABLED");safeOrder(input.orderNo());
  return tx.write(()->{var o=orders.lockOrder(input.orderNo());if(o==null||!o.userId().equals(input.userId()))throw missing();
   var items=db.lockItems(o.orderNo());var units=db.lockUnits(o.orderNo());var allocations=entitlement.full(input.allocations(),units);String allocationHash=entitlement.digest(allocations);var old=db.lockAuth(input.aftersaleNo());
   if(old!=null){if("TERMINATED".equals(old.state()))throw fail("AUTHORIZATION_TERMINATED");if(!old.orderNo().equals(o.orderNo())||!old.userId().equals(input.userId())||!old.sourceType().equals(input.type())||(b6.digest(old.sourceNo())!=null&&!allocationHash.equals(b6.digest(old.sourceNo()))))throw fail("AUTHORIZATION_CONFLICT");return old;}
   if(!"PAID".equals(o.status())||!"NONE".equals(o.fulfillmentLock())||units.isEmpty())throw fail("ORDER_NOT_ELIGIBLE");
   long total=0;for(var u:units){if(u.get("active_aftersale_no")!=null||num(u,"reserved_refund_cents")!=0||num(u,"succeeded_refund_cents")!=0||!"NONE".equals(u.get("return_state")))throw fail("ENTITLEMENT_CONFLICT");total=Math.addExact(total,num(u,"paid_amount_cents"));}
   if(total!=o.totalAmountCents())throw fail("ENTITLEMENT_INVARIANT_BROKEN");
   if(db.hold(o.orderNo(),input.aftersaleNo())!=1)throw fail("ORDER_NOT_ELIGIBLE");
   for(var u:units)if(db.reserveUnit(num(u,"id"),input.aftersaleNo())!=1)throw fail("ENTITLEMENT_CONFLICT");if(db.reserveItems(o.orderNo())!=items.size())throw fail("ENTITLEMENT_INVARIANT_BROKEN");
   var a=new Authorization(input.aftersaleNo(),input.type(),o.orderNo(),o.paymentNo(),o.userId(),o.merchantId(),total,o.currency(),"RESERVED",1);db.insertAuth(a);b6.digestSave(a.sourceNo(),allocationHash);for(var allocation:allocations)b6.allocation(a.sourceNo(),allocation);
   orders.audit("SERVICE","aftersale","RESERVE_ENTITLEMENT",o.orderNo(),a.sourceNo(),a.sourceNo(),"未发货整单权益预留","{\"fulfillmentLocked\":true}");return a;
  });
 }
 /** 激活与终止锁同订单及授权；激活后普通终止不能释放商品权益。 */
 public Authorization change(String source,Change input,boolean activate){safeSource(source);if(!source.startsWith("as_"))throw fail("SOURCE_FORBIDDEN");if(input==null||input.reason()==null||input.reason().isBlank()||input.reason().length()>500)throw fail("REASON_REQUIRED");safeOrder(input.orderNo());
  return tx.write(()->{var o=orders.lockOrder(input.orderNo());if(o==null)throw missing();db.lockItems(o.orderNo());db.lockUnits(o.orderNo());var a=db.lockAuth(source);
   if(a==null){if(activate)throw missing();
    // 终止先到留下墓碑，阻止迟到授权RPC重新占用权益。
    a=new Authorization(source,"TOMBSTONE",o.orderNo(),o.paymentNo(),o.userId(),o.merchantId(),o.totalAmountCents(),o.currency(),"TERMINATED",1);db.insertAuth(a);return a;}
   if(!a.orderNo().equals(o.orderNo())||!Set.of("UNSHIPPED_FULL","REFUND_ONLY","RETURN_REFUND","TOMBSTONE").contains(a.sourceType()))throw fail("AUTHORIZATION_CONFLICT");
   if(activate){if("RETURN_REFUND".equals(a.sourceType())&&b6.receipt(source)==null)throw fail("RETURN_NOT_RECEIVED");if("TERMINATED".equals(a.state()))throw fail("AUTHORIZATION_TERMINATED");if("RESERVED".equals(a.state()))db.transition(source,"RESERVED","EXECUTING");}
   else{if(b6.receipt(source)!=null)throw fail("RETURN_ALREADY_RECEIVED");if(!Set.of("RESERVED","TERMINATED").contains(a.state()))throw fail("AUTHORIZATION_EXECUTING");if("RESERVED".equals(a.state())){db.releaseUnits(o.orderNo(),source);db.releaseItems(o.orderNo());if("UNSHIPPED_FULL".equals(a.sourceType())&&db.unhold(o.orderNo(),source)!=1)throw fail("ENTITLEMENT_INVARIANT_BROKEN");db.transition(source,"RESERVED","TERMINATED");}}
   orders.audit("SERVICE","aftersale",activate?"ACTIVATE_REFUND":"TERMINATE_ENTITLEMENT",o.orderNo(),source,source,input.reason(),"{\"authorityChanged\":true}");return db.auth(source);
  });
 }
 /** 查询来源绑定，支付只使用EXECUTING创建新执行；不泄漏其他库实体。 */
 public Authorization authorization(String source){safeSource(source);var a=db.auth(source);if(a==null)throw new BusinessException(404,"AUTHORIZATION_NOT_FOUND","退款授权不存在");return a;}
 /** 原支付成功而关闭已赢时，固定CLOSE来源生成不可撤销整单授权和执行任务。调用方已锁订单。 */
 public void lateLocked(com.tradeflow.order.domain.OrderRows.Header o){String source="CLOSE:"+o.orderNo();var a=db.lockAuth(source);if(a!=null)return;
  db.insertAuth(new Authorization(source,"LATE_PAYMENT",o.orderNo(),o.paymentNo(),o.userId(),o.merchantId(),o.totalAmountCents(),o.currency(),"EXECUTING",1));db.task(source,"EXECUTE");
  orders.audit("SYSTEM","order","AUTHORIZE_LATE_REFUND",o.orderNo(),source,source,"关闭先成立，原号退款","{\"refundRequired\":true}");}
 /** 退款终态证据、唯一执行绑定、逐件结算和恢复任务同事务；失败只记事实，不释放订单权益。 */
 public Map<String,Boolean> result(String no,Event event){safeOrder(no);if(event==null||event.fact()==null||event.eventId()==null||!event.eventId().matches("[A-Za-z0-9_:-]{1,64}"))throw fail("INVALID_REFUND_FACT");
  var f=event.fact();safeSource(f.sourceNo());if(!no.equals(f.orderNo())||f.refundNo()==null||!f.refundNo().matches("rfd_[a-f0-9]{32}")||f.status()==null||!Set.of("SUCCEEDED","FAILED").contains(f.status())||f.channelVersion()<1||f.updatedAt()==null||("SUCCEEDED".equals(f.status())&&f.succeededAt()==null))throw fail("INVALID_REFUND_FACT");String digest=hash(f);
  return tx.write(()->{db.lockRefundTask(f.sourceNo());tasks.lockByOrder(no);orders.lockIntent(no);var o=orders.lockOrder(no);if(o==null)throw missing();db.lockItems(no);var settlementUnits=db.lockUnits(no);var a=db.lockAuth(f.sourceNo());
   if(a==null||!a.orderNo().equals(no)||!o.paymentNo().equals(f.paymentNo())||!a.paymentNo().equals(f.paymentNo())||a.amountCents()!=f.amountCents()||!a.currency().equals(f.currency()))throw fail("REFUND_BINDING_CONFLICT");String old=db.factHash(event.eventId());if(old!=null){if(!old.equals(digest))throw fail("EVENT_CONFLICT");return Map.of("ack",true);}
   if("SUCCEEDED".equals(f.status())){
    String settled=db.settled(a.sourceNo());if(settled!=null&&!settled.equals(f.refundNo()))throw fail("REFUND_SETTLEMENT_CONFLICT");
    if("EXECUTING".equals(a.state())){int expectedCount="LATE_PAYMENT".equals(a.sourceType())?0:entitlement.settlementCount(a,settlementUnits);if(db.settling(a.sourceNo(),f.refundNo())!=1)throw fail("REFUND_SETTLEMENT_CONFLICT");if("UNSHIPPED_FULL".equals(a.sourceType())){if(db.settleUnits(no,a.sourceNo())!=expectedCount)throw fail("ENTITLEMENT_INVARIANT_BROKEN");db.settleItems(no);db.task(a.sourceNo(),"SETTLE");}else if(Set.of("REFUND_ONLY","RETURN_REFUND").contains(a.sourceType())){if(db.settleUnits(no,a.sourceNo())!=expectedCount)throw fail("ENTITLEMENT_INVARIANT_BROKEN");db.settleItems(no);b6.complete(a.sourceNo());}else db.beginSettlement(a.sourceNo());}
    else if(!Set.of("SETTLING","COMPLETED").contains(a.state()))throw fail("AUTHORIZATION_NOT_EXECUTING");
   }
   db.fact(event.eventId(),f.sourceNo(),f.refundNo(),digest,f.status());return Map.of("ack",true);
  });
 }
 /** 幂等请求与真实状态迁移同事务；失败完整回滚，不占用成功键。 */
 private void bindOperation(String type,String actor,String op,String key,String no,String digest){db.claimOperation(type,actor,op,key,digest,no);var old=db.operation(type,actor,op,key);if(!no.equals(old.get("business_no"))||!digest.equals(old.get("request_hash")))throw fail("IDEMPOTENCY_CONFLICT");}
 /** 外部物流只输出业务字段，不泄漏内部摘要和操作者记录。 */
 private Map<String,Object> shipmentView(String no,Map<String,Object> row){return Map.of("orderNo",no,"carrier",row.get("carrier"),"trackingNo",row.get("tracking_no"),"status","SHIPPED");}
 /** 固定业务号语法避免内部调用路径注入。 */
 public static void safeOrder(String no){if(no==null||!no.matches("ord_[a-f0-9]{32}"))throw fail("INVALID_ORDER_NO");}
 public static void safeSource(String no){if(no==null||!no.matches("(as_[a-f0-9]{32}|CLOSE:ord_[a-f0-9]{32})"))throw fail("INVALID_SOURCE_NO");}
 private static void key(String key){if(key==null||!key.matches("[A-Za-z0-9._:-]{1,64}"))throw fail("INVALID_IDEMPOTENCY_KEY");}
 private String hash(Object value){try{return TradingContracts.sha256(json.writeValueAsString(value));}catch(Exception e){throw fail("INVALID_ARGUMENT");}}
 static long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
 static BusinessException fail(String code){return new BusinessException(409,code,"当前业务状态不允许该操作，请刷新查询");}
 private static BusinessException missing(){return new BusinessException(404,"ORDER_NOT_FOUND","订单或授权不存在");}
}
