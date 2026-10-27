package com.tradeflow.paymentsimulator;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tradeflow.discovery.ServiceEndpoints;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 只允许专属本机Nacos fixture；使用真实SDK注册及Spring发现/负载均衡，不启动业务库或MQ。 */
@EnabledIfEnvironmentVariable(named = "TRADEFLOW_NACOS_FIXTURE_DIR", matches = ".+")
class NacosDiscoveryIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
        "com.tradeflow.web.ServiceWebAutoConfiguration",
        "com.tradeflow.messaging.MessagingAutoConfiguration",
        "com.tradeflow.observability.ObservabilityAutoConfiguration"
    })
    static class DiscoveryOnlyConfiguration { }

    /** 验证无实例拒绝、实际注册可达、注销/替换后发现新实例及namespace隔离；不重试写业务命令。 */
    @Test void actualNacosRegistrationRemovalAndNamespaceIsolation() throws Exception {
        var directory = Path.of(System.getenv("TRADEFLOW_NACOS_FIXTURE_DIR")).toAbsolutePath();
        var mapper = new ObjectMapper();
        var state = mapper.readTree(Files.readString(directory.resolve("runtime.json")));
        assertThat(state.path("fixtureOnly").asBoolean()).isTrue();
        String endpoint = state.path("endpoint").asText();
        assertThat(endpoint).startsWith("127.0.0.1:");
        String namespace = state.path("namespace").asText();
        assertThat(namespace).startsWith("tradeflow-fixture-");
        var secret = mapper.readTree(Files.readString(directory.resolve("fixture-secret.json")));
        String password = secret.path("password").asText();
        var props = new Properties();
        props.put("serverAddr", endpoint);
        props.put("namespace", namespace);
        props.put("username", "nacos");
        props.put("password", password);
        props.put("namingLoadCacheAtStart", "false");
        NamingService publisher = NacosFactory.createNamingService(props);
        var other = new Properties();
        other.putAll(props);
        other.put("namespace", namespace + "-other");
        NamingService isolated = NacosFactory.createNamingService(other);
        var first = backend("fixture-A");
        var second = backend("fixture-B");
        String service = "tradeflow-order", group = "FIXTURE";
        try (var context = new SpringApplicationBuilder(DiscoveryOnlyConfiguration.class)
                .web(WebApplicationType.NONE).run(
                    "--spring.config.location=optional:classpath:/nacos-fixture-no-application.properties",
                    "--spring.application.name=tradeflow-nacos-fixture-consumer",
                    "--spring.cloud.nacos.server-addr=" + endpoint,
                    "--spring.cloud.nacos.username=nacos", "--spring.cloud.nacos.password=" + password,
                    "--spring.cloud.nacos.discovery.namespace=" + namespace,
                    "--spring.cloud.nacos.discovery.group=" + group,
                    "--spring.cloud.nacos.discovery.register-enabled=false",
                    "--spring.cloud.nacos.config.enabled=false",
                    "--spring.cloud.nacos.config.import-check.enabled=false",
                    "--spring.cloud.loadbalancer.cache.enabled=false",
                    "--logging.level.com.alibaba=ERROR", "--logging.level.root=WARN")) {
            var resolver = context.getBean(ServiceEndpoints.class);
            assertThatThrownBy(() -> resolver.resolve("http://tradeflow-order/probe"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("DISCOVERY_INSTANCE_UNAVAILABLE");
            isolated.registerInstance(service, group, instance(second));
            assertThatThrownBy(() -> resolver.resolve("http://tradeflow-order/probe"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("DISCOVERY_INSTANCE_UNAVAILABLE");
            publisher.registerInstance(service, group, instance(first));
            await(() -> resolvesTo(resolver, first.getAddress().getPort()));
            assertThat(get(resolver.resolve("http://tradeflow-order/probe?value=a%2Fb"))).isEqualTo("fixture-A");
            publisher.deregisterInstance(service, group, instance(first));
            await(() -> unavailable(resolver));
            publisher.registerInstance(service, group, instance(second));
            await(() -> resolvesTo(resolver, second.getAddress().getPort()));
            assertThat(get(resolver.resolve("http://tradeflow-order/probe"))).isEqualTo("fixture-B");
            Files.writeString(directory.resolve("java-result.json"), mapper.writeValueAsString(Map.of(
                    "actualNacos", true, "emptyRejected", true, "registrationReached", true,
                    "removedInstanceRejected", true, "replacementReached", true, "namespaceIsolated", true)));
        } finally {
            publisher.shutDown();
            isolated.shutDown();
            first.stop(0);
            second.stop(0);
        }
    }

    private static HttpServer backend(String value) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            var bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        return server;
    }

    private static Instance instance(HttpServer server) {
        var instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(server.getAddress().getPort());
        instance.setEphemeral(true);
        return instance;
    }

    private static boolean resolvesTo(ServiceEndpoints resolver, int port) {
        try { return resolver.resolve("http://tradeflow-order/probe").getPort() == port; }
        catch (IllegalStateException unavailable) { return false; }
    }

    private static boolean unavailable(ServiceEndpoints resolver) {
        try { resolver.resolve("http://tradeflow-order/probe"); return false; }
        catch (IllegalStateException unavailable) { return true; }
    }

    private static String get(URI uri) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as("真实Nacos发现状态应在30秒内传播").isTrue();
    }
}
