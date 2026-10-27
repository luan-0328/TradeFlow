package com.tradeflow.product.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.web.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** 商品独立验签，不能绕过网关后用自造用户头操作库存。 */
@Configuration(proxyBeanMethods=false)
public class ProductSecurity {
    @Bean public JwtDecoder productJwtDecoder(Environment env) throws Exception {
        return JwtSupport.decoder(env.getRequiredProperty("tradeflow.security.public-key"),env.getRequiredProperty("tradeflow.security.issuer"));
    }
    /** 只开放已实现路由；内部报价在Controller验证独立服务凭证后执行。 */
    @Bean public SecurityFilterChain productSecurityChain(HttpSecurity http,ObjectMapper mapper) throws Exception {
        http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable())
                .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a
                        .requestMatchers("/system/info","/actuator/health","/actuator/health/**","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/v1/products","/api/v1/products/*","/api/v1/products/*/images","/api/v1/product-images/*").permitAll()
                        .requestMatchers(HttpMethod.POST,"/internal/v1/inventory/unshipped-restocks","/internal/v1/inventory/return-restocks","/internal/v1/quotes","/internal/v1/reservations","/internal/v1/reservations/*/release","/internal/v1/reservations/*/confirm").permitAll()
                        .requestMatchers(HttpMethod.GET,"/internal/v1/reservations/*").permitAll()
                        .requestMatchers("/api/v1/merchant/product-images/*","/api/v1/merchant/products","/api/v1/merchant/products/*","/api/v1/merchant/products/*/skus","/api/v1/merchant/products/*/images","/api/v1/merchant/products/*/images/*","/api/v1/merchant/skus/*",
                                "/api/v1/merchant/inventory/adjustments","/api/v1/merchant/inventory/ledger","/api/v1/merchant/inventory/conditions","/api/v1/merchant/inventory/condition-adjustments","/api/v1/merchant/inventory/condition-adjustments/requests/by-key").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((r,s,e)->SecurityErrors.write(mapper,r,s,401,"UNAUTHORIZED","需要有效的登录令牌")))
                .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->SecurityErrors.write(mapper,r,s,401,"UNAUTHORIZED","需要有效的登录令牌"))
                        .accessDeniedHandler((r,s,x)->SecurityErrors.write(mapper,r,s,403,"FORBIDDEN","无权访问该入口")));
        return http.build();
    }
}
