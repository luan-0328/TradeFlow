package com.tradeflow.order.application;
import com.tradeflow.contracts.*;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.order.infrastructure.*;
import java.util.*;
import org.springframework.stereotype.Service;
/** 购买件权益用例：所有分配由订单快照核验，网络调用不得放入本地事务。 */
@Service public class EntitlementService {
 private final OrderMapper orders;private final FulfillmentMapper db;private final EntitlementMapper b6;private final OrderTransactions tx;
 public EntitlementService(OrderMapper orders,FulfillmentMapper db,EntitlementMapper b6,OrderTransactions tx){this.orders=orders;this.db=db;this.b6=b6;this.tx=tx;}
 /** 预览使用同一订单锁取得一致快照；不占用权益，提交时必须重新校验期限和占用。 */
 public Preview preview(PreviewRequest r){if(r==null)throw fail("INVALID_ARGUMENT");validatePreview(r.orderNo(),r.type());return tx.write(()->{var o=orders.lockOrder(r.orderNo());if(o==null||!o.userId().equals(r.userId()))throw fail("ORDER_NOT_FOUND");db.lockItems(o.orderNo());var units=db.lockUnits(o.orderNo());if("UNSHIPPED_FULL".equals(r.type())){if(!"PAID".equals(o.status())||!"NONE".equals(o.fulfillmentLock()))throw fail("ORDER_NOT_ELIGIBLE");for(var u:units)if(u.get("active_aftersale_no")!=null||n(u,"succeeded_refund_cents")!=0||n(u,"reserved_refund_cents")!=0||!"NONE".equals(u.get("return_state")))throw fail("ENTITLEMENT_CONFLICT");}else eligible(o.orderNo());var allocations="UNSHIPPED_FULL".equals(r.type())?full(r.allocations(),units):resolve(r.allocations(),units,r.type(),true);return new Preview(o.orderNo(),o.userId(),o.merchantId(),r.type(),total(allocations),o.currency(),allocations,units.stream().map(u->new EntitlementUnit(Long.toString(n(u,"order_item_id")),(int)n(u,"unit_no"),Long.toString(n(u,"sku_id")),n(u,"paid_amount_cents"),n(u,"succeeded_refund_cents"),n(u,"reserved_refund_cents"),remaining(u),(String)u.get("active_aftersale_no"),(String)u.get("return_state"))).toList());});}
 /** 逐件独占预留、规范摘要、不可变明细与行汇总同事务；重试先比首次内容，不重新判断过期。 */
 public Authorization reserve(Reserve r){validate(r.orderNo(),r.type());if(r.aftersaleNo()==null||!r.aftersaleNo().matches("as_[a-f0-9]{32}"))throw fail("INVALID_SOURCE_NO");var canonical=canonical(r.allocations());String digest=digest(canonical);
  return tx.write(()->{var o=orders.lockOrder(r.orderNo());if(o==null||!o.userId().equals(r.userId()))throw fail("ORDER_NOT_FOUND");db.lockItems(o.orderNo());var units=db.lockUnits(o.orderNo());var old=db.lockAuth(r.aftersaleNo());if(old!=null){if("TERMINATED".equals(old.state()))throw fail("AUTHORIZATION_TERMINATED");if(!old.orderNo().equals(r.orderNo())||!old.userId().equals(r.userId())||!old.sourceType().equals(r.type())||!digest.equals(b6.digest(r.aftersaleNo())))throw fail("AUTHORIZATION_CONFLICT");return old;}eligible(o.orderNo());var allocations=resolve(canonical,units,r.type(),false);var a=new Authorization(r.aftersaleNo(),r.type(),o.orderNo(),o.paymentNo(),o.userId(),o.merchantId(),total(allocations),o.currency(),"RESERVED",1);db.insertAuth(a);b6.digestSave(a.sourceNo(),digest);for(var allocation:allocations){var unit=find(units,allocation);if(b6.reserve(n(unit,"id"),a.sourceNo(),allocation.amountCents(),"RETURN_REFUND".equals(r.type())?"RESERVED":"NONE")!=1)throw fail("ENTITLEMENT_CONFLICT");b6.allocation(a.sourceNo(),allocation);}db.releaseItems(o.orderNo());orders.audit("SERVICE","aftersale","RESERVE_UNIT_ENTITLEMENT",o.orderNo(),a.sourceNo(),a.sourceNo(),"逐件金额权益预留","{\"immutableAllocation\":true}");return a;});}
 /** 验收认可只建立退款前提；逐件处置另行冻结，不能把不可售货品自动增加可售库存。 */
 public Authorization accept(String source,ReturnAccept r) {
  FulfillmentService.safeSource(source);
  if(r==null||!("RET:"+source).equals(r.receiptNo())) throw fail("RETURN_RECEIPT_CONFLICT");
  FulfillmentService.safeOrder(r.orderNo());
  return tx.write(()->{
   var order=orders.lockOrder(r.orderNo());
   if(order==null) throw fail("ORDER_NOT_FOUND");
   db.lockItems(order.orderNo());db.lockUnits(order.orderNo());
   var authorization=db.lockAuth(source);
   if(authorization==null||!authorization.orderNo().equals(r.orderNo())||!"RETURN_REFUND".equals(authorization.sourceType()))
    throw fail("AUTHORIZATION_CONFLICT");
   var dispositions=com.tradeflow.contracts.ReturnDispositionPolicy.match(r.dispositions(),b6.allocations(source));
   if(b6.receipt(source)!=null) {
    if(!dispositions.equals(b6.dispositions(source))) throw fail("RETURN_DISPOSITION_CONFLICT");
    return authorization;
   }
   if(!"RESERVED".equals(authorization.state())) throw fail("AUTHORIZATION_NOT_RESERVED");
   b6.receive(r.receiptNo(),source);
   for(var disposition:dispositions) b6.disposition(source,disposition);
   if(b6.receiveUnits(source)!=dispositions.size()) throw fail("ENTITLEMENT_INVARIANT_BROKEN");
   b6.task(source);db.releaseItems(order.orderNo());
   orders.audit("SERVICE","aftersale","ACCEPT_RETURN",order.orderNo(),source,source,
     "认可退货并冻结逐件库存去向，建立独立处置任务","{\"irreversible\":true}");
   return authorization;
  });
 }
 /** 商品只读取订单权威验收收据；金额授权、商品绑定与实物处置必须完整一致。 */
 public ReturnReceipt receipt(String no) {
  if(no==null||!no.matches("RET:as_[a-f0-9]{32}")) throw fail("INVALID_RETURN_RECEIPT");
  String source=no.substring(4);var receipt=b6.receipt(source);
  if(receipt==null) throw new com.tradeflow.common.BusinessException(404,"RETURN_RECEIPT_NOT_FOUND","验收收据不存在");
  var authorization=db.auth(source);var order=orders.order(authorization.orderNo());
  var dispositions=com.tradeflow.contracts.ReturnDispositionPolicy.match(b6.dispositions(source),b6.allocations(source));
  var byUnit=new HashMap<String,String>();
  for(var disposition:dispositions) byUnit.put(disposition.orderItemId()+":"+disposition.unitNo(),disposition.disposition());
  var lines=b6.lines(source).stream().map(line->{
   String item=(String)line.get("order_item_id");int unit=((Number)line.get("unit_no")).intValue();
   return new ReturnLine(item,unit,(String)line.get("sku_id"),byUnit.get(item+":"+unit));
  }).toList();
  if(lines.size()!=dispositions.size()) throw fail("ENTITLEMENT_INVARIANT_BROKEN");
  return new ReturnReceipt(no,source,order.orderNo(),order.reservationNo(),(String)receipt.get("status"),lines);
 }
 /** 成功结算前逐件比对首次明细、当前来源、金额和实物状态；旧B4无明细仅允许原整单全额，任何缺件或零预留均整体回滚。 */
 int settlementCount(Authorization a,List<Map<String,Object>> units){var expected=b6.allocations(a.sourceNo());if(expected.isEmpty()){if(!"UNSHIPPED_FULL".equals(a.sourceType())||b6.digest(a.sourceNo())!=null)throw fail("ENTITLEMENT_INVARIANT_BROKEN");expected=full(List.of(),units);}else if(!digest(expected).equals(b6.digest(a.sourceNo())))throw fail("ENTITLEMENT_INVARIANT_BROKEN");if(total(expected)!=a.amountCents())throw fail("ENTITLEMENT_INVARIANT_BROKEN");var bound=units.stream().filter(u->a.sourceNo().equals(u.get("active_aftersale_no"))).toList();if(bound.size()!=expected.size())throw fail("ENTITLEMENT_INVARIANT_BROKEN");for(var allocation:expected){var u=find(units,allocation);String state="RETURN_REFUND".equals(a.sourceType())?"RECEIVED":"NONE";if(!a.sourceNo().equals(u.get("active_aftersale_no"))||n(u,"reserved_refund_cents")!=allocation.amountCents()||n(u,"reserved_refund_cents")<=0||!state.equals(u.get("return_state"))||("UNSHIPPED_FULL".equals(a.sourceType())&&(n(u,"succeeded_refund_cents")!=0||n(u,"paid_amount_cents")!=allocation.amountCents())))throw fail("ENTITLEMENT_INVARIANT_BROKEN");}return expected.size();}
 /** 旧未发货整单仍接受空分配；显式提交必须覆盖全部原购买件和原实付，重复不能改金额。 */
 List<Allocation> full(List<Allocation> input,List<Map<String,Object>> units){var expected=units.stream().map(u->new Allocation(Long.toString(n(u,"order_item_id")),(int)n(u,"unit_no"),n(u,"paid_amount_cents"))).toList();if(expected.isEmpty())throw fail("ENTITLEMENT_INVARIANT_BROKEN");var all=canonical(expected);if(input!=null&&!input.isEmpty()&&!all.equals(canonical(input)))throw fail("ALLOCATION_CONFLICT");return all;}
 /** 整单预览沿用未发货规则，另外两类固定七日窗口。 */
 private void validatePreview(String no,String type){if("UNSHIPPED_FULL".equals(type))FulfillmentService.safeOrder(no);else validate(no,type);}
 /** 规范化仅接受正整数主键和金额，排序不影响幂等摘要，重复件直接拒绝。 */
 private List<Allocation> canonical(List<Allocation> list){if(list==null||list.isEmpty()||list.size()>1980)throw fail("ALLOCATION_REQUIRED");var result=new ArrayList<Allocation>();var keys=new HashSet<String>();for(var a:list){if(a==null||a.orderItemId()==null||!a.orderItemId().matches("[1-9][0-9]{0,18}")||a.unitNo()<1||a.unitNo()>99||a.amountCents()<1||a.amountCents()>100000000)throw fail("INVALID_ALLOCATION");long id;try{id=Long.parseLong(a.orderItemId());}catch(NumberFormatException e){throw fail("INVALID_ALLOCATION");}if(!keys.add(id+":"+a.unitNo()))throw fail("DUPLICATE_UNIT");result.add(a);}result.sort(Comparator.comparingLong((Allocation a)->Long.parseLong(a.orderItemId())).thenComparingInt(Allocation::unitNo));return List.copyOf(result);}
 /** 空预览只选当前可申请件；提交不得空分配，退货必须申请剩余全部。 */
 private List<Allocation> resolve(List<Allocation> input,List<Map<String,Object>> units,String type,boolean preview){if(preview&&(input==null||input.isEmpty())){input=units.stream().filter(u->u.get("active_aftersale_no")==null&&"NONE".equals(u.get("return_state"))&&remaining(u)>0).map(u->new Allocation(Long.toString(n(u,"order_item_id")),(int)n(u,"unit_no"),remaining(u))).toList();if(input.isEmpty())return List.of();}var list=canonical(input);for(var a:list){var u=find(units,a);if(u.get("active_aftersale_no")!=null||n(u,"reserved_refund_cents")!=0||!"NONE".equals(u.get("return_state"))||a.amountCents()>remaining(u)||("RETURN_REFUND".equals(type)&&a.amountCents()!=remaining(u)))throw fail("ENTITLEMENT_CONFLICT");}return list;}
 /** 单件查找只依据订单内锁定的权威集合。 */
 private Map<String,Object> find(List<Map<String,Object>> units,Allocation a){return units.stream().filter(u->n(u,"order_item_id")==Long.parseLong(a.orderItemId())&&n(u,"unit_no")==a.unitNo()).findFirst().orElseThrow(()->fail("INVALID_PURCHASE_UNIT"));}
 /** 固定分隔符和规范数字生成与JSON编码无关的稳定摘要。 */
 String digest(List<Allocation> list){StringBuilder b=new StringBuilder();for(var a:list)b.append(a.orderItemId()).append(':').append(a.unitNo()).append(':').append(a.amountCents()).append(';');return TradingContracts.sha256(b.toString());}
 /** 汇总金额用整数分并防越界，授权总额不得超过系统订单上限。 */
 private long total(List<Allocation> list){long sum=0;for(var a:list)sum=Math.addExact(sum,a.amountCents());if(sum>100000000)throw fail("INVALID_AMOUNT");return sum;}
 /** 剩余金额同时扣除成功退款和未决预留，避免不同来源重复占款。 */
 private long remaining(Map<String,Object> u){return n(u,"paid_amount_cents")-n(u,"succeeded_refund_cents")-n(u,"reserved_refund_cents");}
 /** 期限判断使用订单数据库UTC，已完成恰满七天拒绝新申请。 */
 private void eligible(String no){if(b6.eligible(no)!=1)throw fail("AFTERSALE_WINDOW_EXPIRED");}
 /** 新类型仅允许已发货金额退款和退款型退货，其他类型由旧流程处理。 */
 private void validate(String no,String type){FulfillmentService.safeOrder(no);if(!Set.of("REFUND_ONLY","RETURN_REFUND").contains(type==null?"":type))throw fail("AFTERSALE_TYPE_NOT_ENABLED");}
 private static long n(Map<String,Object> u,String key){return ((Number)u.get(key)).longValue();}
 private static com.tradeflow.common.BusinessException fail(String code){return FulfillmentService.fail(code);}
}
