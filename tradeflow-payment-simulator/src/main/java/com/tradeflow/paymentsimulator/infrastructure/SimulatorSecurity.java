package com.tradeflow.paymentsimulator.infrastructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.web.SecurityErrors;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
/** 通道服务凭据和管理凭据独立；管理用例额外限制dev环境。 */
@Configuration(proxyBeanMethods=false)
public class SimulatorSecurity {
 private final byte[] channelToken,adminToken;private final boolean dev;
 public SimulatorSecurity(Environment env){
  channelToken=secret(env,"tradeflow.internal.channel-token");adminToken=secret(env,"tradeflow.simulator-admin-token");
  dev=env.matchesProfiles("dev")&&!env.matchesProfiles("prod");
 }
 private byte[] secret(Environment e,String key){byte[] b=e.getRequiredProperty(key).getBytes(StandardCharsets.UTF_8);if(b.length<32)throw new IllegalStateException(key+"至少32字节");return b;}
 /** 所有channel入口在业务调用前校验调用者和独立令牌，常量时间比较不泄露前缀。 */
 public void channel(HttpServletRequest r){if(!"payment".equals(r.getHeader("X-Caller-Service"))||!matches(channelToken,r.getHeader("X-Service-Token")))throw new BusinessException(403,"CHANNEL_FORBIDDEN","通道调用凭据无效");}
 /** 故障配置必须显式开发环境及独立管理令牌，prod不能通过配置暴露入口。 */
 public void admin(HttpServletRequest r){if(!dev||!matches(adminToken,r.getHeader("X-Simulator-Admin-Token")))throw new BusinessException(403,"SIMULATOR_ADMIN_FORBIDDEN","模拟器管理入口不可用");}
 private boolean matches(byte[] expected,String actual){return actual!=null&&MessageDigest.isEqual(expected,actual.getBytes(StandardCharsets.UTF_8));}
 /** URL级只开放已实现入口，实际服务鉴权由控制器首步执行，其他路径统一拒绝。 */
 @Bean public SecurityFilterChain simulatorSecurityChain(HttpSecurity http,ObjectMapper mapper)throws Exception{
  http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable())
   .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/system/info","/actuator/health","/actuator/health/**","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html","/channel/v1/payments","/channel/v1/payments/*","/channel/v1/payments/*/close","/simulator-admin/v1/scenarios","/channel/v1/refunds","/channel/v1/refunds/*","/simulator-admin/v1/refund-scenarios","/channel/v1/bill-snapshots","/channel/v1/bill-snapshots/*","/channel/v1/bill-snapshots/*/lines","/simulator-admin/v1/bill-projections").permitAll().anyRequest().denyAll())
   .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->SecurityErrors.write(mapper,r,s,403,"FORBIDDEN","入口未开放")).accessDeniedHandler((r,s,x)->SecurityErrors.write(mapper,r,s,403,"FORBIDDEN","入口未开放")));
  return http.build();
 }
}

