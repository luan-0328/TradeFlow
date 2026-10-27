package com.tradeflow.product.infrastructure;

import com.tradeflow.discovery.ServiceEndpoints;
import com.tradeflow.common.*;
import com.tradeflow.contracts.RefundContracts.ReturnReceipt;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/** 按Nacos订单服务名读取不可撤销验收事实；只在商品事务外调用，无实例或超时不得推定已验收。 */
@Component
public class ReturnReceiptClient {
 private final RestClient client;
 private final ServiceEndpoints endpoints;private final String token;
 public ReturnReceiptClient(Environment env,ServiceEndpoints endpoints){var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());factory.setReadTimeout(Duration.ofSeconds(3));this.endpoints=endpoints;client=RestClient.builder().requestFactory(factory).build();token=env.getRequiredProperty("tradeflow.internal.product-token");}
 /** 使用商品独立身份读取收据，RPC失败保留订单恢复任务以后重试，不在本地开启事务。 */
 public ReturnReceipt load(String receipt){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("禁止在商品事务内查询订单收据");
  try{var response=client.get().uri(endpoints.resolve("http://tradeflow-order/internal/v1/return-receipts/"+org.springframework.web.util.UriUtils.encodePathSegment(receipt,java.nio.charset.StandardCharsets.UTF_8))).headers(headers->com.tradeflow.observability.TracePropagation.headers().forEach(headers::set)).header("X-Caller-Service","product").header("X-Service-Token",token).retrieve().body(new ParameterizedTypeReference<ApiResponse<ReturnReceipt>>(){});if(response==null||!"OK".equals(response.code())||response.data()==null)throw new IllegalStateException("验收收据响应无效");return response.data();}
  catch(Exception failure){throw new BusinessException(503,"RETURN_RECEIPT_UNAVAILABLE","暂时无法确认验收收据，请按原收据重试");}
 }
}
