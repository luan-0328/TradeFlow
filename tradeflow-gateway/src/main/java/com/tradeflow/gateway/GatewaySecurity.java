package com.tradeflow.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.ApiResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/** 交易入口独立验证JWT；角色与资源归属仍由业务服务复核最新事实。 */
@Configuration(proxyBeanMethods = false)
public class GatewaySecurity {
    /** 公钥本地读取，固定算法与用途；验签不在事件循环执行CPU计算。 */
    @Bean public ReactiveJwtDecoder gatewayJwtDecoder(Environment env) throws Exception {
        var decoder=GatewayJwtSupport.decoder(env.getRequiredProperty("tradeflow.security.public-key"),env.getRequiredProperty("tradeflow.security.issuer"));
        return token->Mono.fromCallable(()->decoder.decode(token)).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
    /** 开放已实现的认证/商品查询，其他交易入口需JWT；未来交易和内部路径默认拒绝。 */
    @Bean
    public SecurityWebFilterChain gatewaySecurityChain(ServerHttpSecurity http, ObjectMapper mapper) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(auth -> auth.pathMatchers("/", "/api/v1/system/services",
                        "/api/v1/*/system/info", "/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/email-code").permitAll()
                        .pathMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/products","/api/v1/products/*","/api/v1/products/*/images","/api/v1/product-images/*").permitAll()
                        .pathMatchers("/api/v1/users/me","/api/v1/users/me/capabilities","/api/v1/admin/users","/api/v1/admin/users/*","/api/v1/admin/users/*/roles",
                                "/api/v1/admin/merchant-members","/api/v1/merchant/products","/api/v1/merchant/products/*",
                                "/api/v1/merchant/skus/*","/api/v1/merchant/inventory/adjustments","/api/v1/merchant/inventory/ledger",
                                "/api/v1/merchant/inventory/conditions","/api/v1/merchant/inventory/condition-adjustments","/api/v1/merchant/inventory/condition-adjustments/requests/by-key").authenticated()
                        .pathMatchers("/api/v1/orders","/api/v1/orders/*","/api/v1/orders/requests/*","/api/v1/orders/*/cancel",
                                "/api/v1/admin/return-stock-tasks","/api/v1/admin/return-stock-tasks/*/retry","/api/v1/admin/order-tasks","/api/v1/admin/order-tasks/*/retry","/api/v1/admin/refund-tasks","/api/v1/admin/refund-tasks/*/retry").authenticated()
                        .pathMatchers("/api/v1/admin/reconciliation/runs","/api/v1/admin/reconciliation/runs/*","/api/v1/admin/reconciliation/runs/*/retry","/api/v1/admin/reconciliation/runs/*/observations","/api/v1/admin/reconciliation/issues","/api/v1/admin/reconciliation/issues/*","/api/v1/admin/reconciliation/issues/*/repair","/api/v1/admin/reconciliation/issues/*/observations","/api/v1/admin/reconciliation/actions/*","/api/v1/admin/reconciliation/actions/*/retry","/api/v1/admin/reconciliation/checks","/api/v1/payments","/api/v1/payments/*","/api/v1/payments/requests/by-key","/api/v1/admin/payment-ledger").authenticated()
                        .pathMatchers("/api/v1/merchant/orders","/api/v1/merchant/orders/*","/api/v1/merchant/orders/*/ship","/api/v1/merchant/orders/*/shipping-address","/api/v1/orders/*/receive",
                          "/api/v1/aftersales/preview","/api/v1/aftersales/*/materials","/api/v1/aftersales/*/return-shipment","/api/v1/merchant/aftersales/*/materials","/api/v1/merchant/aftersales/*/return-inspection","/api/v1/evidence-files","/api/v1/evidence-files/*","/api/v1/aftersales","/api/v1/aftersales/*","/api/v1/aftersales/requests/by-key","/api/v1/aftersales/*/cancel","/api/v1/merchant/aftersales","/api/v1/merchant/aftersales/*","/api/v1/merchant/aftersales/*/review","/api/v1/merchant/aftersales/*/retry-refund","/api/v1/admin/aftersale-tasks","/api/v1/admin/aftersale-tasks/*/retry").authenticated()
                        .pathMatchers("/api/v1/admin/order-messages/*","/api/v1/admin/order-messages/*/*/retry","/api/v1/admin/payment-messages/*","/api/v1/admin/payment-messages/*/*/retry","/api/v1/admin/aftersale-messages/*","/api/v1/admin/aftersale-messages/*/*/retry").authenticated()
                        .pathMatchers("/api/v1/users/me/**","/api/v1/merchant-applications","/api/v1/merchants","/api/v1/merchants/**","/api/v1/admin/merchant-applications","/api/v1/admin/merchant-applications/**","/api/v1/admin/merchants","/api/v1/admin/merchants/**","/api/v1/admin/audits","/api/v1/merchant/products/*/skus","/api/v1/merchant/products/*/images","/api/v1/merchant/products/*/images/*","/api/v1/merchant/product-images/*","/api/v1/trade-groups","/api/v1/trade-groups/**","/api/v1/shipping-quotes","/api/v1/merchant/shipping-rule","/api/v1/orders/*/timeline","/api/v1/disputes","/api/v1/admin/disputes","/api/v1/admin/disputes/*/decision","/api/v1/merchant/settlement/**","/api/v1/admin/settlement/**").authenticated()
                        .pathMatchers("/api/v1/admin/order-notifications","/api/v1/admin/order-notifications/*/retry").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((exchange,e)->unauthorized(mapper,exchange)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, exception) -> unauthorized(mapper, exchange))
                        .accessDeniedHandler((exchange, exception) -> deny(mapper, exchange)))
                .build();
    }

    /** 无效令牌返回401；不记录或回显Token。 */
    private Mono<Void> unauthorized(ObjectMapper mapper,ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes=mapper.writeValueAsBytes(ApiResponse.failure("UNAUTHORIZED","需要有效的登录令牌",
                    exchange.getAttribute("requestId"),exchange.getAttribute("traceId")));
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        } catch(JsonProcessingException e) { return Mono.error(e); }
    }

    /** 返回受控错误且附关联号；不向外暴露框架内部异常。 */
    private Mono<Void> deny(ObjectMapper mapper, ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var body = ApiResponse.failure("ENDPOINT_NOT_ENABLED", "该入口尚未启用或无权访问",
                exchange.getAttribute("requestId"), exchange.getAttribute("traceId"));
        try {
            return response.writeWith(Mono.just(response.bufferFactory().wrap(mapper.writeValueAsBytes(body))));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }
}
