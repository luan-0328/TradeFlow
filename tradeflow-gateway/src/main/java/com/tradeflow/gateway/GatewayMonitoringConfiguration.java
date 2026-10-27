package com.tradeflow.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

/** WebFlux只读监控安全链，独立凭据且仅开放精确指标路径，不将管理端点代理给下游。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="tradeflow.observability",name="enabled",havingValue="true",matchIfMissing=true)
public class GatewayMonitoringConfiguration {
 /** 路由URI标签有限集合，避免原始业务路径造成高基数指标。 */
 @Bean io.micrometer.core.instrument.config.MeterFilter gatewayBoundedUriTags(){return io.micrometer.core.instrument.config.MeterFilter.maximumAllowableTags("http.server.requests","uri",100,io.micrometer.core.instrument.config.MeterFilter.deny());}
 /** 指标抓取必须为GET及正确Bearer；业务鉴权链保持不变，令牌不进入日志。 */
 @Bean @Order(-100)
 SecurityWebFilterChain gatewayMonitoringSecurity(ServerHttpSecurity http,Environment env){byte[] expected=env.getRequiredProperty("tradeflow.monitoring-token").getBytes(StandardCharsets.UTF_8);if(expected.length<32)throw new IllegalStateException("监控凭据至少32字节");return http.securityMatcher(new PathPatternParserServerWebExchangeMatcher("/actuator/prometheus")).csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable).formLogin(ServerHttpSecurity.FormLoginSpec::disable).logout(ServerHttpSecurity.LogoutSpec::disable).authorizeExchange(a->a.anyExchange().permitAll()).addFilterAt((exchange,chain)->{String header=exchange.getRequest().getHeaders().getFirst("Authorization");boolean valid=exchange.getRequest().getMethod()==org.springframework.http.HttpMethod.GET&&header!=null&&header.startsWith("Bearer ")&&MessageDigest.isEqual(expected,header.substring(7).getBytes(StandardCharsets.UTF_8));if(!valid){exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);return exchange.getResponse().setComplete();}return chain.filter(exchange);},SecurityWebFiltersOrder.AUTHENTICATION).build();}
}
