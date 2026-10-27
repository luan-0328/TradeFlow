package com.tradeflow.paymentsimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立支付模拟器入口；持久支付退款事实及回调恢复，通过Nacos发现支付服务。 */
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class PaymentSimulatorApplication {
    /** 默认使用 dev 开发环境；生产启动须显式指定 prod 并提供配置。 */
    public static void main(String[] args) {
        SpringApplication.run(PaymentSimulatorApplication.class, args);
    }
}
