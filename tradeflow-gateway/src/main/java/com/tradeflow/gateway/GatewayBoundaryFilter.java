package com.tradeflow.gateway;

import com.tradeflow.common.TraceIds;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 入口清理伪造身份并建立关联号，JWT由后续安全链验证。 */
@Component
public class GatewayBoundaryFilter implements WebFilter, Ordered {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(GatewayBoundaryFilter.class);
    private static final List<String> UNTRUSTED_IDENTITY_HEADERS = List.of(
            "X-User-Id", "X-User-Roles", "X-User-Role",
            "X-Authenticated-User", "X-Forwarded-User", "X-Internal-Token",
            "X-Service-Token", "X-Caller-Service", "X-Forwarded-For", "Forwarded", "X-Real-IP", "X-Login-Source");

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    /** 安全链前移除外部身份头；X-Merchant-Id仅为选店输入，保留给下游按最新成员职级及资源归属校验，不能据此授予权限。请求号保留、链路号重新生成。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = TraceIds.validOrNew(exchange.getRequest().getHeaders().getFirst(TraceIds.REQUEST_HEADER));
        String traceId = TraceIds.newId();
        var request = exchange.getRequest().mutate().headers(headers -> {
            UNTRUSTED_IDENTITY_HEADERS.forEach(headers::remove);
            headers.set(TraceIds.REQUEST_HEADER, requestId);
            headers.set(TraceIds.TRACE_HEADER, traceId);
        }).build();
        var sanitized = exchange.mutate().request(request).build();
        sanitized.getAttributes().put("requestId", requestId);
        sanitized.getAttributes().put("traceId", traceId);
        sanitized.getResponse().getHeaders().set(TraceIds.REQUEST_HEADER, requestId);
        sanitized.getResponse().getHeaders().set(TraceIds.TRACE_HEADER, traceId);
        long started=System.nanoTime();
        return chain.filter(sanitized).doFinally(signal->{if(request.getPath().value().startsWith("/api/"))LOG.atInfo().addKeyValue("requestId",requestId).addKeyValue("traceId",traceId).addKeyValue("method",request.getMethod().name()).addKeyValue("status",sanitized.getResponse().getStatusCode()==null?200:sanitized.getResponse().getStatusCode().value()).addKeyValue("durationMs",(System.nanoTime()-started)/1000000.0).log("event=GATEWAY_HTTP_COMPLETED");});
    }
}
