package com.tradeflow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.ApiResponse;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 已匹配公开路由且JWT安全链通过后执行；Redis准入失败不触发下游副作用。 */
@Component
public class EntryProtectionFilter implements GlobalFilter,Ordered {
    private final SharedEntryLimiter limiter;
    private final ObjectMapper mapper;
    public EntryProtectionFilter(SharedEntryLimiter limiter,ObjectMapper mapper){this.limiter=limiter;this.mapper=mapper;}
    @Override public int getOrder(){return -100;}
    /** 分类按实际HTTP方法与入口路径；主体来自已验签JWT，匿名来源只取socket，绝不信任外部转发头。 */
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain){
        String category=category(exchange.getRequest().getMethod().name(),exchange.getRequest().getPath().value());
        if(category==null)return chain.filter(exchange);
        var remote=exchange.getRequest().getRemoteAddress();
        String source="source:"+(remote==null || remote.getAddress()==null?"unknown":remote.getAddress().getHostAddress());
        return exchange.getPrincipal().map(principal->principal instanceof JwtAuthenticationToken jwt?"user:"+jwt.getToken().getSubject():source)
                .defaultIfEmpty(source).flatMap(principal->limiter.acquire(category,principal)
                        .onErrorResume(failure->reply(exchange,503,"ENTRY_PROTECTION_UNAVAILABLE","入口保护依赖暂时不可用，请先查询原业务",5)
                                .then(Mono.empty()))
                        .flatMap(decision->decision.allowed()?chain.filter(exchange):reply(exchange,429,"RATE_LIMITED","请求过于频繁，请在"+decision.retryAfterSeconds()+"秒后重试",decision.retryAfterSeconds())));
    }
    /** 管理恢复含任务重试、消息重放、对账修复及商家退款重试；其他公开写操作也有保底额度。 */
    static String category(String method,String path){
        if(!path.startsWith("/api/v1/") || path.endsWith("/system/info"))return null;
        if("POST".equals(method) && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/email-code")))return "login";
        if("POST".equals(method) && path.equals("/api/v1/orders"))return "order";
        if("GET".equals(method) || "HEAD".equals(method))return "query";
        if(path.startsWith("/api/v1/admin/") || path.endsWith("/retry-refund"))return "recovery";
        return "write";
    }
    /** 429与依赖503都提供秒数退避；关联号沿用边界过滤器，错误体不包含Redis地址或令牌。 */
    private Mono<Void> reply(ServerWebExchange exchange,int status,String code,String message,long retry){
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("Retry-After",Long.toString(Math.max(1,retry)));
        try{return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(mapper.writeValueAsBytes(
                ApiResponse.failure(code,message,exchange.getAttribute("requestId"),exchange.getAttribute("traceId"))))));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){return Mono.error(e);}
    }
}
