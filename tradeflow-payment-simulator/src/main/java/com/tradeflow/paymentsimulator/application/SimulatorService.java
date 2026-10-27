package com.tradeflow.paymentsimulator.application;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.PaymentContracts.*;
import com.tradeflow.paymentsimulator.domain.SimulatorRows.Channel;
import com.tradeflow.paymentsimulator.infrastructure.SimulatorMapper;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
/** 独立通道事实所有者；短事务只操作本库，回调持久化后由恢复任务事务外发送。 */
@Service
public class SimulatorService {
 private final SimulatorMapper db; private final TransactionTemplate tx; private final ObjectMapper json;
 private static final Set<String> MODES=Set.of("SUCCESS","REJECT","PROCESSING","RESPONSE_TIMEOUT","CALLBACK_LOST","CALLBACK_DUPLICATE");
 public SimulatorService(SimulatorMapper db,TransactionTemplate tx,ObjectMapper json){this.db=db;this.tx=tx;this.json=json;}
 public record PayResult(ChannelFact fact,boolean responseTimeout) { }
 /** 首次绑定场景后锁唯一交易；同号异参409，墓碑和终态不反转。超时标记在提交后由HTTP层转换504。 */
 public PayResult pay(ChannelCommand command){
  validate(command);
  return local(s->{
   db.defaultScenario(command.paymentNo());
   String mode=db.scenarioLock(command.paymentNo()).mode();
   db.ensure(command,"SIM"+UUID.randomUUID().toString().replace("-",""));
   Channel c=db.lock(command.paymentNo()); bind(c,command);
   boolean first=c.status().equals("PENDING");
   if(first){
    String state=mode.equals("REJECT")?"FAILED":mode.equals("PROCESSING")?"PROCESSING":"SUCCEEDED";
    db.transition(c.paymentNo(),state); c=db.lock(c.paymentNo());
    if(state.equals("PROCESSING")) db.process("PROC:"+c.paymentNo(),c.paymentNo());
    else enqueue(c,mode);
    db.audit("payment","PAY",c.paymentNo(),null,"按固定场景受理支付",state);
   }
   return new PayResult(fact(c),first && mode.equals("RESPONSE_TIMEOUT"));
  });
 }
 /** 关闭即使先到也建立绑定完整的CLOSED墓碑；成功不可取消，处理中和关闭竞争同一行锁。 */
 public ChannelFact close(ChannelCommand command){
  validate(command);
  return local(s->{
   db.ensure(command,"SIM"+UUID.randomUUID().toString().replace("-",""));
   Channel c=db.lock(command.paymentNo()); bind(c,command);
   if(c.status().equals("PENDING")||c.status().equals("PROCESSING")){
    db.transition(c.paymentNo(),"CLOSED"); c=db.lock(c.paymentNo());
    enqueue(c,"SUCCESS");
    db.audit("payment","CLOSE",c.paymentNo(),null,"关闭屏障生效","CLOSED");
   }
   return fact(c);
  });
 }
 /** 短事务锁同号交易以串行读取；无记录404只代表当前查无记录，调用者仍必须显式关闭建立屏障。 */
 public ChannelFact query(String no){validNo(no);return local(s->{Channel c=db.lock(no);if(c==null)throw new BusinessException(404,"CHANNEL_NOT_FOUND","通道暂无记录");return fact(c);});}
 /** 管理场景只允许一次插入；默认场景已被pay绑定也拒绝改写。并发冲突在事务外转409。 */
 public void configure(String no,String mode,String actor,String reason,String request){
  validNo(no);
  if(!MODES.contains(mode==null?"":mode)||actor==null||actor.isBlank()||actor.length()>64||reason==null||reason.isBlank()||reason.length()>500)
   throw new BusinessException(400,"INVALID_SCENARIO","场景、操作人和原因必填且须符合长度要求");
  try{tx.executeWithoutResult(s->{db.scenario(no,mode,actor,reason);db.audit(actor,"CONFIGURE_SCENARIO",no,request,reason,mode);});}
  catch(DuplicateKeyException e){throw new BusinessException(409,"SCENARIO_ALREADY_BOUND","该支付号场景已固定");}
 }
 /** 持有channel行锁时只允许PROCESSING推进；关闭抢先则保持墓碑，任务可安全标为完成。 */
 public void settle(String no){
  Channel c=db.lock(no);
  if(c==null)throw new IllegalStateException("PROCESS_CHANNEL_MISSING");
  if(c.status().equals("PROCESSING")){
   db.transition(no,"SUCCEEDED");enqueue(db.lock(no),"SUCCESS");
   db.audit("SYSTEM","ASYNC_SUCCESS",no,null,"持久异步任务推进支付","SUCCEEDED");
  }
 }
 /** 回调体与事实同事务冻结；丢回调场景显式不创建任务，查询仍能获取成功事实。 */
 private void enqueue(Channel c,String mode){
  if(mode.equals("CALLBACK_LOST"))return;
  String notification="NTF:"+c.paymentNo()+":"+c.channelVersion();
  try{db.callback(notification,c.paymentNo(),json.writeValueAsString(new Callback(notification,fact(c))),mode.equals("CALLBACK_DUPLICATE")?2:1);}
  catch(JsonProcessingException e){throw new IllegalStateException("CALLBACK_SERIALIZATION_FAILED",e);}
 }
 /** 锁超时或死锁必须整笔短事务回滚后重新尝试；最多三次且复用原业务号，超限交给调用方原号重试。 */
 private <T> T local(TransactionCallback<T> action){
  for(int attempt=1;;attempt++){
   try{return tx.execute(action);}
   catch(PessimisticLockingFailureException e){
    if(attempt>=3)throw new BusinessException(503,"CHANNEL_BUSY","通道繁忙，请按原支付号重试");
    LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(5_000_000L,25_000_000L));
   }
  }
 }
 private ChannelFact fact(Channel c){return new ChannelFact(c.paymentNo(),c.orderNo(),c.amountCents(),c.currency(),c.status(),c.channelTransactionNo(),c.channelVersion(),c.updatedAt(),c.succeededAt());}
 /** 输入只接受稳定业务号、正整数分和CNY；未通过绑定检查不能触碰已有事实。 */
 private void validate(ChannelCommand c){if(c==null)throw new BusinessException(400,"INVALID_COMMAND","支付参数不能为空");validNo(c.paymentNo());validNo(c.orderNo());if(c.amountCents()<=0||!"CNY".equals(c.currency()))throw new BusinessException(400,"INVALID_AMOUNT","金额必须为正整数分且币种为CNY");}
 private void validNo(String no){if(no==null||!no.matches("[A-Za-z0-9:_-]{1,64}"))throw new BusinessException(400,"INVALID_BUSINESS_NO","业务编号格式错误");}
 /** 唯一订单号撞到其他支付号时可能没有当前行，也视为明确绑定冲突而非空指针故障。 */
 private void bind(Channel c,ChannelCommand x){if(c==null||!c.paymentNo().equals(x.paymentNo())||!c.orderNo().equals(x.orderNo())||c.amountCents()!=x.amountCents()||!c.currency().equals(x.currency()))throw new BusinessException(409,"CHANNEL_BINDING_CONFLICT","支付编号已绑定其他参数");}
}
