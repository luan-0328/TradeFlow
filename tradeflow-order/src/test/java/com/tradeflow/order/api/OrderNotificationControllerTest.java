package com.tradeflow.order.api;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.tradeflow.common.BusinessException;
import com.tradeflow.order.infrastructure.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
/** 验证人工恢复的最新授权、状态前提和审计回滚，不把通知命令当成重放交易。 */
class OrderNotificationControllerTest {
 OrderAuthority authority;OrderNotificationMapper notifications;OrderMapper orders;PlatformTransactionManager manager;OrderNotificationController controller;Jwt jwt;
 @BeforeEach void setup(){authority=mock(OrderAuthority.class);notifications=mock(OrderNotificationMapper.class);orders=mock(OrderMapper.class);manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());controller=new OrderNotificationController(authority,notifications,orders,manager);jwt=Jwt.withTokenValue("verified").header("alg","RS256").subject("1").claim("token_version",1L).build();}
 Map<String,Object> manual(){return Map.of("id",7L,"state","MANUAL_REVIEW","order_no","ord_1","business_key","order-status:99");}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setAttribute("requestId","req_1");r.setAttribute("traceId","trace_1");return r;}
 @Test void revokedPlatformCannotInspectOrRestartTask(){when(authority.authenticate(jwt,true)).thenThrow(new BusinessException(401,"TOKEN_REVOKED","撤销"));assertThrows(BusinessException.class,()->controller.list(jwt,1,20,request()));assertThrows(BusinessException.class,()->controller.retry(jwt,"7",new OrderModels.Reason("核对原键后重启"),request()));verifyNoInteractions(notifications,orders);}
 @Test void retryPreservesOriginalBusinessKeyAndAuditsSameTask(){when(notifications.lock(7)).thenReturn(manual());when(notifications.restart(7)).thenReturn(1);var result=controller.retry(jwt,"7",new OrderModels.Reason("核对原键后重启"),request());assertEquals("order-status:99",result.data().get("businessKey"));assertEquals("READY",result.data().get("state"));verify(notifications).restart(7);verify(orders).audit(eq("PLATFORM"),eq("1"),eq("RESTART_ORDER_NOTIFICATION"),eq("ord_1"),eq("req_1"),eq("trace_1"),eq("核对原键后重启"),contains("7"));verify(manager).commit(any());}
 @Test void readyOrSucceededTaskCannotBeRestarted(){when(notifications.lock(7)).thenReturn(Map.of("state","SUCCEEDED"));assertThrows(BusinessException.class,()->controller.retry(jwt,"7",new OrderModels.Reason("重试"),request()));verify(notifications,never()).restart(anyLong());verifyNoInteractions(orders);}
 @Test void auditFailureRollsBackRestartTransaction(){when(notifications.lock(7)).thenReturn(manual());when(notifications.restart(7)).thenReturn(1);when(orders.audit(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("audit unavailable"));assertThrows(IllegalStateException.class,()->controller.retry(jwt,"7",new OrderModels.Reason("重试"),request()));verify(manager).rollback(any());verify(manager,never()).commit(any());}
 @Test void backendPaginationReturnsRealTotalAndOnlyManualAction(){when(notifications.page(20,20)).thenReturn(List.of(Map.of("id","7","state","MANUAL_REVIEW"),Map.of("id","6","state","SUCCEEDED")));when(notifications.count()).thenReturn(150L);var result=controller.list(jwt,2,20,request()).data();assertEquals(150,result.total());assertEquals(List.of("RETRY"),result.items().get(0).get("allowedActions"));assertEquals(List.of(),result.items().get(1).get("allowedActions"));}
}
