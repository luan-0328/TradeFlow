package com.tradeflow.order.application;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.tradeflow.discovery.ServiceEndpoints;
import com.tradeflow.order.infrastructure.OrderNotificationMapper;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.client.RestClient;
/** 验证原键重复交接、ACK判定和预算隔离；真实任务行锁及多副本租约竞争需独立MySQL验收。 */
class OrderNotificationWorkerTest {
 OrderNotificationMapper db;ServiceEndpoints endpoints;OrderNotificationWorker worker;MockRestServiceServer server;
 @BeforeEach void setup(){db=mock(OrderNotificationMapper.class);endpoints=mock(ServiceEndpoints.class);when(endpoints.resolve(anyString())).thenAnswer(i->URI.create(i.getArgument(0,String.class).replace("http://tradeflow-user","http://localhost")));var tm=mock(PlatformTransactionManager.class);when(tm.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());var builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();worker=new OrderNotificationWorker(db,tm,endpoints,new MockEnvironment().withProperty("tradeflow.internal.order-token","order-only-token"),builder.build());when(db.claim(anyLong(),anyString())).thenReturn(1);when(db.finish(anyLong(),anyString(),anyLong(),anyString(),any())).thenReturn(1);}
 Map<String,Object> task(int attempts){return Map.of("id",7L,"source_log_id",99L,"claim_version",5L,"attempt_count",attempts,"business_key","order-status:99","user_id","1","order_no","ord_1","to_status","SHIPPED");}
 @Test void nullableSuccessPayloadIsValidNotificationAck(){when(db.due()).thenReturn(task(0));server.expect(requestTo("http://localhost/internal/v1/users/1/notifications")).andExpect(header("X-Caller-Service","order")).andExpect(content().json("{\"businessKey\":\"order-status:99\",\"title\":\"订单状态更新\",\"content\":\"订单 ord_1 状态更新为：已发货。请进入订单详情查看。\"}")).andRespond(withSuccess("{\"code\":\"OK\",\"data\":null}",MediaType.APPLICATION_JSON));worker.runOne();verify(db).finish(eq(7L),anyString(),eq(6L),eq("SUCCEEDED"),isNull());server.verify();}
 @Test void unknownResultRetriesOriginalKeyAndEventuallyFinishes(){when(db.due()).thenReturn(task(0),task(1));server.expect(requestTo("http://localhost/internal/v1/users/1/notifications")).andExpect(content().json("{\"businessKey\":\"order-status:99\"}")).andRespond(withServerError());server.expect(requestTo("http://localhost/internal/v1/users/1/notifications")).andExpect(content().json("{\"businessKey\":\"order-status:99\"}")).andRespond(withSuccess("{\"code\":\"OK\"}",MediaType.APPLICATION_JSON));worker.runOne();worker.runOne();verify(db).finish(eq(7L),anyString(),eq(6L),eq("READY"),eq("DELIVERY_UNCONFIRMED"));verify(db).finish(eq(7L),anyString(),eq(6L),eq("SUCCEEDED"),isNull());server.verify();}
 @Test void exhaustedCrashedLeaseBecomesManualWithoutFurtherRpc(){when(db.due()).thenReturn(task(12));worker.runOne();verify(db).finish(eq(7L),anyString(),eq(6L),eq("MANUAL_REVIEW"),eq("DELIVERY_BUDGET_EXHAUSTED"));verifyNoInteractions(endpoints);server.verify();}
 @Test void finalFailedAttemptEntersManualInsteadOfInfiniteRetry(){when(db.due()).thenReturn(task(11));server.expect(requestTo("http://localhost/internal/v1/users/1/notifications")).andRespond(withServerError());worker.runOne();verify(db).finish(eq(7L),anyString(),eq(6L),eq("MANUAL_REVIEW"),eq("DELIVERY_UNCONFIRMED"));server.verify();}
 @Test void noRpcCanRunWithinBusinessTransaction(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->worker.deliver(new OrderNotificationWorker.Claim(7,"owner",6,1,"order-status:99","1","ord_1","SHIPPED")));verifyNoInteractions(endpoints);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}}
}
