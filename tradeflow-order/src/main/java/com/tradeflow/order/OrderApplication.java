package com.tradeflow.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/** 订单服务启动入口；已接入B2持久下单与库存恢复，不伪造支付成功。 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.tradeflow.order.infrastructure.client")
public class OrderApplication {
    /** 默认使用 dev 开发环境；生产启动须显式指定 prod 并提供配置。 */
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
