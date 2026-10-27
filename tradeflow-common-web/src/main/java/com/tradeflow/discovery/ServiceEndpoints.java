package com.tradeflow.discovery;

import java.net.URI;
import java.util.Set;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 原始HTTP客户端通过Nacos发现实例；不缓存具体地址，不提供直连回退，不自动重试写请求。 */
public final class ServiceEndpoints {
    private static final Set<String> SERVICES = Set.of("tradeflow-user", "tradeflow-product", "tradeflow-order",
            "tradeflow-payment", "tradeflow-aftersale", "tradeflow-payment-simulator","tradeflow-settlement");
    private final LoadBalancerClient loadBalancer;

    public ServiceEndpoints(LoadBalancerClient loadBalancer) { this.loadBalancer = loadBalancer; }

    /** 每次调用重新选择实例，原样保留编码路径和查询参数；无实例作为依赖未知交给原持久任务恢复。 */
    public URI resolve(String logicalAddress) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("禁止在本地事务内选择远程实例或发起RPC");
        URI logical = URI.create(logicalAddress);
        if (!"http".equals(logical.getScheme()) || logical.getHost() == null || !SERVICES.contains(logical.getHost())
                || logical.getPort() != -1 || logical.getUserInfo() != null || logical.getFragment() != null)
            throw new IllegalArgumentException("仅允许固定业务服务名，不接受外部地址或凭据");
        var instance = loadBalancer.choose(logical.getHost());
        if (instance == null) throw new IllegalStateException("DISCOVERY_INSTANCE_UNAVAILABLE");
        return LoadBalancerUriTools.reconstructURI(instance, logical);
    }
}
