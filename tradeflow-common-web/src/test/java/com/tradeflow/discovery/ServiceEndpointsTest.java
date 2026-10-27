package com.tradeflow.discovery;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 离线验证实例切换、未知结果边界与编码；不冒充真实Nacos集成测试。 */
class ServiceEndpointsTest {
    @Test void selectsCurrentInstanceAndPreservesEncodedPathAndQuery() {
        var loadBalancer = mock(LoadBalancerClient.class);
        when(loadBalancer.choose("tradeflow-order")).thenReturn(
                new DefaultServiceInstance("one", "tradeflow-order", "127.0.0.2", 8083, false),
                new DefaultServiceInstance("two", "tradeflow-order", "127.0.0.3", 9083, false));
        var endpoints = new ServiceEndpoints(loadBalancer);
        String logical = "http://tradeflow-order/internal/v1/return-receipts/RET%3Aas_123?after=10&pageSize=20";
        assertThat(endpoints.resolve(logical)).isEqualTo(URI.create("http://127.0.0.2:8083/internal/v1/return-receipts/RET%3Aas_123?after=10&pageSize=20"));
        assertThat(endpoints.resolve(logical).getPort()).isEqualTo(9083);
        verify(loadBalancer, times(2)).choose("tradeflow-order");
    }

    @Test void missingInstanceFailsWithoutDirectAddressFallbackOrRetry() {
        var loadBalancer = mock(LoadBalancerClient.class);
        var endpoints = new ServiceEndpoints(loadBalancer);
        assertThatThrownBy(() -> endpoints.resolve("http://tradeflow-payment/callbacks/v1/payments"))
                .isInstanceOf(IllegalStateException.class).hasMessage("DISCOVERY_INSTANCE_UNAVAILABLE");
        verify(loadBalancer, times(1)).choose("tradeflow-payment");
    }

    @Test void refusesNetworkSelectionInsideLocalTransaction() {
        var loadBalancer = mock(LoadBalancerClient.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> new ServiceEndpoints(loadBalancer).resolve("http://tradeflow-user/internal/v1/users/u/auth-context"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("事务");
            verifyNoInteractions(loadBalancer);
        } finally { TransactionSynchronizationManager.clear(); }
    }

    @Test void rejectsArbitraryHostsAndCredentials() {
        var loadBalancer = mock(LoadBalancerClient.class);
        var endpoints = new ServiceEndpoints(loadBalancer);
        for (String address : new String[]{"http://127.0.0.1:8081/test", "http://tradeflow-user@evil.test/test", "https://tradeflow-user/test", "http://tradeflow-user:8081/test"}) {
            assertThatThrownBy(() -> endpoints.resolve(address)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(loadBalancer);
    }
}
