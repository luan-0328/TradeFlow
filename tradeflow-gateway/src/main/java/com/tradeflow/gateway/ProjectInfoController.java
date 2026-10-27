package com.tradeflow.gateway;

import com.tradeflow.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/** 本地查看入口，公开已实现能力与验证边界，不暴露内部配置。 */
@RestController
public class ProjectInfoController {
    /** 返回工程模块及可访问路径，无数据库事务和虚构业务数据。 */
    @GetMapping({"/", "/api/v1/system/services"})
    public ApiResponse<ProjectOverview> overview(ServerWebExchange exchange) {
        return ApiResponse.success(new ProjectOverview("TradeFlow", "B8_OBSERVABILITY_UNTESTED",
                "已实现多商家交易履约、售后、可靠消息、对账和模拟结算；已完成本地八服务及浏览器业务验收。支付与出款使用模拟通道，生产部署、容量、高可用和完整灾备另行验收。",
                ServiceCatalog.SERVICES), exchange.getAttribute("requestId"), exchange.getAttribute("traceId"));
    }

    public record ProjectOverview(String project, String stage, String description,
            List<ServiceCatalog.ServiceLink> services) {}
}
