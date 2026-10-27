package com.tradeflow.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 商品库存服务启动入口；已接入B1真实持久化用例，不产生模拟业务成功数据。 */
@SpringBootApplication
public class ProductApplication {
    /** 默认使用 dev 开发环境；生产启动须显式指定 prod 并提供配置。 */
    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
