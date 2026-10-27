package com.tradeflow.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ServiceBoundaryTest.Fixture.class, properties = {
    "spring.application.name=tradeflow-order", "tradeflow.service.code=order", "tradeflow.service.display-name=订单服务",
    // 此用例只验无基础设施的公共Web默认边界；监控独立安全链应由专门测试验证。
    "tradeflow.observability.enabled=false", "management.endpoint.health.probes.enabled=true"})
@AutoConfigureMockMvc
class ServiceBoundaryTest {
    @Autowired MockMvc mvc;
    @SpringBootConfiguration @EnableAutoConfiguration static class Fixture {}

    @Test void exposesOnlyHonestModuleInfoWithRequestCorrelation() throws Exception {
        mvc.perform(get("/system/info").header("X-Request-Id", "review-123"))
            .andExpect(status().isOk()).andExpect(jsonPath("code").value("OK"))
            .andExpect(jsonPath("data.serviceName").value("tradeflow-order"))
            .andExpect(jsonPath("data.stage").value("SKELETON"))
            .andExpect(jsonPath("requestId").value("review-123"))
            .andExpect(header().string("X-Request-Id", "review-123"));
    }

    @Test void refusesUnimplementedBusinessAndInternalCommands() throws Exception {
        mvc.perform(get("/internal/v1/refunds").header("X-User-Id", "admin"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("ENDPOINT_NOT_ENABLED"));
    }

    @Test void exposesLivenessWithoutInfrastructureOrConfiguration() throws Exception {
        // liveness只判断进程存活；聚合health可能因Rabbit等依赖不可用而正确返回503。
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
            .andExpect(jsonPath("status").value("UP")).andExpect(jsonPath("components").doesNotExist());
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    }
}
