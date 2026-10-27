package com.tradeflow.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.tradeflow.common.ApiResponse;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Sentinel 规则从 Nacos 加载；限流与熔断有不同的客户端错误语义。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cloud.sentinel.enabled", havingValue = "true")
public class GatewaySentinelConfiguration {
    /**
     * 注册统一阻断响应，不把被限流或熔断的请求伪装成业务成功。
     * 无数据库事务；仅影响当前 JVM 回调，业务请求的幂等由各服务负责。
     * 限流返回 429；熔断返回 503，调用方必须查询原业务再决定是否重试写请求。
     */
    @Bean
    public InitializingBean sentinelBlockResponse() {
        return () -> GatewayCallbackManager.setBlockHandler((exchange, failure) -> {
            boolean circuitOpen = isDegraded(failure);
            int status = circuitOpen ? 503 : 429;
            var body = ApiResponse.failure(circuitOpen ? "UPSTREAM_CIRCUIT_OPEN" : "RATE_LIMITED",
                    circuitOpen ? "下游服务暂时不可用，请稍后查询原业务" : "请求过于频繁，请稍后重试",
                    exchange.getAttribute("requestId"), exchange.getAttribute("traceId"));
            return ServerResponse.status(status).header("Retry-After",circuitOpen?"5":"1")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        });
    }

    /** 检查有限层异常链，区分熔断与流控；不将外部输入作为异常类型判断依据。 */
    private boolean isDegraded(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
            if (current instanceof DegradeException) return true;
        }
        return false;
    }
}
