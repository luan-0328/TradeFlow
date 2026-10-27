package com.tradeflow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Redis不可用与限额拒绝均不得触发任何下游副作用。 */
class EntryProtectionFilterTest {
    @Test void redisFailureFailsClosedWithRetryAfterAndNeverForwards() {
        SharedEntryLimiter limiter=mock(SharedEntryLimiter.class);
        when(limiter.acquire(anyString(),anyString())).thenReturn(Mono.error(new IllegalStateException("test unavailable")));
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("127.0.0.3",12345)).header("X-Forwarded-For","203.0.113.8").build());
        AtomicBoolean forwarded=new AtomicBoolean();
        StepVerifier.create(new EntryProtectionFilter(limiter,new ObjectMapper()).filter(exchange,e->{forwarded.set(true);return Mono.empty();})).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("ENTRY_PROTECTION_UNAVAILABLE");
        assertThat(forwarded).isFalse();
        verify(limiter).acquire("login","source:127.0.0.3");
    }
    @Test void limitReturnsActualWindowBackoffAndNeverForwards() {
        SharedEntryLimiter limiter=mock(SharedEntryLimiter.class);
        when(limiter.acquire(anyString(),anyString())).thenReturn(Mono.just(new SharedEntryLimiter.Decision(false,37)));
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/orders").build());
        AtomicBoolean forwarded=new AtomicBoolean();
        StepVerifier.create(new EntryProtectionFilter(limiter,new ObjectMapper()).filter(exchange,e->{forwarded.set(true);return Mono.empty();})).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("37");
        assertThat(forwarded).isFalse();
    }
    @Test void acceptedRequestIsForwardedExactlyOnce() {
        SharedEntryLimiter limiter=mock(SharedEntryLimiter.class);
        when(limiter.acquire(anyString(),anyString())).thenReturn(Mono.just(new SharedEntryLimiter.Decision(true,0)));
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products").build());
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        StepVerifier.create(new EntryProtectionFilter(limiter,new ObjectMapper()).filter(exchange,e->{calls.incrementAndGet();return Mono.empty();})).verifyComplete();
        assertThat(calls.get()).isEqualTo(1);
    }
}
