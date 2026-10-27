package com.tradeflow.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅转发已实现业务和模块信息；内部接口永远不经公开网关路由。 */
@Configuration(proxyBeanMethods = false)
public class GatewayRoutes {
    /** 所有环境按固定服务名发现实例；仅注册公开业务路径，不启用自动发现全量路由。 */
    @Bean
    public RouteLocator moduleInfoRoutes(RouteLocatorBuilder builder) {
        var routes = builder.routes();
        for (var service : ServiceCatalog.SERVICES) {
            routes.route("info-" + service.code(), route -> route.path(service.infoPath())
                    .filters(filters -> filters.stripPrefix(3))
                    .uri("lb://tradeflow-" + service.code()));
        }
        routes.route("user-api",r->r.path("/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/email-code","/api/v1/users/me","/api/v1/users/me/capabilities",
                "/api/v1/admin/users","/api/v1/admin/users/*","/api/v1/admin/users/*/roles","/api/v1/admin/merchant-members")
                .uri("lb://tradeflow-user"));
        routes.route("product-api",r->r.path("/api/v1/products","/api/v1/products/*","/api/v1/merchant/products",
                "/api/v1/merchant/products/*","/api/v1/merchant/skus/*","/api/v1/merchant/inventory/adjustments","/api/v1/merchant/inventory/ledger",
                "/api/v1/merchant/inventory/conditions","/api/v1/merchant/inventory/condition-adjustments","/api/v1/merchant/inventory/condition-adjustments/requests/by-key")
                .uri("lb://tradeflow-product"));
        routes.route("order-api",r->r.path("/api/v1/orders","/api/v1/orders/*","/api/v1/orders/requests/*","/api/v1/orders/*/cancel",
                "/api/v1/admin/return-stock-tasks","/api/v1/admin/return-stock-tasks/*/retry","/api/v1/admin/order-tasks","/api/v1/admin/order-tasks/*/retry","/api/v1/admin/refund-tasks","/api/v1/admin/refund-tasks/*/retry","/api/v1/merchant/orders","/api/v1/merchant/orders/*","/api/v1/merchant/orders/*/ship","/api/v1/merchant/orders/*/shipping-address","/api/v1/orders/*/receive")
                .uri("lb://tradeflow-order"));
        routes.route("payment-api",r->r.path("/api/v1/admin/reconciliation/runs","/api/v1/admin/reconciliation/runs/*","/api/v1/admin/reconciliation/runs/*/retry","/api/v1/admin/reconciliation/runs/*/observations","/api/v1/admin/reconciliation/issues","/api/v1/admin/reconciliation/issues/*","/api/v1/admin/reconciliation/issues/*/repair","/api/v1/admin/reconciliation/issues/*/observations","/api/v1/admin/reconciliation/actions/*","/api/v1/admin/reconciliation/actions/*/retry","/api/v1/admin/reconciliation/checks","/api/v1/payments","/api/v1/payments/*","/api/v1/payments/requests/by-key","/api/v1/admin/payment-ledger")
                .uri("lb://tradeflow-payment"));
        routes.route("aftersale-api",r->r.path("/api/v1/aftersales/preview","/api/v1/aftersales/*/materials","/api/v1/aftersales/*/return-shipment","/api/v1/merchant/aftersales/*/materials","/api/v1/merchant/aftersales/*/return-inspection","/api/v1/evidence-files","/api/v1/evidence-files/*","/api/v1/aftersales","/api/v1/aftersales/*","/api/v1/aftersales/requests/by-key","/api/v1/aftersales/*/cancel","/api/v1/merchant/aftersales","/api/v1/merchant/aftersales/*","/api/v1/merchant/aftersales/*/review","/api/v1/merchant/aftersales/*/retry-refund","/api/v1/admin/aftersale-tasks","/api/v1/admin/aftersale-tasks/*/retry")
                .uri("lb://tradeflow-aftersale"));
        for(String owner:java.util.List.of("order","payment","aftersale")) {
            routes.route(owner+"-message-admin",r->r.path("/api/v1/admin/"+owner+"-messages/*","/api/v1/admin/"+owner+"-messages/*/*/retry")
                    .uri("lb://tradeflow-"+owner));
        }
        routes.route("user-upgrade",r->r.path("/api/v1/users/me/**","/api/v1/merchant-applications","/api/v1/merchants","/api/v1/merchants/**","/api/v1/admin/merchant-applications","/api/v1/admin/merchant-applications/**","/api/v1/admin/merchants","/api/v1/admin/merchants/**","/api/v1/admin/audits").uri("lb://tradeflow-user"));
        routes.route("product-upgrade",r->r.path("/api/v1/products/*/images","/api/v1/product-images/*","/api/v1/merchant/products/*/skus","/api/v1/merchant/products/*/images","/api/v1/merchant/products/*/images/*","/api/v1/merchant/product-images/*").uri("lb://tradeflow-product"));
        routes.route("order-upgrade",r->r.path("/api/v1/trade-groups","/api/v1/trade-groups/**","/api/v1/shipping-quotes","/api/v1/merchant/shipping-rule","/api/v1/orders/*/timeline").uri("lb://tradeflow-order"));
        routes.route("governance-upgrade",r->r.path("/api/v1/disputes","/api/v1/admin/disputes","/api/v1/admin/disputes/*/decision").uri("lb://tradeflow-aftersale"));
        routes.route("settlement-upgrade",r->r.path("/api/v1/merchant/settlement/**","/api/v1/admin/settlement/**").uri("lb://tradeflow-settlement"));
        routes.route("order-notifications",r->r.path("/api/v1/admin/order-notifications","/api/v1/admin/order-notifications/*/retry").uri("lb://tradeflow-order"));
        return routes.build();
    }
}
