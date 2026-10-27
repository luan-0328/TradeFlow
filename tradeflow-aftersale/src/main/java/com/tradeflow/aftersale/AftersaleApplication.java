package com.tradeflow.aftersale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 售后服务启动入口；启用持久授权、终止和退款恢复任务。 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients(basePackages = "com.tradeflow.aftersale.infrastructure.client")
public class AftersaleApplication {
    /** 默认使用 dev 开发环境；生产启动须显式指定 prod 并提供配置。 */
    public static void main(String[] args) {
        SpringApplication.run(AftersaleApplication.class, args);
    }
}
