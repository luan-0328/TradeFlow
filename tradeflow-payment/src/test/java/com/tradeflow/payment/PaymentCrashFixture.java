package com.tradeflow.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.contracts.PaymentContracts.*;
import com.tradeflow.payment.application.PaymentService;
import com.tradeflow.payment.infrastructure.PaymentMapper;
import com.tradeflow.payment.infrastructure.PaymentRemote;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 专属子JVM fixture：真实MySQL/Mapper/支付用例，通道明确为fsync文件替身，不加载业务应用。 */
public final class PaymentCrashFixture {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final int HALT_AFTER_CHANNEL_SUCCESS = 73;
    final Path root;
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final PaymentMapper mapper;
    final String no, order;
    final Intent intent;

    PaymentCrashFixture() throws Exception {
        root = Path.of(System.getenv("TRADEFLOW_PAYMENT_CRASH_DIR")).toAbsolutePath().normalize();
        var runtime = JSON.readTree(Files.readString(root.resolve("runtime.json")));
        if (!runtime.path("fixtureOnly").asBoolean() || !root.getFileName().toString().startsWith("payment-crash-"))
            throw new IllegalStateException("拒绝非专属fixture目录");
        String url = System.getenv("TRADEFLOW_PAYMENT_CRASH_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/trade_payment\\?.*"))
            throw new IllegalStateException("只允许显式回环fixture数据库");
        var source = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(source);
        Path actual = Path.of(jdbc.queryForObject("SELECT @@datadir", String.class)).toRealPath();
        if (!actual.equals(root.resolve("mysql/data").toRealPath()))
            throw new IllegalStateException("MySQL数据目录不属于本轮fixture，禁止写入");
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var config = new org.apache.ibatis.session.Configuration(new Environment("crash-fixture",
                new SpringManagedTransactionFactory(), source));
        config.addMapper(PaymentMapper.class);
        // 手工构建MyBatis配置时显式加载XML，保持真实租约与事务SQL进入测试。
        String mapperResource = "mapper/payment/PaymentMapper.xml";
        try (var input = org.apache.ibatis.io.Resources.getResourceAsStream(mapperResource)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, config, mapperResource, config.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(PaymentMapper.class);
        no = runtime.path("paymentNo").asText();
        order = runtime.path("orderNo").asText();
        intent = new Intent(order, no, "1", 12345, "CNY", Instant.now().plusSeconds(600), "PENDING_PAYMENT");
    }

    PaymentService service(boolean crashOnCommand) {
        var env = new MockEnvironment().withProperty("tradeflow.internal.order-token", "fixture-order")
                .withProperty("tradeflow.callback-secret", "fixture-callback")
                .withProperty("tradeflow.internal.payment-token", "fixture-payment")
                .withProperty("tradeflow.internal.channel-token", "fixture-channel");
        return new PaymentService(mapper, new FileChannelRemote(env, crashOnCommand), tx, JSON, env);
    }

    /** 第一个JVM在外部事实落盘后直接halt，跳过catch/finally及Spring关闭钩子。 */
    public static void main(String[] arguments) throws Exception {
        var fixture = new PaymentCrashFixture();
        switch (arguments[0]) {
            case "crash" -> {
                fixture.tx.executeWithoutResult(status -> {
                    fixture.mapper.ensure(fixture.intent);
                    fixture.mapper.ensureTask(fixture.no);
                });
                fixture.service(true).recover();
                throw new AssertionError("应在通道成功后halt，不能正常返回");
            }
            case "recover" -> fixture.restartAndRecover();
            default -> throw new IllegalArgumentException("未知fixture阶段");
        }
    }

    /** 重启后首先证明有效旧租约不可接管，再等真实数据库时钟过期，仅查询原号恢复并重复应用核验。 */
    private void restartAndRecover() throws Exception {
        PaymentService restarted = service(false);
        if (jdbc.queryForObject("SELECT lease_until>UTC_TIMESTAMP(3) FROM process_task WHERE task_no=?", Integer.class, no) != 1)
            throw new AssertionError("重启探测必须发生在原租约有效期内");
        restarted.recover();
        if (Files.exists(root.resolve("queries.log"))) throw new AssertionError("租约未过期不能查询通道");
        if (!"PENDING".equals(mapper.find(no).get("status"))) throw new AssertionError("租约未过期不能应用成功");
        durable(root.resolve("before-expiry.json"), JSON.writeValueAsBytes(Map.of("oldLeaseRespected", true)), false);
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (jdbc.queryForObject("SELECT lease_until<UTC_TIMESTAMP(3) FROM process_task WHERE task_no=?", Integer.class, no) != 1) {
            if (System.nanoTime() > deadline) throw new AssertionError("真实租约未在预算内过期");
            Thread.sleep(100);
        }
        restarted.recover();
        // 重复worker及主动查询只能读取同一成功事实，不能再次生成本地资金效果。
        restarted.recover();
        restarted.queryChannel("order", "fixture-order", no);
        restarted.queryChannel("order", "fixture-order", no);
        durable(root.resolve("recovered.json"), JSON.writeValueAsBytes(Map.of("databaseClockLeaseExpired", true,
                "originalPaymentQueried", true, "repeatedFactApplied", true)), false);
    }

    /** 明确文件通道替身：一次command落盘成功，后续只允许query原号；不模拟真实HTTP/Nacos/支付通道。 */
    private final class FileChannelRemote extends PaymentRemote {
        private final boolean crash;
        FileChannelRemote(MockEnvironment environment, boolean crash) {
            super(JSON, environment, null);
            this.crash = crash;
        }
        @Override public Intent intent(String requestedOrder) {
            outsideTransaction();
            if (!order.equals(requestedOrder)) throw new AssertionError("订单号改变");
            return intent;
        }
        @Override public ChannelFact command(ChannelCommand command, boolean close) {
            outsideTransaction();
            if (!crash || close || !no.equals(command.paymentNo()) || !order.equals(command.orderNo())
                    || command.amountCents() != 12345 || !"CNY".equals(command.currency()))
                throw new AssertionError("重启后禁止再次提交或更换原支付意图");
            try {
                durable(root.resolve("commands.log"), (no + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
                Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                var fact = new ChannelFact(no, order, 12345, "CNY", "SUCCEEDED", "channel_crash_fixture", 1, at, at);
                durable(root.resolve("channel-success.json"), JSON.writeValueAsBytes(fact), false);
            } catch (Exception failure) { throw new AssertionError("通道替身未持久化", failure); }
            Runtime.getRuntime().halt(HALT_AFTER_CHANNEL_SUCCESS);
            throw new AssertionError("halt不能返回");
        }
        @Override public ChannelFact query(String requestedPayment) {
            outsideTransaction();
            if (!no.equals(requestedPayment)) throw new AssertionError("只能查询原支付号");
            try {
                durable(root.resolve("queries.log"), (requestedPayment + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
                return JSON.readValue(Files.readAllBytes(root.resolve("channel-success.json")), ChannelFact.class);
            } catch (Exception failure) { throw new AssertionError("通道替身查询失败", failure); }
        }
    }

    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new AssertionError("通道调用不得位于本地事务");
    }

    /** 外部成功证据force后才halt；CREATE_NEW防止重写既有事实，append用于记录真实调用次数。 */
    private static void durable(Path path, byte[] bytes, boolean append) throws Exception {
        var options = append ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};
        try (FileChannel channel = FileChannel.open(path, options)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
