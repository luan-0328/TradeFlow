package com.tradeflow.settlement;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.*;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.discovery.ServiceEndpoints;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** 所有权威身份、支付事实、履约资格RPC均在事务外进行，不允许跨库读取或故障降级。 */
@Component public class SettlementRemote {
 private final ObjectMapper json;private final ServiceEndpoints endpoints;private final String token;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
 public SettlementRemote(ObjectMapper json,ServiceEndpoints endpoints,Environment env){this.json=json;this.endpoints=endpoints;token=env.getRequiredProperty("tradeflow.internal.settlement-token");if(token.isBlank())throw new IllegalStateException("结算独立凭据不能为空");}
 public record Order(String orderNo,String userId,String merchantId,String status,Instant receivedAt,boolean hasActiveAftersale){}
 public record Fact(long factId,String businessNo,String paymentNo,String orderNo,String kind,long amountCents,String currency,Instant occurredAt){}
 public record Page(List<Fact> items,long nextAfter,boolean hasMore){}
 /** 重扫原账本，原支付号和原退款号是幂等事实主键。 */
 public Page facts(long after){return call("http://tradeflow-payment/internal/v1/settlement/facts?after="+after+"&pageSize=100",Page.class);}
 /** 最新订单资格仅作结算前风险校验；并发退款由后续不可变冲正兜底。 */
 public Order order(String no){return call("http://tradeflow-order/internal/v1/settlement/orders/"+safe(no),Order.class);}
 /** 权限每次从用户服务核验，撤权不依赖旧JWT角色缓存。 */
 public AuthContext context(String id){return call("http://tradeflow-user/internal/v1/users/"+safe(id)+"/auth-context",AuthContext.class);}
 private <T>T call(String url,Class<T> type){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("结算RPC禁止进入事务");
  try{var b=HttpRequest.newBuilder(endpoints.resolve(url)).timeout(Duration.ofSeconds(3)).header("X-Caller-Service","settlement").header("X-Service-Token",token).GET();com.tradeflow.observability.TracePropagation.apply(b);var response=client.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());if(response.statusCode()!=200)throw new IllegalStateException("依赖未确认");ApiResponse<T> result=json.readValue(response.body(),json.getTypeFactory().constructParametricType(ApiResponse.class,type));if(!"OK".equals(result.code())||result.data()==null)throw new IllegalStateException("依赖响应无效");return result.data();}
  catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","结算依赖中断");}catch(Exception e){throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","结算依赖暂不可确认");}
 }
 public static String safe(String s){if(s==null||!s.matches("[A-Za-z0-9_-]{1,64}"))throw new BusinessException(400,"INVALID_ARGUMENT","编号无效");return s;}
}
