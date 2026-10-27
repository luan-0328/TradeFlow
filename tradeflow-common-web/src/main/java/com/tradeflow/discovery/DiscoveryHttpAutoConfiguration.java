package com.tradeflow.discovery;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;

/** Servlet服务共享发现适配器；实例来自注册中心，客户端原超时、签名和幂等协议保持不变。 */
@AutoConfiguration
public class DiscoveryHttpAutoConfiguration {
    @Bean
    public ServiceEndpoints serviceEndpoints(LoadBalancerClient loadBalancer) { return new ServiceEndpoints(loadBalancer); }
}
