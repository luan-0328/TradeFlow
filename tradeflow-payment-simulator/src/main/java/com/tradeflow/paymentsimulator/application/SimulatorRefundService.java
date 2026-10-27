package com.tradeflow.paymentsimulator.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.paymentsimulator.infrastructure.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
/** 独立退款通道：每笔退款持不可变绑定，原支付与预算同事务锁定，不信任调用方自报已支付事实。 */
@Service
public class SimulatorRefundService {
 private final SimulatorRefundMapper db;private final SimulatorMapper payments;private final TransactionTemplate tx;private final ObjectMapper json;
 private static final Set<String> MODES=Set.of("SUCCESS","REJECT","PROCESSING","RESPONSE_TIMEOUT","CALLBACK_LOST","CALLBACK_DUPLICATE");
 public SimulatorRefundService(SimulatorRefundMapper db,SimulatorMapper payments,TransactionTemplate tx,ObjectMapper json){this.db=db;this.payments=payments;this.tx=tx;this.json=json;}
 public record Result(ChannelFact fact,boolean responseTimeout) { }
 /** 同号重放先核对原绑定；首次受理锁定原支付并占用剩余真实本金，拒绝和超额写明确FAILED。 */
 public Result refund(ChannelCommand c){validate(c);return local(t->{
  db.defaultScenario(c.refundNo());String mode=db.scenarioLock(c.refundNo());
  var paid=payments.lock(c.paymentNo());
  if(paid==null||!paid.orderNo().equals(c.orderNo())||!paid.currency().equals(c.currency()))throw error(409,"CHANNEL_BINDING_CONFLICT","原支付绑定不存在或不一致");
  Map<String,Object> old=db.lock(c.refundNo());if(old!=null){bind(old,c);return new Result(fact(old),false);}
  boolean accepted="SUCCEEDED".equals(paid.status())&&!mode.equals("REJECT")&&db.reserve(c.paymentNo(),c.amountCents())==1;
  String state=!accepted?"FAILED":mode.equals("PROCESSING")?"PROCESSING":"SUCCEEDED";
  db.insert(c,state,"SIMR"+UUID.randomUUID().toString().replace("-",""));
  if(accepted&&state.equals("SUCCEEDED")&&db.settle(c.paymentNo(),c.amountCents(),c.amountCents())!=1)throw new IllegalStateException("通道退款预算不一致");
  Map<String,Object> row=db.lock(c.refundNo());
  if(state.equals("PROCESSING"))db.process("RPROC:"+c.refundNo(),c.refundNo());else enqueue(row,mode);
  payments.audit("payment","REFUND",c.refundNo(),null,"独立通道退款预算受理",state);
  return new Result(fact(row),mode.equals("RESPONSE_TIMEOUT"));
 });}
 /** 查询只读已提交的不可变事实，查无记录404不代表失败；调用方必须按原退款号重试。 */
 public ChannelFact query(String no){validRefund(no);Map<String,Object> row=db.find(no);if(row==null)throw error(404,"CHANNEL_REFUND_NOT_FOUND","通道暂无退款记录");return fact(row);}
 /** 管理场景与支付独立，默认绑定后禁止变更；审计与场景同事务追加。 */
 public void configure(String no,String mode,String actor,String reason,String request){validRefund(no);if(!MODES.contains(mode==null?"":mode)||actor==null||actor.isBlank()||actor.length()>64||reason==null||reason.isBlank()||reason.length()>500)throw error(400,"INVALID_SCENARIO","场景操作人及原因必填");try{local(t->{db.configure(no,mode,actor,reason);payments.audit(actor,"CONFIGURE_REFUND_SCENARIO",no,request,reason,mode);return null;});}catch(DuplicateKeyException e){throw error(409,"SCENARIO_ALREADY_BOUND","退款号场景已固定");}}
 /** 恢复事务已领取并验证任务租约后调用；固定原支付后退款锁顺序，成功只结算一次预算。 */
 public void settle(String no){Map<String,Object> snapshot=db.find(no);if(snapshot==null)throw new IllegalStateException("通道退款任务绑定缺失");payments.lock(s(snapshot,"payment_no"));Map<String,Object> row=db.lock(no);if(!"PROCESSING".equals(s(row,"status")))return;if(db.succeed(no)!=1)throw new IllegalStateException("通道退款状态竞争");if(db.settle(s(row,"payment_no"),n(row,"amount_cents"),n(row,"amount_cents"))!=1)throw new IllegalStateException("通道退款预算不一致");enqueue(db.lock(no),"SUCCESS");payments.audit("SYSTEM","ASYNC_REFUND_SUCCESS",no,null,"持久任务确认退款成功","SUCCEEDED");}
 /** 事实与冻结回调在同一事务，丢回调仅省略通知任务，查询仍能得到成功事实。 */
 private void enqueue(Map<String,Object> row,String mode){if(mode.equals("CALLBACK_LOST"))return;ChannelFact f=fact(row);String id="RNTF:"+f.refundNo()+":"+f.channelVersion();try{db.callback(id,f.refundNo(),json.writeValueAsString(new Callback(id,f)),mode.equals("CALLBACK_DUPLICATE")?2:1);}catch(Exception e){throw new IllegalStateException("退款回调序列化失败",e);}}
 /** 死锁与锁超时整笔回滚后最多三次短事务抖动重试，始终使用原退款号。 */
 private <T>T local(TransactionCallback<T> action){for(int attempt=1;;attempt++){try{return tx.execute(action);}catch(PessimisticLockingFailureException failure){if(attempt>=3)throw error(503,"CHANNEL_BUSY","退款通道繁忙，请按原号查询重试");LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(5_000_000L,25_000_000L));}}}
 private void validate(ChannelCommand c){if(c==null)throw error(400,"INVALID_COMMAND","退款命令不能为空");validRefund(c.refundNo());if(c.paymentNo()==null||!c.paymentNo().matches("[A-Za-z0-9_-]{1,64}")||c.orderNo()==null||!c.orderNo().matches("[A-Za-z0-9_-]{1,64}")||c.sourceNo()==null||!c.sourceNo().matches("(?:as_[a-f0-9]{32}|CLOSE:ord_[a-f0-9]{32})")||c.amountCents()<=0||!"CNY".equals(c.currency()))throw error(400,"INVALID_COMMAND","退款绑定或金额不合法");}
 private void validRefund(String no){if(no==null||!no.matches("rfd_[a-f0-9]{32}"))throw error(400,"INVALID_REFUND_NO","退款编号不合法");}
 private void bind(Map<String,Object> row,ChannelCommand c){if(!Objects.equals(s(row,"payment_no"),c.paymentNo())||!Objects.equals(s(row,"order_no"),c.orderNo())||!Objects.equals(s(row,"source_no"),c.sourceNo())||n(row,"amount_cents")!=c.amountCents()||!Objects.equals(s(row,"currency"),c.currency()))throw error(409,"CHANNEL_BINDING_CONFLICT","退款号已绑定其他资金参数");}
 private ChannelFact fact(Map<String,Object> r){return new ChannelFact(s(r,"refund_no"),s(r,"payment_no"),s(r,"order_no"),s(r,"source_no"),n(r,"amount_cents"),s(r,"currency"),s(r,"status"),s(r,"channel_refund_no"),n(r,"channel_version"),time(r,"updated_at"),time(r,"succeeded_at"));}
 private static String s(Map<String,Object> r,String k){Object v=r.get(k);return v==null?null:v.toString();}private static long n(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}
 private static Instant time(Map<String,Object> r,String k){Object v=r.get(k);if(v==null)return null;if(v instanceof Timestamp t)return t.toInstant();if(v instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);if(v instanceof Instant t)return t;throw new IllegalStateException("未知数据库时间类型");}
 private static BusinessException error(int status,String code,String message){return new BusinessException(status,code,message);}
}
