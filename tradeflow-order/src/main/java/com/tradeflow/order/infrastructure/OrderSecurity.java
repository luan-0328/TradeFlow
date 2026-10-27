package com.tradeflow.order.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.web.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** 订单服务自己验签，不能通过直连端口绕过网关身份校验。 */
@Configuration(proxyBeanMethods=false)
public class OrderSecurity {
    @Bean public JwtDecoder orderJwtDecoder(Environment env) throws Exception {
        return JwtSupport.decoder(env.getRequiredProperty("tradeflow.security.public-key"),env.getRequiredProperty("tradeflow.security.issuer"));
    }
    /** 仅开放B2实现接口；角色、撤销版本和订单归属仍由用例复核。 */
    @Bean public SecurityFilterChain orderSecurityChain(HttpSecurity http,ObjectMapper mapper) throws Exception {
        http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable())
                .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a
                        .requestMatchers("/system/info","/actuator/health","/actuator/health/**","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll()
                        .requestMatchers("/internal/v1/settlement/orders/*").permitAll()
                        .requestMatchers("/api/v1/trade-groups","/api/v1/trade-groups/**","/api/v1/shipping-quotes","/api/v1/merchant/shipping-rule","/api/v1/orders/*/timeline").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/v1/orders/*/payment-intent").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,"/internal/v1/orders/*/payment-results").permitAll()
                        .requestMatchers("/internal/v1/orders/*/aftersale-intent","/internal/v1/aftersale-authorizations","/internal/v1/aftersale-authorizations/preview","/internal/v1/refund-authorizations/*/return-receipt","/internal/v1/return-receipts/*","/internal/v1/refund-authorizations/*","/internal/v1/refund-authorizations/*/activate","/internal/v1/refund-authorizations/*/terminate","/internal/v1/orders/*/refund-results").permitAll()
                        .requestMatchers("/api/v1/merchant/orders","/api/v1/merchant/orders/*","/api/v1/merchant/orders/*/ship","/api/v1/merchant/orders/*/shipping-address","/api/v1/orders/*/receive").authenticated()
                        .requestMatchers("/api/v1/orders","/api/v1/orders/*","/api/v1/orders/requests/*","/api/v1/orders/*/cancel",
                                "/api/v1/admin/order-tasks","/api/v1/admin/order-tasks/*/retry","/api/v1/admin/return-stock-tasks","/api/v1/admin/return-stock-tasks/*/retry","/api/v1/admin/refund-tasks","/api/v1/admin/refund-tasks/*/retry").authenticated().requestMatchers("/api/v1/admin/order-messages/*","/api/v1/admin/order-messages/*/*/retry").authenticated().requestMatchers("/api/v1/admin/order-notifications","/api/v1/admin/order-notifications/*/retry").authenticated().anyRequest().denyAll())
                .oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((r,s,e)->SecurityErrors.write(mapper,r,s,401,"UNAUTHORIZED","需要有效的登录令牌")))
                .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->SecurityErrors.write(mapper,r,s,401,"UNAUTHORIZED","需要有效的登录令牌"))
                        .accessDeniedHandler((r,s,x)->SecurityErrors.write(mapper,r,s,403,"FORBIDDEN","无权访问该入口")));
        return http.build();
    }
}
