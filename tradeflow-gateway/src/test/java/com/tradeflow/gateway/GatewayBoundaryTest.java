package com.tradeflow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

@ActiveProfiles("test")
@SpringBootTest(classes = GatewayBoundaryTest.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayBoundaryTest {
    @SpringBootConfiguration @EnableAutoConfiguration @ComponentScan("com.tradeflow.gateway")
    static class Fixture {}
    @Autowired WebTestClient client;
    @MockitoBean(name = "gatewayJwtDecoder") ReactiveJwtDecoder jwtDecoder;
    @MockitoBean SharedEntryLimiter entryLimiter;
    static HttpServer upstream;

    /** 本类验证网关路由/安全边界；限流Lua另测，这里显式准入且不连接任何既有Redis。 */
    @org.junit.jupiter.api.BeforeEach void admitBoundaryRequests() {
        org.mockito.Mockito.when(entryLimiter.acquire(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(reactor.core.publisher.Mono.just(new SharedEntryLimiter.Decision(true,0)));
    }

    @DynamicPropertySource static void upstream(DynamicPropertyRegistry registry) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            Map<String, String> identity = new LinkedHashMap<>();
            for (String name : new String[]{"X-User-Id", "X-User-Roles", "X-Merchant-Id", "X-Internal-Token"}) {
                String value = exchange.getRequestHeaders().getFirst(name);
                if (value != null) identity.put(name, value);
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("path", exchange.getRequestURI().getPath(),
                    "identity", identity, "requestId", String.valueOf(exchange.getRequestHeaders().getFirst("X-Request-Id"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        // 测试注册表只提供本机临时实例；真实运行仍使用Nacos，不访问任何远程注册中心。
        registry.add("spring.cloud.discovery.client.simple.instances.tradeflow-user[0].uri",
                () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }

    @AfterAll static void stopUpstream() { if (upstream != null) upstream.stop(0); }

    @Test void routesToRealHttpUpstreamAndRemovesForgedIdentity() {
        client.get().uri("/api/v1/user/system/info")
            .header("X-User-Id", "platform-admin").header("X-User-Roles", "PLATFORM_ADMIN")
            .header("X-Merchant-Id", "merchant_selected").header("X-Internal-Token", "forged")
            .header("X-Request-Id", "gateway-review-123")
            .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Request-Id", "gateway-review-123")
            .expectBody().jsonPath("$.path").isEqualTo("/system/info")
            // 选店是业务输入，必须保留给下游权威成员校验；伪造用户身份和内部凭据仍清除。
            .jsonPath("$.identity.length()").isEqualTo(1)
            .jsonPath("$.identity['X-Merchant-Id']").isEqualTo("merchant_selected")
            .jsonPath("$.requestId").isEqualTo("gateway-review-123");
    }

    @Test void cannotReachInternalOrManagementPathsThroughGateway() {
        // 未登录应401；携带有效测试身份仍不能绕过公开路由白名单，应403。
        org.mockito.Mockito.when(jwtDecoder.decode("boundary-test-token")).thenReturn(reactor.core.publisher.Mono.just(
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("boundary-test-token")
                        .header("alg", "RS256").subject("boundary-test-user").build()));
        for (String path : new String[]{"/internal/v1/refunds", "/actuator/env", "/api/v1/user/internal/v1/refunds"}) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
            client.get().uri(path).headers(headers -> headers.setBearerAuth("boundary-test-token"))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("ENDPOINT_NOT_ENABLED");
        }
    }

    @Test void missingDiscoveredInstanceDoesNotFallBackToFixedAddress() {
        client.get().uri("/api/v1/product/system/info").exchange().expectStatus().isEqualTo(503);
    }
    /** 邮箱发送必须匿名可达用户服务，不能只加安全白名单却遗漏显式路由。 */
    @Test void anonymousEmailCodeRequestReachesUserServiceRoute() {
        client.post().uri("/api/v1/auth/email-code").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email","buyer@example.test"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/v1/auth/email-code");
        org.mockito.Mockito.verify(entryLimiter).acquire(org.mockito.ArgumentMatchers.eq("login"),org.mockito.ArgumentMatchers.startsWith("source:"));
    }
}
