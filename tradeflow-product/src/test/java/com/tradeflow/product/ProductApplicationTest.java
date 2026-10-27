package com.tradeflow.product;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 完整应用和真实所属MySQL库的启动/HTTP边界；不证明Nacos、MQ或七服务端到端。
 * 仅隔离fixture显式启用，普通单元测试阶段明确跳过；不模拟业务Bean或放宽生产安全。
 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_FULL_APPLICATION_FIXTURE",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ProductApplicationTest {
    @Autowired TestRestTemplate http;
    @LocalServerPort int businessPort;
    @LocalManagementPort int managementPort;

    /** 端口、密钥和凭据均由独占fixture进程提供，禁止回退到开发或远程数据库。 */
    @DynamicPropertySource
    static void fixture(DynamicPropertyRegistry properties) {
        String port=required("TRADEFLOW_UPGRADE_MYSQL_PORT");
        int number=Integer.parseInt(port);
        if(number<1024||number>65535)throw new IllegalArgumentException("隔离MySQL端口必须为1024至65535");
        properties.add("spring.autoconfigure.exclude",()->"org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration");
        properties.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+port+"/trade_product?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->"");
        properties.add("spring.datasource.hikari.maximum-pool-size",()->3);
        properties.add("spring.datasource.hikari.minimum-idle",()->1);
        properties.add("spring.datasource.hikari.transaction-isolation",()->"TRANSACTION_READ_COMMITTED");
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");
        // fixture已应用原始Flyway脚本；应用启动不使用迁移账号、不重复迁移。
        properties.add("spring.flyway.enabled",()->false);
        properties.add("spring.cloud.nacos.discovery.enabled",()->false);
        properties.add("spring.cloud.nacos.discovery.register-enabled",()->false);
        properties.add("spring.cloud.nacos.config.enabled",()->false);
        properties.add("spring.cloud.sentinel.enabled",()->false);
        properties.add("spring.cloud.service-registry.auto-registration.enabled",()->false);
        properties.add("spring.rabbitmq.dynamic",()->false);
        properties.add("spring.rabbitmq.username",()->"guest");
        properties.add("spring.rabbitmq.password",()->"guest");
        properties.add("spring.rabbitmq.listener.simple.auto-startup",()->false);
        properties.add("spring.rabbitmq.listener.direct.auto-startup",()->false);
        properties.add("tradeflow.messaging.enabled",()->false);
        properties.add("tradeflow.observability.enabled",()->false);
        properties.add("tradeflow.notifications.enabled",()->false);
        properties.add("tradeflow.bootstrap.enabled",()->false);
        properties.add("management.server.address",()->"127.0.0.1");
        properties.add("management.server.port",()->0);
        properties.add("management.health.rabbit.enabled",()->false);
        properties.add("management.health.redis.enabled",()->false);
        properties.add("management.endpoint.health.group.readiness.include",()->"readinessState,db");
        properties.add("tradeflow.security.issuer",()->"tradeflow");
        Map.ofEntries(
            Map.entry("tradeflow.security.public-key","TRADEFLOW_JWT_PUBLIC_KEY"),
            Map.entry("tradeflow.security.private-key","TRADEFLOW_JWT_PRIVATE_KEY"),
            Map.entry("tradeflow.monitoring-token","TRADEFLOW_MONITORING_TOKEN"),
            Map.entry("tradeflow.internal.product-token","TRADEFLOW_PRODUCT_SERVICE_TOKEN"),
            Map.entry("tradeflow.internal.order-token","TRADEFLOW_ORDER_SERVICE_TOKEN"),
            Map.entry("tradeflow.internal.payment-token","TRADEFLOW_PAYMENT_SERVICE_TOKEN"),
            Map.entry("tradeflow.internal.aftersale-token","TRADEFLOW_AFTERSALE_SERVICE_TOKEN"),
            Map.entry("tradeflow.internal.channel-token","TRADEFLOW_CHANNEL_SERVICE_TOKEN"),
            Map.entry("tradeflow.internal.settlement-token","TRADEFLOW_INTERNAL_SETTLEMENT_TOKEN"),
            Map.entry("tradeflow.callback-secret","TRADEFLOW_CALLBACK_SECRET"),
            Map.entry("tradeflow.simulator-admin-token","TRADEFLOW_SIMULATOR_ADMIN_TOKEN")
        ).forEach((property,environment)->properties.add(property,()->required(environment)));
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalStateException("完整应用fixture缺少配置："+name);
        return value;
    }

    /** 实际Spring应用、真实Mapper和本库连接均启动，公开身份必须来自当前服务配置。 */
    @Test void startsCompleteApplicationWithRealOwnDatabaseAndCurrentIdentity() {
        var response=http.getForEntity("/system/info",JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().at("/data/serviceName").asText()).isEqualTo("tradeflow-product");
        assertThat(response.getBody().at("/data/stage").asText()).isEqualTo("B8_OBSERVABILITY_UNTESTED");
    }
    /** 管理端口与业务端口分离；readiness真实检查所属数据库，不拿应用启动替代业务端到端验收。 */
    @Test void managementReadinessUsesIndependentPortAndBusinessPortDoesNotExposeActuator() {
        assertThat(managementPort).isPositive().isNotEqualTo(businessPort);
        assertThat(http.getForEntity("/actuator/health",String.class).getStatusCode().value()).isEqualTo(403);
        var response=new TestRestTemplate().getForEntity("http://127.0.0.1:"+managementPort+"/actuator/health/readiness",JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
    }
}
