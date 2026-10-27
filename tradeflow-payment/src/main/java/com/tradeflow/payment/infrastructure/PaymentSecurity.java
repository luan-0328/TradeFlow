package com.tradeflow.payment.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.web.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** 公共入口独立RS256验签；内部接口与回调仅开放传输层，应用层分别强制独立凭据/HMAC。 */
@Configuration(proxyBeanMethods=false)
public class PaymentSecurity {
    @Bean public JwtDecoder paymentJwtDecoder(Environment env)throws Exception{return JwtSupport.decoder(env.getRequiredProperty("tradeflow.security.public-key"),env.getRequiredProperty("tradeflow.security.issuer"));}
    @Bean public SecurityFilterChain paymentSecurityChain(HttpSecurity http,ObjectMapper mapper)throws Exception{
        http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable())
          .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(a->a.requestMatchers("/internal/v1/settlement/facts","/system/info","/actuator/health","/actuator/health/**","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html","/internal/v1/payments/*","/internal/v1/payments/*/close","/internal/v1/payments/*/query-channel","/callbacks/v1/payments","/internal/v1/refunds","/internal/v1/refunds/*","/callbacks/v1/refunds").permitAll()
            .requestMatchers("/api/v1/admin/reconciliation/runs","/api/v1/admin/reconciliation/runs/*","/api/v1/admin/reconciliation/runs/*/retry","/api/v1/admin/reconciliation/runs/*/observations","/api/v1/admin/reconciliation/issues","/api/v1/admin/reconciliation/issues/*","/api/v1/admin/reconciliation/issues/*/repair","/api/v1/admin/reconciliation/issues/*/observations","/api/v1/admin/reconciliation/actions/*","/api/v1/admin/reconciliation/actions/*/retry","/api/v1/admin/reconciliation/checks","/api/v1/payments","/api/v1/payments/*","/api/v1/payments/requests/by-key","/api/v1/admin/payment-ledger").authenticated().requestMatchers("/api/v1/admin/payment-messages/*","/api/v1/admin/payment-messages/*/*/retry").authenticated().anyRequest().denyAll())
          .oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((r,s,e)->SecurityErrors.write(mapper,r,s,401,"UNAUTHORIZED","需要有效登录令牌")))
          .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->SecurityErrors.write(mapper,r,s,401,"UNAUTHORIZED","需要有效登录令牌")).accessDeniedHandler((r,s,x)->SecurityErrors.write(mapper,r,s,403,"FORBIDDEN","无权访问该入口")));
        return http.build();
    }
}
