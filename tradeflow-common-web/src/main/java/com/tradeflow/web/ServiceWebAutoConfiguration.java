package com.tradeflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

/** 仅供 Servlet 业务服务使用；网关不得依赖本模块，避免混合 MVC/WebFlux。 */
@AutoConfiguration(before = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(ServiceProperties.class)
@Import({ServiceInfoController.class, ApplicationExceptionHandler.class})
public class ServiceWebAutoConfiguration {
    /** 管理端口隔离独立于指标采样开关。 */
    @Bean public ManagementEndpointPortFilter managementEndpointPortFilter(org.springframework.core.env.Environment environment) { return new ManagementEndpointPortFilter(environment); }
    @Bean public org.springdoc.core.customizers.OpenApiCustomizer apiSecurityDocumentation() { return ApiDocumentation.security(); }
    @Bean public RequestTraceFilter requestTraceFilter() { return new RequestTraceFilter(); }

    /** 禁止框架生成临时登录密码；账号认证由各服务显式安全链处理。 */
    @Bean @ConditionalOnMissingBean(UserDetailsService.class)
    public UserDetailsService noDefaultLoginUsers() {
        return username -> { throw new UsernameNotFoundException("不提供默认表单登录账号"); };
    }

    /** 骨架只开放健康、模块信息和开发文档，其余业务与内部命令默认拒绝。 */
    @Bean @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain skeletonSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http.csrf(csrf -> csrf.disable()).httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/system/info", "/actuator/health", "/actuator/health/**",
                                "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> deny(mapper, request, response))
                        .accessDeniedHandler((request, response, exception) -> deny(mapper, request, response)));
        return http.build();
    }

    /** 未实现功能明确拒绝，不根据伪造用户头或默认账号产生业务授权。 */
    private void deny(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        response.setStatus(403);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiResponse.failure("ENDPOINT_NOT_ENABLED",
                "当前为工程骨架，该业务入口尚未启用", (String) request.getAttribute("requestId"),
                (String) request.getAttribute("traceId")));
    }
}
