package com.tradeflow.user.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.tradeflow.web.JwtSupport;
import com.tradeflow.web.SecurityErrors;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.Base64;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/** 用户认证入口；JWT只签发短期访问令牌，内部服务凭证由独立接口校验。 */
@Configuration(proxyBeanMethods=false)
public class UserSecurity {
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean public JwtDecoder userJwtDecoder(Environment env) throws Exception {
        return JwtSupport.decoder(env.getRequiredProperty("tradeflow.security.public-key"),env.getRequiredProperty("tradeflow.security.issuer"));
    }
    /** 私钥仅用户服务读取；生成的Token固定RS256，不与内部服务凭证混用。 */
    @Bean public JwtEncoder accessTokenEncoder(Environment env) throws Exception {
        String pem=Files.readString(Path.of(env.getRequiredProperty("tradeflow.security.private-key")))
                .replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
        var key=(RSAPrivateCrtKey)KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var pub=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(key.getModulus(),key.getPublicExponent()));
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(pub).privateKey(key).keyID("tradeflow-access-v1").build())));
    }
    /** 公开注册登录，受保护接口验签后仍由应用复核最新权限；其他内部路径默认拒绝。 */
    @Bean public SecurityFilterChain userSecurityChain(HttpSecurity http,ObjectMapper mapper) throws Exception {
        http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable())
                .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a
                        .requestMatchers("/system/info","/actuator/health","/actuator/health/**","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST,"/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/email-code").permitAll()
                        .requestMatchers(HttpMethod.GET,"/internal/v1/users/*/auth-context","/internal/v1/merchants/*").permitAll()
                        .requestMatchers(HttpMethod.POST,"/internal/v1/users/*/notifications").permitAll()
                        .requestMatchers("/api/v1/users/me/**","/api/v1/merchant-applications/**","/api/v1/merchant-applications","/api/v1/merchants/**","/api/v1/merchants","/api/v1/admin/merchant-applications/**","/api/v1/admin/merchant-applications","/api/v1/admin/merchants/**","/api/v1/admin/merchants","/api/v1/admin/audits").authenticated()
                        .requestMatchers("/api/v1/users/me","/api/v1/users/me/capabilities","/api/v1/admin/users","/api/v1/admin/users/*",
                                "/api/v1/admin/users/*/roles","/api/v1/admin/merchant-members").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((r,s,e)->SecurityErrors.write(mapper,r,s,401,"UNAUTHORIZED","需要有效的登录令牌")))
                .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->SecurityErrors.write(mapper,r,s,401,"UNAUTHORIZED","需要有效的登录令牌"))
                        .accessDeniedHandler((r,s,x)->SecurityErrors.write(mapper,r,s,403,"FORBIDDEN","无权访问该入口")));
        return http.build();
    }
}
