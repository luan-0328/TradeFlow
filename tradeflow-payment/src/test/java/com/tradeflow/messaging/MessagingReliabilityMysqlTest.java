package com.tradeflow.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.tradeflow.contracts.MessagingContracts.Event;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 仅连接 messaging-fixture.py 创建的新数据库和新 broker；不启动业务应用、不读取开发凭据。 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_MESSAGING_FIXTURE_DIR", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessagingReliabilityMysqlTest {
    // 与Spring Boot生产ObjectMapper一致：事件契约要求ISO文本时间，不能用裸Jackson默认数字时间戳。
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private MessageStore store;
    private EventCodec codec;
    private RabbitTemplate rabbit;
    private CachingConnectionFactory connection;
    private final AtomicInteger returnedMessages=new AtomicInteger();
    private final MessagingProperties properties = new MessagingProperties();
    private static final List<String> QUEUES = List.of("q.order.payment", "q.order.refund", "q.aftersale.refund", "q.order.audit");

    @BeforeAll void connectOnlyOwnedFixture() throws Exception {
        Path root = Path.of(System.getenv("TRADEFLOW_MESSAGING_FIXTURE_DIR")).toRealPath();
        JsonNode run = json.readTree(Files.readString(root.resolve("runtime.json")));
        JsonNode secret = json.readTree(Files.readString(root.resolve("fixture-secret.json")));
        assertThat(run.path("fixtureOnly").asBoolean()).isTrue();
        assertThat(run.path("root").asText()).isEqualTo(root.toString());
        String url = run.path("mysqlUrl").asText();
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/trade_payment\\?serverTimezone=UTC");
        source = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(source);
        assertThat(Path.of(jdbc.queryForObject("SELECT @@datadir", String.class)).toRealPath()).isEqualTo(root.resolve("mysql-data").toRealPath());
        assertThat(jdbc.queryForObject("SELECT marker FROM fixture_identity", String.class)).isEqualTo(run.path("marker").asText());
        jdbc.execute("CREATE TABLE IF NOT EXISTS fixture_effect(id BIGINT NOT NULL AUTO_INCREMENT COMMENT '测试副作用主键',event_id VARCHAR(64) NOT NULL COMMENT '消息原编号', PRIMARY KEY(id)) COMMENT='独占消息集成测试副作用，不属于业务数据'");
        store = new MessageStore(jdbc, new DataSourceTransactionManager(source));
        codec = new EventCodec(json);
        connection = new CachingConnectionFactory("127.0.0.1", run.path("amqpPort").asInt());
        connection.setVirtualHost("/tradeflow");
        connection.setUsername(secret.path("user").asText());
        connection.setPassword(secret.path("password").asText());
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        rabbit = new RabbitTemplate(connection);
        rabbit.setMandatory(true);
        rabbit.setUsePublisherConnection(true);
        rabbit.setReturnsCallback(returned -> returnedMessages.incrementAndGet());
    }

    @BeforeEach void resetExclusiveFixture() {
        returnedMessages.set(0);
        for (String table : List.of("message_operation", "message_failure", "deferred_event", "inbox_message", "outbox_event", "fixture_effect")) jdbc.update("DELETE FROM " + table);
        rabbit.execute(channel -> { for (String queue : QUEUES) { channel.queuePurge(queue); channel.queuePurge(queue + ".dlq"); } return null; });
    }
    @AfterAll void closeFixtureConnections() { if (connection != null) connection.destroy(); }

    /** 同一真实事务中的业务副作用和 Outbox 必须共同回滚，不能留下可发布幽灵事实。 */
    @Test void outboxRollsBackWithLocalBusinessTransaction() {
        MessageOutbox outbox = new MessageOutbox(source, jdbc, codec);
        assertThatThrownBy(() -> store.tx(() -> {
            jdbc.update("INSERT INTO fixture_effect(event_id) VALUES('rollback')");
            outbox.enqueue("ORDER", "order_rollback", 1, "OrderPaid", "order_rollback", "cmd_rollback", Map.of("orderNo", "order_rollback", "paymentNo", "payment_rollback"));
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("fixture_effect")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    /** 两个线程争用同一真实 Inbox 唯一键，只允许一份业务效果；不同正文单独隔离且不覆盖原摘要。 */
    @Test void concurrentDuplicateAppliesOnceAndConflictingBodyIsQuarantined() throws Exception {
        byte[] raw = audit("evt_duplicate", "payment_original");
        MessageReceiver receiver = receiver(false, new AtomicBoolean(false));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i=0;i<2;i++) tasks.add(executor.submit(() -> { try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } receiver.receive("order.audit", raw, "order.paid.v1"); }));
            start.countDown();
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        String accepted = jdbc.queryForObject("SELECT payload_hash FROM inbox_message", String.class);
        receiver.receive("order.audit", audit("evt_duplicate", "payment_conflict"), "order.paid.v1");
        assertThat(count("fixture_effect")).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message", String.class)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM inbox_message", String.class)).isEqualTo(accepted);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM message_failure", String.class)).isEqualTo("EVENT_ID_CONTENT_CONFLICT");
    }

    /** 处理器先写后抛错必须先回滚业务；原正文持久延期后入口重复不再执行，专门恢复入口才执行。 */
    @Test void failedHandlerRollsBackThenDeferredResumesOriginalBytes() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        MessageReceiver receiver = receiver(true, fail);
        byte[] raw = audit("evt_deferred", "payment_deferred");
        receiver.receive("order.audit", raw, "order.paid.v1");
        assertThat(count("fixture_effect")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message", String.class)).isEqualTo("DEFERRED");
        assertThat(jdbc.queryForObject("SELECT raw_body FROM deferred_event", byte[].class)).isEqualTo(raw);
        receiver.receive("order.audit", raw, "order.paid.v1");
        assertThat(count("fixture_effect")).isZero();
        fail.set(false);
        jdbc.update("UPDATE deferred_event SET next_retry_at=UTC_TIMESTAMP(3)");
        receiver.resume(store.claim("deferred", List.of("order.audit")));
        assertThat(count("fixture_effect")).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM deferred_event", String.class)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_message", String.class)).isEqualTo("APPLIED");
    }

    /** Broker 对无路由发布也可给 ACK；mandatory return 必须使 Outbox 保持可重试而非 SENT。 */
    @Test void mandatoryReturnDoesNotMarkOutboxSent() {
        bind("q.order.audit", "order.paid.v1", false);
        try {
            String id = enqueueAudit();
            publisher(store).outbox(store.claim("outbox", List.of()));
            assertThat(status(id)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE event_id=?", String.class, id)).isEqualTo("PUBLISH_UNCONFIRMED");
            assertThat(returnedMessages.get()).as("必须实际收到basic.return，不能把确认超时当作无路由证据").isEqualTo(1);
            assertThat(rabbit.receive("q.order.audit", 100)).isNull();
        } finally { bind("q.order.audit", "order.paid.v1", true); }
    }

    /** 真实 Broker 确认后仅让首次 SENT 回写抛数据库异常；下次仍须发送同 ID 和完全相同正文。 */
    @Test void brokerAckThenLocalWriteFailureReplaysSameIdAndBody() {
        AtomicBoolean failSent = new AtomicBoolean(true);
        JdbcTemplate fault = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("UPDATE outbox_event SET status=?") && "SENT".equals(args[0]) && failSent.compareAndSet(true, false))
                    throw new DataAccessResourceFailureException("fixture: broker acknowledged, local completion unavailable");
                return super.update(sql, args);
            }
        };
        MessageStore faultStore = new MessageStore(fault, new DataSourceTransactionManager(source));
        String id = enqueueAudit();
        publisher(faultStore).outbox(faultStore.claim("outbox", List.of()));
        var first = rabbit.receive("q.order.audit", 5000);
        assertThat(first).isNotNull();
        assertThat(failSent.get()).as("必须真正执行确认之后的SENT回写故障注入").isFalse();
        assertThat(status(id)).isEqualTo("READY");
        jdbc.update("UPDATE outbox_event SET next_retry_at=UTC_TIMESTAMP(3) WHERE event_id=?", id);
        publisher(faultStore).outbox(faultStore.claim("outbox", List.of()));
        var second = rabbit.receive("q.order.audit", 5000);
        assertThat(second).isNotNull();
        assertThat(first.getMessageProperties().getMessageId()).isEqualTo(id);
        assertThat(second.getMessageProperties().getMessageId()).isEqualTo(id);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(status(id)).isEqualTo("SENT");
        MessageReceiver receiver = receiver(false, new AtomicBoolean(false));
        receiver.receive("order.audit", first.getBody(), "order.paid.v1");
        receiver.receive("order.audit", second.getBody(), "order.paid.v1");
        assertThat(count("fixture_effect")).isOne();
    }

    /** 隔离落库和 DLQ 到达是两步；DLQ 无绑定不能 SENT，恢复绑定后原始非法正文必须不变。 */
    @Test void isolatedRawMessageRetriesIndependentDlqDelivery() {
        byte[] raw = "{invalid-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        receiver(false, new AtomicBoolean(false)).receive("order.audit", raw, "order.paid.v1");
        assertThat(count("message_failure")).isOne();
        rabbit.execute(ch -> { ch.queueUnbind("q.order.audit.dlq", "tradeflow.dlx", "q.order.audit.dlq"); return null; });
        try {
            publisher(store).failure(store.claim("failure", List.of()));
            assertThat(jdbc.queryForObject("SELECT status FROM message_failure", String.class)).isEqualTo("READY");
        } finally { rabbit.execute(ch -> { ch.queueBind("q.order.audit.dlq", "tradeflow.dlx", "q.order.audit.dlq"); return null; }); }
        jdbc.update("UPDATE message_failure SET next_retry_at=UTC_TIMESTAMP(3)");
        publisher(store).failure(store.claim("failure", List.of()));
        var dead = rabbit.receive("q.order.audit.dlq", 5000);
        assertThat(dead).isNotNull();
        assertThat(dead.getBody()).isEqualTo(raw);
        assertThat(jdbc.queryForObject("SELECT status FROM message_failure", String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT disposition FROM message_failure", String.class)).isEqualTo("QUARANTINED");
    }

    /** 观察扇出拓扑前提：尚有一个目标时 mandatory 不返回，确认不等于所有预期队列都收到。 */
    @Test void partialRefundBindingShowsConfirmedPublishBoundary() {
        bind("q.aftersale.refund", "refund.succeeded.v1", false);
        try {
            String id = store.tx(() -> new MessageOutbox(source, jdbc, codec).enqueue("REFUND", "refund_boundary", 1, "RefundSucceeded", "order_boundary", "cmd_boundary",
                    Map.ofEntries(Map.entry("orderNo", "order_boundary"), Map.entry("paymentNo", "payment_boundary"), Map.entry("refundNo", "refund_boundary"), Map.entry("sourceNo", "source_boundary"), Map.entry("amountCents", 100), Map.entry("currency", "CNY"), Map.entry("status", "SUCCEEDED"), Map.entry("channelVersion", 1), Map.entry("channelRefundNo", "channel_refund_boundary"), Map.entry("updatedAt", "2026-10-25T00:00:00Z"), Map.entry("succeededAt", "2026-10-25T00:00:00Z"))));
            publisher(store).outbox(store.claim("outbox", List.of()));
            assertThat(status(id)).isEqualTo("SENT");
            assertThat(returnedMessages.get()).isZero();
            assertThat(rabbit.receive("q.order.refund", 5000)).isNotNull();
            assertThat(rabbit.receive("q.aftersale.refund", 100)).isNull();
        } finally { bind("q.aftersale.refund", "refund.succeeded.v1", true); }
    }

    private ConfirmedPublisher publisher(MessageStore target) { return new ConfirmedPublisher(target, codec, rabbit, List.of((aggregate, id) -> {}), properties); }
    private String enqueueAudit() { String order="order_"+UUID.randomUUID().toString().replace("-", ""); return store.tx(() -> new MessageOutbox(source,jdbc,codec).enqueue("ORDER",order,1,"OrderPaid",order,"fixture_cmd",Map.of("orderNo",order,"paymentNo","payment_fixture"))); }
    private String status(String id) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?",String.class,id); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private void bind(String queue, String route, boolean enabled) { rabbit.execute(ch -> { if(enabled) ch.queueBind(queue,"tradeflow.events",route); else ch.queueUnbind(queue,"tradeflow.events",route); return null; }); }
    private byte[] audit(String id, String payment) { return codec.encode(new Event(id,"OrderPaid",1,"ORDER","order_fixture",1,Instant.parse("2026-10-25T00:00:00Z"),"order_fixture","cmd_fixture","trace_fixture",Map.of("orderNo","order_fixture","paymentNo",payment,"extension",Map.of("retained",true)))); }
    private MessageReceiver receiver(boolean canFail, AtomicBoolean fail) {
        return new MessageReceiver(store,codec,List.of(new EventHandler() {
            public String consumer(){return "order.audit";}
            public Set<String> eventTypes(){return Set.of("OrderPaid");}
            public void handle(Event event){jdbc.update("INSERT INTO fixture_effect(event_id) VALUES(?)",event.eventId()); if(canFail && fail.get()) throw new DeferredMessageException("FIXTURE_PRECONDITION_MISSING");}
        }));
    }
}
