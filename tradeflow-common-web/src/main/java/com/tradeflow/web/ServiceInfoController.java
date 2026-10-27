package com.tradeflow.web;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.ServiceDescriptor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开当前已实现能力，不返回虚构的订单、支付或退款结果。 */
@RestController
public class ServiceInfoController {
    private final Environment environment;
    private final ServiceProperties properties;

    public ServiceInfoController(Environment environment, ServiceProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    /** 查询骨架能力，无事务和外部依赖；可用于确认网关路由及实际启动的服务。 */
    @GetMapping("/system/info")
    public ApiResponse<ServiceDescriptor> info(HttpServletRequest request) {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) active = environment.getDefaultProfiles();
        var descriptor = new ServiceDescriptor(environment.getRequiredProperty("spring.application.name"),
                properties.code(), properties.displayName(), environment.getProperty("info.app.stage", "SKELETON"), Arrays.asList(active),
                List.of(environment.getProperty("tradeflow.service.capabilities", "module-info,health,api-docs").split(",")), "/actuator/health", "/v3/api-docs");
        return ApiResponse.success(descriptor, (String) request.getAttribute("requestId"),
                (String) request.getAttribute("traceId"));
    }
}
