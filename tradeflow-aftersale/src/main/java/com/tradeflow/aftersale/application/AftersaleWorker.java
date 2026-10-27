package com.tradeflow.aftersale.application;
import com.tradeflow.aftersale.domain.Aftersale;
import com.tradeflow.aftersale.infrastructure.*;
import com.tradeflow.aftersale.infrastructure.AftersaleStore.Claim;
import com.tradeflow.aftersale.infrastructure.FeignPolicy.RemoteFailure;
import com.tradeflow.aftersale.infrastructure.client.*;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.RefundContracts.*;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 售后恢复编排：短事务领取→事务外权威RPC→短事务围栏应用，未知永不换号或释放权益。 */
@Component
public class AftersaleWorker {
 private final AftersaleStore db;private final TransactionTemplate tx;private final OrderClient orders;private final RefundClient refunds;private final String token;private final com.tradeflow.messaging.MessageOutbox outbox;
 public AftersaleWorker(AftersaleStore db,TransactionTemplate tx,OrderClient orders,RefundClient refunds,Environment env,com.tradeflow.messaging.MessageOutbox outbox){this.db=db;this.tx=tx;this.orders=orders;this.refunds=refunds;this.outbox=outbox;token=env.getRequiredProperty("tradeflow.internal.aftersale-token");}
 private record Result(String state,Authorization authorization,View refund,boolean done,String error){}
 /** 每轮有限领取，租约内仅执行固定数量HTTP；异常持久预算，30次后等待平台原号恢复。 */
 @Scheduled(fixedDelayString="${tradeflow.recovery.delay-ms:2000}")
 public void recover(){for(int i=0;i<8;i++){Claim c=tx.execute(s->db.claim());if(c==null)return;Aftersale a=db.get(c.no(),false);if(a==null)continue;try{Result r=perform(c,a);tx.executeWithoutResult(s->{var current=db.get(c.no(),true);if(!db.owns(c))return;apply(c,current,r);});}catch(Exception e){String error=e instanceof RemoteFailure r?r.code():"REMOTE_RESULT_UNKNOWN";tx.executeWithoutResult(s->{db.get(c.no(),true);if(db.owns(c))db.reschedule(c,error,false);});}}}
 private <T>T data(ApiResponse<T> response){if(response==null||!"OK".equals(response.code())||response.data()==null)throw new IllegalStateException("远端结果未知");return response.data();}
 private Authorization query(String no){return data(orders.query(no,"aftersale",token));}
 /** 授权来源、金额及用户绑定必须一致；不使用客户端金额，不接受混淆的远端对象。 */
 private void validate(Aftersale a,Authorization x){if(!a.aftersaleNo().equals(x.sourceNo())||!a.orderNo().equals(x.orderNo())||!a.userId().equals(x.userId())||!a.merchantId().equals(x.merchantId())||!a.type().equals(x.sourceType())||x.amountCents()<=0||x.amountCents()>100000000L||!"CNY".equals(x.currency())||x.paymentNo()==null||x.version()<1||(a.amountCents()!=null&&a.amountCents().longValue()!=x.amountCents())||(a.currency()!=null&&!a.currency().equals(x.currency())))throw new IllegalStateException("授权绑定异常");if(!"UNSHIPPED_FULL".equals(a.type())){long requested=0;for(var allocation:db.allocations(a.aftersaleNo()))requested=Math.addExact(requested,allocation.amountCents());if(requested!=x.amountCents())throw new IllegalStateException("授权金额与原申请不一致");}}
 /** CREATING先查原授权；仅权威404重放reserve，明确业务拒绝结束本次申请，未知保留任务。 */
 private Result perform(Claim c,Aftersale a){
  if("AUTHORIZATION".equals(c.phase())){Authorization x;try{x=query(a.aftersaleNo());}catch(RemoteFailure e){if(e.status()!=404||!"AUTHORIZATION_NOT_FOUND".equals(e.code()))throw e;try{x=data(orders.reserve("aftersale",token,new Reserve(a.aftersaleNo(),a.orderNo(),a.userId(),a.type(),db.allocations(a.aftersaleNo()))));}catch(RemoteFailure rejection){if((rejection.status()==400||rejection.status()==409||rejection.status()==422))return new Result("REJECTED",null,null,true,rejection.code());throw rejection;}}if("TERMINATED".equals(x.state()))return new Result("REJECTED",null,null,true,"AUTHORIZATION_TERMINATED");validate(a,x);if(!"RESERVED".equals(x.state()))throw new IllegalStateException("授权状态异常");return new Result("PENDING_REVIEW",x,null,true,null);}
  if("TERMINATE".equals(c.phase())){var x=data(orders.terminate(a.aftersaleNo(),"aftersale",token,new Change(a.orderNo(),"CANCELLING".equals(a.status())?"用户撤销售后":"商家拒绝售后")));if(!a.aftersaleNo().equals(x.sourceNo())||!a.orderNo().equals(x.orderNo())||!"TERMINATED".equals(x.state()))throw new IllegalStateException("终止结果未确认");return new Result("CANCELLING".equals(a.status())?"CANCELLED":"RETURN_REJECTING".equals(a.status())?"RETURN_REJECTED":"REJECTED",null,null,true,null);}
  // 验收事实先交接订单并形成独立入库义务，之后才进入退款；任意RPC超时仍按原收据重试。
  if("RECEIVE".equals(c.phase())){if(!"RETURN_REFUND".equals(a.type())||!"RETURN_RECEIVED".equals(a.status()))throw new IllegalStateException("验收任务状态异常");var x=data(orders.receive(a.aftersaleNo(),"aftersale",token,new ReturnAccept(a.orderNo(),"RET:"+a.aftersaleNo(),db.dispositions(a.aftersaleNo()))));validate(a,x);if(!Set.of("RESERVED","EXECUTING","SETTLING","COMPLETED").contains(x.state()))throw new IllegalStateException("验收授权状态异常");return new Result("REFUND_PROCESSING",x,null,false,null);}
  if(!"REFUND".equals(c.phase())||!"REFUND_PROCESSING".equals(a.status()))throw new IllegalStateException("任务状态异常");
  var x=query(a.aftersaleNo());validate(a,x);
  // 激活响应丢失时本轮保留原任务，下轮先查询同授权，绝不回退为可撤销。
  if("RESERVED".equals(x.state())){x=data(orders.activate(a.aftersaleNo(),"aftersale",token,new Change(a.orderNo(),"商家审核通过")));validate(a,x);}
  if(!Set.of("EXECUTING","SETTLING","COMPLETED").contains(x.state()))throw new IllegalStateException("授权不可执行退款");
  View v;try{v=data(refunds.query(a.refundNo(),"aftersale",token));}catch(RemoteFailure e){if(e.status()!=404||!"REFUND_NOT_FOUND".equals(e.code())||!"EXECUTING".equals(x.state()))throw e;v=data(refunds.submit("aftersale",token,new Submit(a.refundNo(),a.aftersaleNo(),a.orderNo())));}
  if(!a.refundNo().equals(v.refundNo())||!a.aftersaleNo().equals(v.sourceNo())||!a.orderNo().equals(v.orderNo())||!x.paymentNo().equals(v.paymentNo())||x.amountCents()!=v.amountCents()||!x.currency().equals(v.currency())||v.status()==null||!Set.of("PENDING","PROCESSING","UNKNOWN","SUCCEEDED","FAILED").contains(v.status()))throw new IllegalStateException("退款绑定异常");
  // FAILED只是支付执行失败，售后仍REFUND_PROCESSING且授权权益不释放；商家受控重试另起新执行。
  if("FAILED".equals(v.status()))return new Result("REFUND_PROCESSING",x,v,true,v.lastError()==null?"REFUND_FAILED":safe(v.lastError()));
  // 退款成功还不等于售后完成，订单COMPLETED证明退款结算及库存回补都已完成。
  boolean completed="SUCCEEDED".equals(v.status());completed=completed&&"COMPLETED".equals(x.state());
  return new Result(completed?"COMPLETED":"REFUND_PROCESSING",x,v,completed,null);
 }
 private String safe(String value){return value.matches("[A-Z0-9_]{1,128}")?value:"REFUND_FAILED";}
 /** 主单和任务统一锁顺序，只有当前租约版本可落远端事实；旧领取不能覆盖新退款号。 */
 private void apply(Claim c,Aftersale a,Result r){com.tradeflow.observability.BusinessLog.afterCommit("AFTERSALE_PROGRESS",java.util.Map.of("aftersaleNo",a.aftersaleNo(),"orderNo",a.orderNo(),"taskId",c.no(),"state",r.state()));if("COMPLETED".equals(r.state())&&!"COMPLETED".equals(a.status()))outbox.enqueue("AFTERSALE",a.aftersaleNo(),1,"AftersaleCompleted",a.orderNo(),a.refundNo(),java.util.Map.of("orderNo",a.orderNo(),"aftersaleNo",a.aftersaleNo(),"refundNo",a.refundNo()));if(r.authorization()!=null){var x=r.authorization();db.jdbc().update("UPDATE aftersale SET amount_cents=?,currency=?,authorized_payment_no=? WHERE aftersale_no=?",x.amountCents(),x.currency(),x.paymentNo(),a.aftersaleNo());}if(r.refund()!=null){var v=r.refund();db.jdbc().update("UPDATE aftersale SET refund_status=? WHERE aftersale_no=?",v.status(),a.aftersaleNo());db.jdbc().update("UPDATE aftersale_refund_attempt SET status=?,last_error=? WHERE refund_no=?",v.status(),r.error(),a.refundNo());}db.jdbc().update("UPDATE aftersale SET status=?,last_error=? WHERE aftersale_no=?",r.state(),r.error(),a.aftersaleNo());if("RECEIVE".equals(c.phase())){db.task(a.aftersaleNo(),"REFUND");}else if(r.done())db.done(c);else db.reschedule(c,null,true);}
}
