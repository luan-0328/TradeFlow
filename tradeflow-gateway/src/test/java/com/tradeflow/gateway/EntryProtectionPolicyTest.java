package com.tradeflow.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实公开路径必须命中共享入口保护，禁止用错误路径制造限流覆盖。 */
class EntryProtectionPolicyTest {
    @Test void coversActualRecoveryAndQueryPaths() {
        assertThat(EntryProtectionFilter.category("POST", "/api/v1/auth/login")).isEqualTo("login");
        assertThat(EntryProtectionFilter.category("POST", "/api/v1/auth/email-code")).isEqualTo("login");
        assertThat(EntryProtectionFilter.category("POST", "/api/v1/orders")).isEqualTo("order");
        assertThat(EntryProtectionFilter.category("GET", "/api/v1/orders/requests/example")).isEqualTo("query");
        assertThat(EntryProtectionFilter.category("POST", "/api/v1/admin/order-messages/inbox/example/retry")).isEqualTo("recovery");
        assertThat(EntryProtectionFilter.category("POST", "/api/v1/admin/reconciliation/issues/example/repair")).isEqualTo("recovery");
        assertThat(EntryProtectionFilter.category("POST", "/api/v1/merchant/aftersales/example/retry-refund")).isEqualTo("recovery");
        assertThat(EntryProtectionFilter.category("POST", "/internal/v1/order/tasks")).isNull();
    }
}
