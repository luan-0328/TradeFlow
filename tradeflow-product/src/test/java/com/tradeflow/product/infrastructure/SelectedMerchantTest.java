package com.tradeflow.product.infrastructure;

import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.discovery.ServiceEndpoints;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 店铺选择不是权限，测试跨店伪造、缺省歧义和撤销后的最新成员事实。 */
class SelectedMerchantTest {
    private AuthorityClient authority;
    @BeforeEach void setup() {
        authority=new AuthorityClient(new MockEnvironment().withProperty("tradeflow.internal.product-token","test-product")
                .withProperty("tradeflow.internal.order-token","test-order"),mock(ServiceEndpoints.class));
    }
    @AfterEach void cleanup() {RequestContextHolder.resetRequestAttributes();}
    private AuthContext context(List<String> merchants) {return new AuthContext("1","ACTIVE",List.of("MERCHANT_ADMIN"),merchants,1,merchants.stream().collect(java.util.stream.Collectors.toMap(java.util.function.Function.identity(),id -> "ADMIN")));}
    private void select(String id) {var request=new MockHttpServletRequest();request.addHeader("X-Merchant-Id",id);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
    @Test void uniqueMembershipDefaultsButMultipleMembershipsRequireSelection() {
        assertThat(authority.selectedMerchant(context(List.of("merchant_a")))).isEqualTo("merchant_a");
        assertThatThrownBy(()->authority.selectedMerchant(context(List.of("merchant_a","merchant_b"))))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("MERCHANT_SELECTION_REQUIRED"));
    }
    @Test void selectedMerchantMustStillBeInLatestMembership() {
        select("merchant_b");assertThat(authority.selectedMerchant(context(List.of("merchant_a","merchant_b")))).isEqualTo("merchant_b");
        assertThatThrownBy(()->authority.selectedMerchant(context(List.of("merchant_a"))))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("MERCHANT_FORBIDDEN"));
    }
    @Test void forgedAndMalformedHeadersCannotSelectAnotherStore() {
        select("merchant_intruder");assertThatThrownBy(()->authority.selectedMerchant(context(List.of("merchant_a")))).isInstanceOf(BusinessException.class);
        select("../merchant_a");assertThatThrownBy(()->authority.selectedMerchant(context(List.of("merchant_a")))).isInstanceOf(BusinessException.class);
    }
}
