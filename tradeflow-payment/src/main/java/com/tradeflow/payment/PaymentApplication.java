package com.tradeflow.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 支付服务启动入口；B3持久支付事实与关闭屏障。 */
@SpringBootApplication
@EnableScheduling
public class PaymentApplication {
    /** 默认使用 dev 开发环境；生产启动须显式指定 prod 并提供配置。 */
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
