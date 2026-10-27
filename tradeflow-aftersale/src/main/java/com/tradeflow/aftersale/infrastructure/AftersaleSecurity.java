package com.tradeflow.aftersale.infrastructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.web.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
/** 直连售后端口也必须RS256验签，具体角色与归属由用例最新校验。 */
@Configuration(proxyBeanMethods=false)
public class AftersaleSecurity {
 @Bean JwtDecoder aftersaleJwtDecoder(Environment env)throws Exception{return JwtSupport.decoder(env.getRequiredProperty("tradeflow.security.public-key"),env.getRequiredProperty("tradeflow.security.issuer"));}
 @Bean SecurityFilterChain aftersaleSecurityChain(HttpSecurity http,ObjectMapper json)throws Exception{http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(a->a.requestMatchers("/system/info","/actuator/health","/actuator/health/**","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll().requestMatchers("/api/v1/evidence-files","/api/v1/evidence-files/*","/api/v1/aftersales","/api/v1/aftersales/**","/api/v1/merchant/aftersales","/api/v1/merchant/aftersales/**","/api/v1/admin/aftersale-tasks","/api/v1/admin/aftersale-tasks/**").authenticated().requestMatchers("/api/v1/admin/aftersale-messages/*","/api/v1/admin/aftersale-messages/*/*/retry").authenticated().requestMatchers("/api/v1/disputes","/api/v1/admin/disputes","/api/v1/admin/disputes/*/decision").authenticated().anyRequest().denyAll()).oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((r,s,e)->SecurityErrors.write(json,r,s,401,"UNAUTHORIZED","需要有效登录令牌"))).exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->SecurityErrors.write(json,r,s,401,"UNAUTHORIZED","需要有效登录令牌")).accessDeniedHandler((r,s,x)->SecurityErrors.write(json,r,s,403,"FORBIDDEN","无权访问")));return http.build();}
}
