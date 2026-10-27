package com.tradeflow.product.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.product.api.ProductModels.ReceiptView;
import com.tradeflow.product.domain.ProductRows.IdempotencyRow;
import com.tradeflow.product.infrastructure.ProductMapper;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 升级后原键安全读取旧成功快照；旧摘要、商家事实和终态三项都符合才允许兼容。 */
class LegacyIdempotencyTest {
    private ProductMapper mapper;private IdempotentWork work;private ObjectMapper json;
    @BeforeEach void setup() {
        mapper=mock(ProductMapper.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());json=new ObjectMapper();work=new IdempotentWork(mapper,json,manager);
    }
    private void legacy(String owner,String state) throws Exception {
        var result=new ReceiptView("rcp_old","APPLIED");String snapshot=json.writeValueAsString(result);
        when(mapper.lockRequest("MERCHANT","1","ADJUST_INVENTORY","old-key")).thenReturn(new IdempotencyRow(work.hash(Map.of("delta",1)),state,"rcp_old",snapshot));
        when(mapper.legacyRequestMerchant("ADJUST_INVENTORY","rcp_old",snapshot)).thenReturn(owner);
    }
    @Test void originalInputAndProvenOwnerReplayWithoutUpdatingOldDigestOrBusiness() throws Exception {
        legacy("merchant_a","SUCCEEDED");
        var result=work.runScoped("MERCHANT","1","ADJUST_INVENTORY","old-key","merchant_a",Map.of("delta",1),ReceiptView.class,no->{throw new AssertionError("旧成功请求禁止重做业务");});
        assertThat(result.businessNo()).isEqualTo("rcp_old");verify(mapper,never()).completed(any(),any(),any(),any(),any());
    }
    @Test void otherMerchantOrMissingProofCannotReceiveOldSnapshot() throws Exception {
        legacy("merchant_a","SUCCEEDED");
        assertThatThrownBy(()->work.runScoped("MERCHANT","1","ADJUST_INVENTORY","old-key","merchant_b",Map.of("delta",1),ReceiptView.class,no->{throw new AssertionError();}))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        legacy(null,"SUCCEEDED");
        assertThatThrownBy(()->work.runScoped("MERCHANT","1","ADJUST_INVENTORY","old-key","merchant_a",Map.of("delta",1),ReceiptView.class,no->{throw new AssertionError();})).isInstanceOf(BusinessException.class);
    }
    @Test void changedInputAndIncompleteOldRequestNeverEnterLegacyReplay() throws Exception {
        legacy("merchant_a","SUCCEEDED");
        assertThatThrownBy(()->work.runScoped("MERCHANT","1","ADJUST_INVENTORY","old-key","merchant_a",Map.of("delta",2),ReceiptView.class,no->{throw new AssertionError();})).isInstanceOf(BusinessException.class);
        legacy("merchant_a","PROCESSING");
        assertThatThrownBy(()->work.runScoped("MERCHANT","1","ADJUST_INVENTORY","old-key","merchant_a",Map.of("delta",1),ReceiptView.class,no->{throw new AssertionError();})).isInstanceOf(BusinessException.class);
        verify(mapper,never()).legacyRequestMerchant(any(),any(),any());
    }
}
