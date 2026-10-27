package com.tradeflow.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/** 管理路径必须从独立管理端口访问；即使关闭指标采样，也不能在业务端口暴露或误报500。 */
public final class ManagementEndpointPortFilter extends OncePerRequestFilter implements Ordered {
    private final Environment environment;
    public ManagementEndpointPortFilter(Environment environment){this.environment=environment;}
    @Override public int getOrder(){return Ordered.HIGHEST_PRECEDENCE+1;}
    /** 使用启动后实际管理端口支持随机端口测试；没有配置独立端口时沿用Spring默认同端口行为。 */
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        Integer configured=environment.getProperty("management.server.port",Integer.class);
        Integer actual=environment.getProperty("local.management.port",Integer.class,configured);
        if((path.equals("/actuator")||path.startsWith("/actuator/"))&&configured!=null&&(actual==null||actual<1||request.getLocalPort()!=actual)){
            response.setStatus(403);response.setCharacterEncoding("UTF-8");response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"MANAGEMENT_PORT_REQUIRED\",\"message\":\"请从独立管理端口访问\"}");return;
        }
        chain.doFilter(request,response);
    }
}
