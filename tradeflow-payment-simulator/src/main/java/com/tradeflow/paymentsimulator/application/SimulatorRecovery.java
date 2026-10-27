package com.tradeflow.paymentsimulator.application;
import com.tradeflow.discovery.ServiceEndpoints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.paymentsimulator.domain.SimulatorRows.Task;
import com.tradeflow.paymentsimulator.infrastructure.SimulatorTaskMapper;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
/** 数据库租约恢复；进程崩溃后重领原号，任何网络调用都在事务之外。 */
@Component
public class SimulatorRecovery {
 private static final Logger LOG=LoggerFactory.getLogger(SimulatorRecovery.class);
 private final SimulatorTaskMapper db;private final TransactionTemplate tx;private final SimulatorService service;private final SimulatorRefundService refunds;
 private final ObjectMapper json;private final String owner=UUID.randomUUID().toString();private final byte[] secret;private final ServiceEndpoints endpoints;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
 public SimulatorRecovery(SimulatorTaskMapper db,TransactionTemplate tx,SimulatorService service,SimulatorRefundService refunds,ObjectMapper json,Environment env,ServiceEndpoints endpoints){
  this.db=db;this.tx=tx;this.service=service;this.refunds=refunds;this.json=json;this.endpoints=endpoints;
  secret=env.getRequiredProperty("tradeflow.callback-secret").getBytes(StandardCharsets.UTF_8);
  if(secret.length<32)throw new IllegalStateException("callback-secret至少32字节");

 }
 /** 固定批量领取避免线程饥饿；独立领取提交后再锁任务和通道，失败按原号恢复。 */
 @Scheduled(fixedDelayString="${tradeflow.recovery-delay-ms:1000}") public void processes(){run("process_task",false);}
 /** 回调仅接受Nacos中的固定支付服务名，不读取客户端提供的回调URL，合法ACK才完成。 */
 @Scheduled(fixedDelayString="${tradeflow.recovery-delay-ms:1000}") public void callbacks(){run("callback_task",true);}
 /** 独立退款推进任务，不占用支付处理任务的领取队列。 */
 @Scheduled(fixedDelayString="${tradeflow.recovery-delay-ms:1000}") public void refundProcesses(){run("refund_process_task",false);}
 /** 独立退款回调按固定支付服务名发现实例，沿用租约围栏与冻结通知正文。 */
 @Scheduled(fixedDelayString="${tradeflow.recovery-delay-ms:1000}") public void refundCallbacks(){run("refund_callback_task",true);}
 /** 表名仅来自本类四个固定入口；领取事务提交后执行副作用，应用前复核有效租约。 */
 private void run(String table,boolean remote){
  for(int i=0;i<10;i++){
   Task t;
   try{t=tx.execute(s->{Task n=db.next(table);if(n==null)return null;db.claim(table,n.taskNo(),owner);return new Task(n.taskNo(),n.paymentNo(),"RUNNING",n.attemptCount()+1,n.claimVersion()+1,owner,n.payload(),n.deliveriesRequired(),n.deliveriesDone());});}
   catch(RuntimeException e){LOG.warn("模拟器任务领取暂不可用 table={}",table);return;}
   if(t==null)return;
   try{
    if(t.attemptCount()>30){tx.executeWithoutResult(s->{if(db.fence(table,t.taskNo(),owner,t.claimVersion())!=null)db.retry(table,t.taskNo(),owner,t.claimVersion(),0,"RECOVERY_BUDGET_EXHAUSTED");});continue;}
    if(remote)send(t,table.startsWith("refund_"));
    tx.executeWithoutResult(s->{if(db.fence(table,t.taskNo(),owner,t.claimVersion())==null)return;if(!remote){if(table.startsWith("refund_"))refunds.settle(t.paymentNo());else service.settle(t.paymentNo());}db.complete(table,t.taskNo(),owner,t.claimVersion());});
   }catch(Exception e){
    try{tx.executeWithoutResult(s->{if(db.fence(table,t.taskNo(),owner,t.claimVersion())!=null)db.retry(table,t.taskNo(),owner,t.claimVersion(),Math.min(60,2*t.attemptCount()),remote?"CALLBACK_DELIVERY_UNCONFIRMED":"PROCESS_RETRY");});}
    catch(RuntimeException ignored){LOG.warn("模拟器任务回写失败，等待租约恢复 table={}",table);}
   }
  }
 }
 /** 签名精确覆盖秒时间戳、nonce和冻结UTF8 body；不自动重定向，不在事务内等待HTTP。 */
 private void send(Task t,boolean refund)throws Exception{
  String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
  Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
  String signature=HexFormat.of().formatHex(mac.doFinal((timestamp+"\n"+nonce+"\n"+t.payload()).getBytes(StandardCharsets.UTF_8)));
  HttpRequest request=com.tradeflow.observability.TracePropagation.apply(HttpRequest.newBuilder(endpoints.resolve("http://tradeflow-payment/callbacks/v1/"+(refund?"refunds":"payments")))).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json; charset=UTF-8")
   .header("X-Notification-Timestamp",timestamp).header("X-Notification-Nonce",nonce).header("X-Notification-Signature",signature)
   .POST(HttpRequest.BodyPublishers.ofString(t.payload(),StandardCharsets.UTF_8)).build();
  HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  if(response.statusCode()!=200)throw new IllegalStateException("CALLBACK_HTTP_REJECTED");
  var root=json.readTree(response.body());
  if(!"OK".equals(root.path("code").asText())||!root.path("data").path("ack").asBoolean(false))throw new IllegalStateException("CALLBACK_ACK_MISSING");
 }
}
