package com.tradeflow.payment.api;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.tradeflow.common.BusinessException;
import com.tradeflow.payment.infrastructure.SettlementFactsMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
/** 独立调用凭据、调用者与分页边界必须在读取资金事实前验证。 */
class SettlementFactsControllerTest {
 @Test void otherServiceCannotReuseSettlementToken(){var db=mock(SettlementFactsMapper.class);var c=new SettlementFactsController(db,new MockEnvironment().withProperty("tradeflow.internal.settlement-token","independent-secret"));assertThrows(BusinessException.class,()->c.facts("order","independent-secret",0,100,new MockHttpServletRequest()));verifyNoInteractions(db);}
 @Test void unconfiguredTokenRejectsEvenEmptyCredential(){var db=mock(SettlementFactsMapper.class);var c=new SettlementFactsController(db,new MockEnvironment());assertThrows(BusinessException.class,()->c.facts("settlement","",0,100,new MockHttpServletRequest()));verifyNoInteractions(db);}
 @Test void pageUsesLastImmutableLedgerIdAndPreservesEmptyCursor(){var db=mock(SettlementFactsMapper.class);var c=new SettlementFactsController(db,new MockEnvironment().withProperty("tradeflow.internal.settlement-token","secret"));when(db.page(10,1)).thenReturn(List.of(Map.of("factId",15L)));var result=c.facts("settlement","secret",10,1,new MockHttpServletRequest());assertEquals(15L,result.data().get("nextAfter"));assertEquals(true,result.data().get("hasMore"));assertThrows(BusinessException.class,()->c.facts("settlement","secret",0,101,new MockHttpServletRequest()));}
}
