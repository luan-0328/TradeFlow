package com.tradeflow.settlement;

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
public class SettlementSecurity {
    @Bean public JwtDecoder settlementJwtDecoder(Environment env)throws Exception{return JwtSupport.decoder(env.getRequiredProperty("tradeflow.security.public-key"),env.getRequiredProperty("tradeflow.security.issuer"));}
    @Bean public SecurityFilterChain settlementSecurityChain(HttpSecurity http,ObjectMapper mapper)throws Exception{
        http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable())
          .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(a->a.requestMatchers("/system/info","/actuator/health","/actuator/health/**").permitAll().requestMatchers("/api/v1/admin/settlement/**","/api/v1/merchant/settlement/**").authenticated().anyRequest().denyAll())
          .oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((r,s,e)->SecurityErrors.write(mapper,r,s,401,"UNAUTHORIZED","需要有效登录令牌")))
          .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->SecurityErrors.write(mapper,r,s,401,"UNAUTHORIZED","需要有效登录令牌")).accessDeniedHandler((r,s,x)->SecurityErrors.write(mapper,r,s,403,"FORBIDDEN","无权访问该入口")));
        return http.build();
    }
}
