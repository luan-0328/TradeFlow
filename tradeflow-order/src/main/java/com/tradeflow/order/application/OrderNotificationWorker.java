package com.tradeflow.order.application;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.discovery.ServiceEndpoints;
import com.tradeflow.order.infrastructure.OrderNotificationMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.client.RestClient;

/** 交易后置可靠通知：日志建任务、租约领取、事务外投递、原键ACK；失败不改变任何订单或支付事实。 */
@Component public class OrderNotificationWorker {
 private final OrderNotificationMapper db;private final TransactionTemplate tx;private final RestClient client;private final ServiceEndpoints endpoints;private final String token;private final int maxAttempts;private final boolean enabled;
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OrderNotificationWorker.class);
 @Autowired public OrderNotificationWorker(OrderNotificationMapper db,PlatformTransactionManager manager,ServiceEndpoints endpoints,Environment env){this(db,manager,endpoints,env,client());}
 OrderNotificationWorker(OrderNotificationMapper db,PlatformTransactionManager manager,ServiceEndpoints endpoints,Environment env,RestClient client){this.db=db;this.tx=new TransactionTemplate(manager);this.endpoints=endpoints;this.client=client;token=env.getRequiredProperty("tradeflow.internal.order-token");maxAttempts=env.getProperty("tradeflow.notifications.max-attempts",Integer.class,12);enabled=env.getProperty("tradeflow.notifications.enabled",Boolean.class,true);if(maxAttempts<1||maxAttempts>100)throw new IllegalArgumentException("通知重试预算必须为1至100");}
 private static RestClient client(){var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build());factory.setReadTimeout(Duration.ofSeconds(3));return RestClient.builder().requestFactory(factory).build();}
 record Claim(long id,String owner,long version,int attempt,String businessKey,String userId,String orderNo,String status){}
 public record Notification(String businessKey,String title,String content){}
 /** 每轮先幂等补建至多100个任务，再投递一条；任何失败只留在独立通知恢复链路。 */
 @Scheduled(fixedDelayString="${tradeflow.notifications.scan-delay-ms:1000}",initialDelay=5000)
 public void scheduled(){if(!enabled)return;try{runOne();}catch(RuntimeException e){log.warn("订单通知任务恢复暂未完成：{}",e.getClass().getSimpleName());}}
 /** 领取事务结束才做网络请求，宕机后按原日志业务键恢复，不使用进程内队列承担可靠性。 */
 public void runOne(){
  tx.executeWithoutResult(s->db.materialize());String owner=UUID.randomUUID().toString();
  Claim c=tx.execute(s->{var row=db.due();if(row==null)return null;long id=n(row,"id");if(db.claim(id,owner)!=1)return null;return new Claim(id,owner,n(row,"claim_version")+1,(int)n(row,"attempt_count")+1,str(row,"business_key"),str(row,"user_id"),str(row,"order_no"),str(row,"to_status"));});
  if(c==null)return;
  // 已用尽预算的过期租约可能是上次RPC已成功但本地宕机，保留原键供人工核对，不能换号发送。
  if(c.attempt()>maxAttempts){finish(c,"MANUAL_REVIEW","DELIVERY_BUDGET_EXHAUSTED");return;}
  try{deliver(c);finish(c,"SUCCEEDED",null);}
  catch(RuntimeException e){finish(c,c.attempt()>=maxAttempts?"MANUAL_REVIEW":"READY","DELIVERY_UNCONFIRMED");}
 }
 /** HTTP200且业务OK才确认；data允许为空，未知或丢ACK重试同businessKey，用户服务唯一键去重。 */
 void deliver(Claim c){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("禁止在订单事务内发送通知");
  if(token.isBlank()||!c.userId().matches("[1-9][0-9]{0,18}")||!c.orderNo().matches("[A-Za-z0-9_-]{1,64}")||!c.businessKey().matches("order-status:[0-9]{1,20}"))throw new IllegalStateException("通知权威绑定无效");
  Notification body=new Notification(c.businessKey(),"订单状态更新","订单 "+c.orderNo()+" 状态更新为："+statusText(c.status())+"。请进入订单详情查看。");
  var response=client.post().uri(endpoints.resolve("http://tradeflow-user/internal/v1/users/"+c.userId()+"/notifications")).headers(h->com.tradeflow.observability.TracePropagation.headers().forEach(h::set)).header("X-Caller-Service","order").header("X-Service-Token",token).body(body).retrieve().body(new ParameterizedTypeReference<ApiResponse<Void>>(){});
  if(response==null||!"OK".equals(response.code()))throw new IllegalStateException("站内通知ACK未确认");
 }
 /** 回写必须尚持有效原租约；失去围栏时保留新任务，由用户端原键保证不重复可见通知。 */
 private void finish(Claim c,String state,String error){tx.executeWithoutResult(s->{if(db.finish(c.id(),c.owner(),c.version(),state,error)!=1)log.info("订单通知旧租约结果已丢弃，sourceKey={}",c.businessKey());});}
 static String statusText(String status){return switch(status){case "CREATING"->"正在创建";case "PENDING_PAYMENT"->"等待付款";case "PAID"->"已付款";case "SHIPPED"->"已发货";case "COMPLETED"->"已收货";case "CLOSING"->"正在关闭";case "CLOSED"->"已关闭";case "CANCELLED"->"已取消";default->"已有新进展";};}
 private static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}private static String str(Map<String,Object> row,String key){return String.valueOf(row.get(key));}
}
