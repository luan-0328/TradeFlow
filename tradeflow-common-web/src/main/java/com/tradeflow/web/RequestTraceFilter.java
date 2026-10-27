package com.tradeflow.web;

import com.tradeflow.common.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在鉴权之前建立请求关联，确保成功和拒绝响应都有可追查编号。 */
public final class RequestTraceFilter extends OncePerRequestFilter implements Ordered {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RequestTraceFilter.class);
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    /** 只复用安全的日志关联头；请求结束清除 MDC，防止线程池串号。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = TraceIds.validOrNew(request.getHeader(TraceIds.REQUEST_HEADER));
        String traceId = TraceIds.validOrNew(request.getHeader(TraceIds.TRACE_HEADER));
        request.setAttribute("requestId", requestId);
        request.setAttribute("traceId", traceId);
        response.setHeader(TraceIds.REQUEST_HEADER, requestId);
        response.setHeader(TraceIds.TRACE_HEADER, traceId);
        try (var ignoredRequest = MDC.putCloseable("requestId", requestId);
             var ignoredTrace = MDC.putCloseable("traceId", traceId)) {
            long started=System.nanoTime();
            try { chain.doFilter(request, response); }
            finally { if(request.getRequestURI().startsWith("/api/")) LOG.atInfo().addKeyValue("method",request.getMethod()).addKeyValue("status",response.getStatus()).addKeyValue("durationMs",(System.nanoTime()-started)/1000000.0).log("event=HTTP_COMPLETED"); }
        }
    }
}
