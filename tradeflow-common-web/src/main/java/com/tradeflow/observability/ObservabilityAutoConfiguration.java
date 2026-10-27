package com.tradeflow.observability;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.sql.DataSource;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** Servlet只读管理安全链与指标采样；Gateway采用独立WebFlux实现，不能依赖本模块。 */
@AutoConfiguration(after=DataSourceAutoConfiguration.class,afterName="org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration")
@ConditionalOnProperty(prefix="tradeflow.observability",name="enabled",havingValue="true",matchIfMissing=true)
public class ObservabilityAutoConfiguration {
 /** 指标端点使用独立只读凭据，业务JWT与服务调用token均不能代替；不记录请求头。 */
 @Bean @Order(-100)
 SecurityFilterChain monitoringSecurity(HttpSecurity http,Environment env)throws Exception{byte[] expected=env.getRequiredProperty("tradeflow.monitoring-token").getBytes(StandardCharsets.UTF_8);if(expected.length<32)throw new IllegalStateException("监控凭据至少32字节");http.securityMatcher(EndpointRequest.to("prometheus")).csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(a->a.anyRequest().permitAll()).addFilterBefore(new OncePerRequestFilter(){
  protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{String value=request.getHeader("Authorization");boolean valid="GET".equals(request.getMethod())&&value!=null&&value.startsWith("Bearer ")&&MessageDigest.isEqual(expected,value.substring(7).getBytes(StandardCharsets.UTF_8));if(!valid){response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"code\":\"MONITORING_UNAUTHORIZED\"}");return;}chain.doFilter(request,response);}
 },AuthorizationFilter.class);return http.build();}
 /** 路由标签设硬上限，防止错误配置将原始业务URL扩展为无限高基数。 */
 @Bean io.micrometer.core.instrument.config.MeterFilter boundedHttpUriTags(){return io.micrometer.core.instrument.config.MeterFilter.maximumAllowableTags("http.server.requests","uri",100,io.micrometer.core.instrument.config.MeterFilter.deny());}
 /** 不存在DataSource的保留测试上下文不注册业务SQL采样，避免伪造指标。 */
 @Bean(destroyMethod="close") @ConditionalOnBean({DataSource.class,MeterRegistry.class})
 BusinessMetricsSampler businessMetricsSampler(DataSource source,MeterRegistry registry,Environment env){return new BusinessMetricsSampler(source,registry,env.getRequiredProperty("tradeflow.service.code"));}
}
