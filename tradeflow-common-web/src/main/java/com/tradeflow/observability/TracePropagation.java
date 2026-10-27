package com.tradeflow.observability;

import com.tradeflow.common.TraceIds;
import java.net.http.HttpRequest;
import java.util.Map;
import org.slf4j.MDC;

/** 仅传播日志关联标识，不传播用户角色、凭据或请求正文，不把关联号作为授权依据。 */
public final class TracePropagation {
 private TracePropagation(){}
 /** HTTP入口继承已有安全MDC；恢复线程没有上下文时生成新的安全关联号，业务号仍用于独立审计。 */
 public static Map<String,String> headers(){return Map.of(TraceIds.REQUEST_HEADER,TraceIds.validOrNew(MDC.get("requestId")),TraceIds.TRACE_HEADER,TraceIds.validOrNew(MDC.get("traceId")));}
 /** JDK出站调用只添加关联头，保留原HMAC/服务身份和固定目标，不自动重试。 */
 public static HttpRequest.Builder apply(HttpRequest.Builder request){headers().forEach(request::setHeader);return request;}
}
