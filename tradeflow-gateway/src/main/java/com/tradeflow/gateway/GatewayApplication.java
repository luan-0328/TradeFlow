package com.tradeflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** WebFlux 网关入口；不得引入 tradeflow-common-web 或 Servlet 运行时。 */
@SpringBootApplication
public class GatewayApplication {
    /** 启动公开的骨架信息和受限服务路由，交易入口在后续鉴权批次开放。 */
    public static void main(String[] args) {
        // 在 Sentinel 初始化前声明网关类型，使控制台展示网关流控菜单；不参与业务事务。
        System.setProperty("csp.sentinel.app.type", "1");
        SpringApplication.run(GatewayApplication.class, args);
    }
}
