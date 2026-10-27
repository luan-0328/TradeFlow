package com.tradeflow.payment.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.*;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.payment.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
/** 退款用例：订单激活授权是权益事实，支付与来源执行额度由本库短事务串行，RPC始终事务外。 */
@Service
public class RefundService {
 private final RefundMapper db;private final PaymentMapper payments;private final PaymentRemote remote;private final TransactionTemplate tx;private final ObjectMapper json;
 private final String orderToken,aftersaleToken,secret,owner=TraceIds.newId();
 public RefundService(RefundMapper db,PaymentMapper payments,PaymentRemote remote,TransactionTemplate tx,ObjectMapper json,Environment env){this.db=db;this.payments=payments;this.remote=remote;this.tx=tx;this.json=json;orderToken=env.getRequiredProperty("tradeflow.internal.order-token");aftersaleToken=env.getRequiredProperty("tradeflow.internal.aftersale-token");secret=env.getRequiredProperty("tradeflow.callback-secret");if(orderToken.isBlank()||aftersaleToken.isBlank()||secret.isBlank())throw new IllegalStateException("退款内部凭据不能为空");}
 /** 同退款号优先返回已绑定结果；首次执行只接受EXECUTING权威授权，失败重试仍需新退款号。 */
 public View submit(String caller,String token,Submit c){
  authenticate(caller,token);if(c==null||c.refundNo()==null||!c.refundNo().matches("rfd_[a-f0-9]{32}"))throw error(400,"INVALID_REFUND","退款编号不合法");scope(caller,c.sourceNo());PaymentRemote.safe(c.orderNo());
  Map<String,Object> old=db.find(c.refundNo());if(old!=null){bindSubmit(old,c);return trustedView(old);}
  Authorization a=remote.authorization(c.sourceNo());
  if(a==null||!c.sourceNo().equals(a.sourceNo())||!c.orderNo().equals(a.orderNo())||!"EXECUTING".equals(a.state())||a.amountCents()<=0||!"CNY".equals(a.currency())||a.version()<0||a.userId()==null||a.merchantId()==null||!("order".equals(caller)?Set.of("LATE_PAYMENT"):Set.of("UNSHIPPED_FULL","REFUND_ONLY","RETURN_REFUND")).contains(a.sourceType()))throw error(409,"REFUND_NOT_AUTHORIZED","退款授权未激活或绑定不合法");
  PaymentRemote.safe(a.paymentNo());String digest=authorizationHash(a);
  return tx.execute(t->{
   Map<String,Object> payment=required(payments.lock(a.paymentNo()));
   if(!"SUCCEEDED".equals(s(payment,"status"))||"YES".equals(s(payment,"review_required"))||!a.orderNo().equals(s(payment,"order_no"))||!a.userId().equals(s(payment,"user_id"))||!a.currency().equals(s(payment,"currency")))throw error(409,"PAYMENT_NOT_REFUNDABLE","支付事实尚不可退款");
   if(db.account(a.paymentNo())==null)throw error(409,"REFUND_ACCOUNT_MISSING","退款本金账户缺失");
   db.source(a,digest);Map<String,Object> source=db.sourceLock(a.sourceNo());
   if(!a.paymentNo().equals(s(source,"payment_no"))||!a.orderNo().equals(s(source,"order_no"))||!digest.equals(s(source,"authorization_hash")))throw error(409,"REFUND_SOURCE_CONFLICT","同来源授权绑定不一致");
   Map<String,Object> saved=db.lock(c.refundNo());if(saved!=null){bindSubmit(saved,c);return trustedView(saved);}
   long amount=a.amountCents()-n(source,"succeeded_cents");
   if(db.reserveAccount(a.paymentNo(),amount)!=1||db.reserveSource(a.sourceNo(),c.refundNo(),amount)!=1)throw error(409,"REFUND_QUOTA_EXCEEDED","支付或来源退款额度不足或已有未决执行");
   db.insert(new ChannelCommand(c.refundNo(),a.paymentNo(),a.orderNo(),a.sourceNo(),amount,a.currency()));db.task(c.refundNo());
   db.audit("RESERVE_REFUND_QUOTA",c.refundNo(),TraceIds.newId(),"退款执行占用双额度，订单授权版本="+a.version());return view(required(db.find(c.refundNo())));
  });
 }
 /** 调用方仅能查询自身来源范围；冻结事实不能被下游当成结算凭据。 */
 public View detail(String caller,String token,String no){authenticate(caller,token);Map<String,Object> row=required(db.find(PaymentRemote.safe(no)));scope(caller,s(row,"source_no"));return trustedView(row);}
 /** 验真先于JSON解析和去重；业务锁统一从支付开始，非法请求不能占用通知号。 */
 public Map<String,Boolean> callback(byte[] body,String timestamp,String nonce,String signature){
  if(body==null||body.length>65536)throw error(413,"CALLBACK_TOO_LARGE","回调正文过大");verify(body,timestamp,nonce,signature);
  Callback c;try{c=json.readValue(body,Callback.class);}catch(Exception e){throw error(400,"INVALID_CALLBACK","退款回调格式不合法");}
  if(c==null||c.fact()==null||c.notificationNo()==null||!c.notificationNo().matches("[A-Za-z0-9._:-]{1,64}"))throw error(400,"INVALID_CALLBACK","退款通知编号不合法");String digest=hash(body);
  try{tx.executeWithoutResult(t->{Map<String,Object> row=locks(c.fact().refundNo());Map<String,Object> old=payments.notification(c.notificationNo());if(old!=null){if(!digest.equals(s(old,"payload_hash"))||!c.fact().refundNo().equals(s(old,"business_no")))throw error(409,"NOTIFICATION_CONFLICT","同通知号内容不一致");return;}boolean ok=apply(row,c.fact());db.notification(c.notificationNo(),c.fact().refundNo(),digest,ok?"APPLIED":"QUARANTINED");});}
  catch(org.springframework.dao.DuplicateKeyException duplicate){tx.executeWithoutResult(t->{Map<String,Object> old=payments.notification(c.notificationNo());if(old==null||!digest.equals(s(old,"payload_hash"))||!c.fact().refundNo().equals(s(old,"business_no")))throw error(409,"NOTIFICATION_CONFLICT","同通知号内容不一致");});}
  return Map.of("ack",true);
 }
 /** 领取事务只锁任务并立即提交；执行阶段重新围栏，原号未知先查询，超过30次转人工保留占用。 */
 @Scheduled(fixedDelayString="${tradeflow.worker-delay-ms:1000}") public void recover(){
  Map<String,Object> claim=tx.execute(t->{Map<String,Object> task=db.due();if(task==null)return null;payments.claimTask(s(task,"task_no"),owner);task.put("claim_version",n(task,"claim_version")+1);task.put("attempt_count",n(task,"attempt_count")+1);return task;});if(claim==null)return;
  String no=s(claim,"task_no");long version=n(claim,"claim_version");
  try{
   ChannelCommand command=tx.execute(t->{if(db.fence(no,version,owner)==null)return null;Map<String,Object> r=locks(no);if("YES".equals(s(r,"review_required"))||n(claim,"attempt_count")>30){finishTask(no,version,"MANUAL_REVIEW",s(claim,"step"),"退款恢复需人工核验");return null;}if(terminal(s(r,"status"))){finishTask(no,version,"SUCCEEDED",s(claim,"step"),null);return null;}payments.beforeSubmit(no);return command(r);});
   if(command==null)return;
   ChannelFact fact="SUBMIT".equals(s(claim,"step"))?remote.refund(command):remote.refundQuery(no);
   tx.executeWithoutResult(t->{if(db.fence(no,version,owner)==null)return;Map<String,Object> r=locks(no);if(fact==null){finishTask(no,version,"READY","SUBMIT","原号通道暂无记录，复用原退款号");return;}boolean ok=apply(r,fact);finishTask(no,version,!ok?"MANUAL_REVIEW":terminal(fact.status())?"SUCCEEDED":"READY","QUERY",ok?null:"退款事实矛盾");});
  }catch(Exception failure){tx.executeWithoutResult(t->{if(db.fence(no,version,owner)==null)return;locks(no);boolean invalid=failure instanceof BusinessException e&&"INVALID_CHANNEL_FACT".equals(e.code());if(invalid)db.issue(no,"退款通道绑定或格式矛盾");else db.unknown(no);finishTask(no,version,invalid?"MANUAL_REVIEW":"READY","QUERY",invalid?"退款通道事实需人工核验":"通道未知，先查原退款号");});}
 }
 /** 完成前复核共享任务有效租约；跨期返回零行时抛出异常，使额度、事实与流水同事务回滚。 */
 private void finishTask(String no,long claim,String state,String step,String error){if(payments.finishTask(no,claim,owner,state,step,error)!=1)throw new IllegalStateException("退款恢复租约失效，本地应用回滚");}
 /** 先普通读取不可变绑定，再按支付、账户、来源、退款固定顺序持锁，所有资金入口共用。 */
 private Map<String,Object> locks(String no){Map<String,Object> r=required(db.find(no));Map<String,Object> paid=required(payments.lock(s(r,"payment_no")));required(db.account(s(r,"payment_no")));required(db.sourceLock(s(r,"source_no")));Map<String,Object> locked=required(db.lock(no));if("YES".equals(s(paid,"review_required"))){db.issue(no,"原支付事实矛盾，退款同步冻结待人工核验");return required(db.lock(no));}return locked;}
 /** 对账在action围栏事务内按原资金锁序复用结果应用；不会再次提交退款，补流水不再转换额度。 */
 public boolean reconcileApply(ChannelFact fact,String actionNo,boolean repairLedger){if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("对账退款修复必须处于已围栏事务");var row=locks(fact.refundNo());if(!apply(row,fact))return false;if(repairLedger&&"SUCCEEDED".equals(fact.status())&&db.reconciliationLedgerCount(fact.refundNo())==0){
   // 低版本可被忽略，但不能把其矛盾成功时间作为新流水发生时间，补记仅采用锁定的当前证据。
   var current=required(db.lock(fact.refundNo()));if(!"SUCCEEDED".equals(s(current,"status"))||!Objects.equals(s(current,"channel_refund_no"),fact.channelRefundNo())||!Objects.equals(time(current,"succeeded_at"),millis(fact.succeededAt())))return conflict(current,"对账补流水成功证据矛盾");
   db.ledger(new ChannelFact(fact.refundNo(),s(current,"payment_no"),s(current,"order_no"),s(current,"source_no"),n(current,"amount_cents"),s(current,"currency"),"SUCCEEDED",s(current,"channel_refund_no"),n(current,"channel_version"),time(current,"channel_updated_at"),time(current,"succeeded_at")));
  }db.audit("RECONCILE_REFUND",fact.refundNo(),actionNo,"对账统一应用与受控流水补记，action="+actionNo);return true;}
 /** 响应、查询和已验真回调唯一应用器；终态只转换一次额度，流水和事件与成功事实原子提交。 */
 private boolean apply(Map<String,Object> r,ChannelFact f){
  if(f==null||!Objects.equals(s(r,"refund_no"),f.refundNo())||!Objects.equals(s(r,"source_no"),f.sourceNo())||!Objects.equals(s(r,"order_no"),f.orderNo())||!Objects.equals(s(r,"payment_no"),f.paymentNo())||n(r,"amount_cents")!=f.amountCents()||!"CNY".equals(f.currency())||f.channelVersion()<1||f.updatedAt()==null||!Set.of("PENDING","PROCESSING","UNKNOWN","SUCCEEDED","FAILED").contains(f.status()==null?"":f.status())||(f.channelRefundNo()!=null&&!f.channelRefundNo().matches("[A-Za-z0-9:_-]{1,64}"))||("SUCCEEDED".equals(f.status())&&(f.succeededAt()==null||f.channelRefundNo()==null))||(!"SUCCEEDED".equals(f.status())&&f.succeededAt()!=null))throw error(409,"INVALID_CHANNEL_FACT","退款通道事实绑定或格式不合法");
  if("YES".equals(s(r,"review_required")))return false;String state=s(r,"status");long version=n(r,"channel_version");
  if(s(r,"channel_refund_no")!=null&&!Objects.equals(s(r,"channel_refund_no"),f.channelRefundNo()))return conflict(r,"通道退款号变化");
  if(terminal(state)&&!state.equals(f.status())){if(!terminal(f.status())&&f.channelVersion()<version)return true;return conflict(r,"退款终态矛盾");}
  if(f.channelVersion()<version)return true;
  if(f.channelVersion()==version){if(!state.equals(f.status())||!Objects.equals(s(r,"channel_refund_no"),f.channelRefundNo())||!Objects.equals(time(r,"succeeded_at"),millis(f.succeededAt()))||!Objects.equals(time(r,"channel_updated_at"),millis(f.updatedAt())))return conflict(r,"同版本退款证据不一致");return true;}
  if(terminal(state)){if(!Objects.equals(time(r,"succeeded_at"),millis(f.succeededAt())))return conflict(r,"退款成功时间变化");db.fact(f);return true;}
  db.fact(f);
  if(terminal(f.status())){com.tradeflow.observability.BusinessLog.afterCommit("REFUND_FACT_APPLIED",Map.of("paymentNo",f.paymentNo(),"refundNo",f.refundNo(),"orderNo",f.orderNo(),"taskId",f.refundNo(),"state",f.status()));long success="SUCCEEDED".equals(f.status())?f.amountCents():0;if(db.settleAccount(f.paymentNo(),f.amountCents(),success)!=1||db.settleSource(f.sourceNo(),f.refundNo(),f.amountCents(),success)!=1)throw new IllegalStateException("退款双额度结算不一致");if(success>0)db.ledger(f);String id="evt_"+TraceIds.newId();try{db.outbox(id,f,success>0?"RefundSucceeded":"RefundFailed",json.writeValueAsString(new Event(id,f)));}catch(Exception e){throw new IllegalStateException("退款事件序列化失败",e);}}
  return true;
 }
 /** 矛盾不回退事实、不释放未知占用，仅冻结并追加证据。 */
 private boolean conflict(Map<String,Object> row,String reason){db.issue(s(row,"refund_no"),reason);db.audit("REFUND_FACT_CONFLICT",s(row,"refund_no"),TraceIds.newId(),reason);return false;}
 /** 调用方与独立token绑定，常量时间比较；不能借另一服务凭据进入退款范围。 */
 private void authenticate(String caller,String token){String expected="order".equals(caller)?orderToken:"aftersale".equals(caller)?aftersaleToken:null;if(expected==null||token==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw error(403,"FORBIDDEN","退款内部凭据无效");}
 /** 来源前缀是服务能力边界，售后不能操作关单来源，订单不能操作售后来源。 */
 private void scope(String caller,String source){boolean ok=source!=null&&("order".equals(caller)?source.matches("CLOSE:ord_[a-f0-9]{32}"):source.matches("as_[a-f0-9]{32}"));if(!ok)throw error(403,"REFUND_SOURCE_FORBIDDEN","调用方无权访问该退款来源");}
 /** 已持久退款重放必须保持来源与订单不变，不再次请求授权或占用额度。 */
 private void bindSubmit(Map<String,Object> row,Submit c){if(!c.sourceNo().equals(s(row,"source_no"))||!c.orderNo().equals(s(row,"order_no")))throw error(409,"REFUND_BINDING_CONFLICT","同退款号不能改变来源或订单");}
 /** 固定授权摘要只含不可变授权字段，不含执行过程状态，避免终态查询破坏原证据。 */
 private String authorizationHash(Authorization a){return hash(String.join("|",a.sourceNo(),a.sourceType(),a.orderNo(),a.paymentNo(),a.userId(),a.merchantId(),Long.toString(a.amountCents()),a.currency(),Long.toString(a.version())).getBytes(StandardCharsets.UTF_8));}
 /** 五分钟窗口原始字节HMAC，验签失败不占通知编号、不修改业务事实。 */
 private void verify(byte[] body,String timestamp,String nonce,String signature){try{long at=Long.parseLong(timestamp),now=Instant.now().getEpochSecond();if(at<now-300||at>now+300||nonce==null||!nonce.matches("[A-Za-z0-9_-]{1,128}")||signature==null||!signature.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException();Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update((timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));if(!MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature)))throw new IllegalArgumentException();}catch(Exception e){throw error(401,"INVALID_CALLBACK_SIGNATURE","退款回调验真失败");}}
 private static ChannelCommand command(Map<String,Object> r){return new ChannelCommand(s(r,"refund_no"),s(r,"payment_no"),s(r,"order_no"),s(r,"source_no"),n(r,"amount_cents"),s(r,"currency"));}
 private View trustedView(Map<String,Object> r){Map<String,Object> paid=payments.find(s(r,"payment_no"));if("YES".equals(s(r,"review_required"))||paid==null||"YES".equals(s(paid,"review_required")))throw error(409,"REFUND_MANUAL_REVIEW","退款事实需要人工核验");return view(r);}
 private static View view(Map<String,Object> r){return new View(s(r,"refund_no"),s(r,"source_no"),s(r,"order_no"),s(r,"payment_no"),n(r,"amount_cents"),s(r,"currency"),s(r,"status"),s(r,"last_error"));}
 private static boolean terminal(String s){return "SUCCEEDED".equals(s)||"FAILED".equals(s);}
 private static Map<String,Object> required(Map<String,Object> r){if(r==null)throw error(404,"REFUND_NOT_FOUND","退款或关联记录不存在");return r;}
 private static String s(Map<String,Object> r,String k){Object v=r.get(k);return v==null?null:v.toString();}
 private static long n(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}
 private static Instant time(Map<String,Object> r,String k){Object v=r.get(k);if(v==null)return null;if(v instanceof Timestamp t)return t.toInstant();if(v instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);if(v instanceof Instant t)return t;throw new IllegalStateException("未知数据库时间类型");}
 private static Instant millis(Instant i){return i==null?null:i.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);}
 private static String hash(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
 private static BusinessException error(int status,String code,String message){return new BusinessException(status,code,message);}
}
