package com.tradeflow.aftersale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.aftersale.application.*;
import com.tradeflow.aftersale.domain.Aftersale;
import com.tradeflow.aftersale.infrastructure.*;
import com.tradeflow.aftersale.infrastructure.client.*;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 退款原键重放仍须当前选店授权，历史幂等结果不能成为撤权后读取另一店售后的通行证。 */
class RefundReplayAuthorityTest {
    @Test void originalKeyCannotReadAnotherMerchantAfterMembershipRevocation() throws Exception { verifyReplay("merchant_b",false); }
    @Test void originalKeyStillReturnsSameResourceForCurrentMerchant() throws Exception { verifyReplay("merchant_a",true); }
    private void verifyReplay(String selected,boolean allowed) throws Exception {
        var db=mock(AftersaleStore.class);var jdbc=mock(JdbcTemplate.class);var authority=mock(Authority.class);
        var orders=mock(OrderClient.class);var refunds=mock(RefundClient.class);var json=new ObjectMapper();
        var jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("7").build();
        when(authority.check(jwt,"merchant")).thenReturn(new AuthContext("7","ACTIVE",List.of("USER"),List.of(selected),1,Map.of(selected,"OPERATOR")));
        when(db.jdbc()).thenReturn(jdbc);
        String no="as_"+"a".repeat(32);var reason=new AftersaleService.Reason("重试原退款");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(reason)));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("request_hash",hash,"aftersale_no",no)));
        var original=new Aftersale(no,"ord_original","buyer","merchant_a","REFUND_ONLY","原原因","REFUND_PROCESSING","rfd_original","NOT_SUBMITTED",100L,"CNY",null);
        when(db.get(no,false)).thenReturn(original);
        var service=new AftersaleService(db,authority,orders,refunds,mock(TransactionTemplate.class),json,
            new MockEnvironment().withProperty("tradeflow.internal.aftersale-token","fixture"),mock(EvidenceService.class));
        if(allowed)assertThat(service.retryRefund(jwt,no,"original_key",reason)).isEqualTo(original);
        else assertThatThrownBy(()->service.retryRefund(jwt,no,"original_key",reason)).isInstanceOf(BusinessException.class).hasMessage("售后不存在");
        verifyNoInteractions(orders,refunds);
    }
}
