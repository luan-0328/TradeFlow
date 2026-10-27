package com.tradeflow.gateway;

import java.util.List;

/** 当前工程模块目录；只列公开开发信息，不暴露服务凭证或数据源。 */
public final class ServiceCatalog {
    public static final List<ServiceLink> SERVICES = List.of(
            entry("user", "用户服务"), entry("product", "商品库存服务"),
            entry("order", "订单服务"), entry("payment", "支付服务"),
            entry("aftersale", "售后服务"), entry("settlement", "模拟结算服务"), entry("payment-simulator", "独立支付模拟器"));

    private ServiceCatalog() {}

    private static ServiceLink entry(String code, String displayName) {
        return new ServiceLink(code, displayName, "/api/v1/" + code + "/system/info");
    }

    public record ServiceLink(String code, String displayName, String infoPath) {}
}
