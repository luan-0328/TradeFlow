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
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 仅连接 messaging-recovery.py 创建的新数据库和新 broker；不启动业务应用、不读取开发凭据。 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_MESSAGING_FIXTURE_DIR", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessagingRecoveryMysqlTest {
    // 与Spring Boot生产ObjectMapper一致：事件契约要求ISO文本时间，不能用裸Jackson默认数字时间戳。
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private Path fixtureRoot;
    private JsonNode fixtureRun, fixtureSecret;
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
        fixtureRoot=root; fixtureRun=run; fixtureSecret=secret;
        String url = run.path("mysqlUrl").asText();
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/trade_payment\\?serverTimezone=UTC");
        source = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(source);
        assertThat(Path.of(jdbc.queryForObject("SELECT @@datadir", String.class)).toRealPath()).isEqualTo(root.resolve("mysql-data").toRealPath());
        assertThat(jdbc.queryForObject("SELECT marker FROM fixture_identity", String.class)).isEqualTo(run.path("marker").asText());
        jdbc.execute("CREATE TABLE IF NOT EXISTS fixture_effect(id BIGINT NOT NULL AUTO_INCREMENT COMMENT '测试副作用主键',event_id VARCHAR(64) NOT NULL COMMENT '消息原编号', PRIMARY KEY(id)) COMMENT='独占消息集成测试副作用，不属于业务数据'");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fixture_recovery_effect(consumer_name VARCHAR(64) NOT NULL COMMENT '合成消费者',event_id VARCHAR(64) NOT NULL COMMENT '原消息号',PRIMARY KEY(consumer_name,event_id)) COMMENT='隔离恢复测试效果，不属于业务数据'");
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
        for (String table : List.of("message_operation", "message_failure", "deferred_event", "inbox_message", "outbox_event", "fixture_effect", "fixture_recovery_effect")) jdbc.update("DELETE FROM " + table);
        rabbit.execute(channel -> { for (String queue : QUEUES) { channel.queuePurge(queue); channel.queuePurge(queue + ".dlq"); } return null; });
    }
    @AfterAll void closeFixtureConnections() { if (connection != null) connection.destroy(); }


    /** 生产监听每次delivery三次本库失败后NACK requeue；真实Broker再次交付才成功，ACK后队列清空。 */
    @Test void listenerNacksUnavailableInboxThenRedeliveryCommitsAndAcks() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        JdbcTemplate unavailable = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("INSERT INTO inbox_message") && attempts.incrementAndGet() <= 3)
                    throw new DataAccessResourceFailureException("fixture inbox unavailable");
                return super.update(sql, args);
            }
        };
        MessageStore flakyStore = new MessageStore(unavailable, new DataSourceTransactionManager(source));
        CachingConnectionFactory listeners = listenerConnection();
        MessagingRuntime runtime = runtime(flakyStore, listeners, List.of(handler("order.audit", Set.of("OrderPaid"), event -> effect("order.audit", event))), List.of("order.audit"));
        long redeliverBefore = queueRedeliveries("q.order.audit");
        try {
            runtime.start();
            String id = enqueueAudit(); publisher(store, new AtomicInteger()).outbox(store.claim("outbox", List.of()));
            await(() -> effects("order.audit", id) == 1, 25);
            await(() -> queueRedeliveries("q.order.audit") > redeliverBefore, 20);
            assertThat(attempts.get()).isGreaterThanOrEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE event_id=?", String.class, id)).isEqualTo("APPLIED");
            await(() -> queueDrained("q.order.audit"), 15);
            evidence("listener-nack", Map.of("attempts", attempts.get(), "brokerRedeliveries", queueRedeliveries("q.order.audit"), "effects", effects("order.audit", id)));
        } finally { runtime.stop(); listeners.destroy(); }
    }

    /** 业务事务已提交后关闭真实消费连接使ACK丢失；Broker重投由Inbox去重，不能重复业务效果。 */
    @Test void connectionLostAfterCommitBeforeAckRedeliversWithoutDuplicateEffect() throws Exception {
        AtomicBoolean disconnect = new AtomicBoolean(true);
        AtomicInteger inboxAttempts = new AtomicInteger();
        JdbcTemplate observed = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("INSERT INTO inbox_message")) inboxAttempts.incrementAndGet();
                return super.update(sql, args);
            }
        };
        MessageStore target = new MessageStore(observed, new DataSourceTransactionManager(source));
        CachingConnectionFactory listeners = listenerConnection();
        EventHandler handler = handler("order.audit", Set.of("OrderPaid"), event -> {
            effect("order.audit", event);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        if (disconnect.compareAndSet(true, false)) listeners.resetConnection();
                    }
                });
        });
        MessagingRuntime runtime = runtime(target, listeners, List.of(handler), List.of("order.audit"));
        long redeliverBefore = queueRedeliveries("q.order.audit");
        try {
            runtime.start();
            String id=enqueueAudit(); publisher(store,new AtomicInteger()).outbox(store.claim("outbox",List.of()));
            await(() -> inboxAttempts.get() >= 2 && effects("order.audit",id)==1, 30);
            await(() -> queueRedeliveries("q.order.audit") > redeliverBefore, 20);
            assertThat(disconnect.get()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE event_id=? AND status='APPLIED'", Long.class, id)).isOne();
            await(() -> queueDrained("q.order.audit"), 15);
            evidence("listener-lost-ack",Map.of("inboxAttempts",inboxAttempts.get(),"brokerRedeliveries",queueRedeliveries("q.order.audit"),"effects",effects("order.audit",id)));
        } finally { runtime.stop(); listeners.destroy(); }
    }

    /** 缺一退款绑定仍能SENT；恢复绑定后平台审计补投原ID原wire，两真实监听仅为缺失方增加一次效果。 */
    @Test void restoredBindingRedeliversOriginalSentEventThroughGuardsAuditAndInbox() throws Exception {
        bind("q.aftersale.refund","refund.succeeded.v1",false);
        AtomicInteger guardCalls=new AtomicInteger();
        CachingConnectionFactory listeners=listenerConnection();
        MessagingRuntime runtime=runtime(store,listeners,List.of(handler("order.refund",Set.of("RefundSucceeded"),e->effect("order.refund",e)),handler("aftersale.refund",Set.of("RefundSucceeded"),e->effect("aftersale.refund",e))),List.of("order.refund","aftersale.refund"));
        try {
            runtime.start();
            String id=enqueueRefund();
            MessageStore.Claim originalClaim=store.claim("outbox",List.of());
            publisher(store,guardCalls).outbox(originalClaim);
            String originalWire=jdbc.queryForObject("SELECT wire_payload FROM outbox_event WHERE event_id=?",String.class,id);
            assertThat(status(id)).isEqualTo("SENT");
            assertThat(returnedMessages.get()).isZero();
            await(()->effects("order.refund",id)==1,15);
            assertThat(effects("aftersale.refund",id)).isZero();
            bind("q.aftersale.refund","refund.succeeded.v1",true);
            Thread.sleep(200);
            assertThat(effects("aftersale.refund",id)).as("恢复绑定本身不会重投历史SENT").isZero();
            var operation=new MessageOperations(store,codec).retry("outbox",id,"platform_fixture","拓扑恢复后补投缺失目标，原ID原正文");
            assertThat(operation.get("operationId")).startsWith("mop_");
            assertThat(jdbc.queryForObject("SELECT action FROM message_operation WHERE target_id=?",String.class,id)).isEqualTo("REDELIVER_SENT_ORIGINAL");
            assertThat(store.finish(originalClaim,"MANUAL_REVIEW","STALE_WORKER")).as("旧租约不能覆盖补投请求").isFalse();
            MessageStore.Claim replayClaim=store.claim("outbox",List.of());
            assertThat(replayClaim.version()).isGreaterThan(originalClaim.version());
            publisher(store,guardCalls).outbox(replayClaim);
            await(()->effects("aftersale.refund",id)==1,15);
            await(()->queueDrained("q.order.refund")&&queueDrained("q.aftersale.refund"),15);
            assertThat(effects("order.refund",id)).isOne();
            assertThat(jdbc.queryForObject("SELECT wire_payload FROM outbox_event WHERE event_id=?",String.class,id)).isEqualTo(originalWire);
            assertThat(guardCalls.get()).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT DISTINCT payload_hash FROM inbox_message WHERE event_id=?",String.class,id)).hasSize(1);
            assertThat(status(id)).isEqualTo("SENT");
            evidence("partial-binding-recovery",Map.of("eventId",id,"wireSha256",codec.hashOrRaw(originalWire.getBytes(StandardCharsets.UTF_8)),"guardCalls",guardCalls.get(),"orderEffects",effects("order.refund",id),"aftersaleEffects",effects("aftersale.refund",id),"operationId",operation.get("operationId"),"oldClaimFenced",true));
        } finally { bind("q.aftersale.refund","refund.succeeded.v1",true); runtime.stop(); listeners.destroy(); }
    }

    /** 已发送原文缺失时不能重建；审计失败必须回滚READY；人工入口不能夺取运行中租约。 */
    @Test void controlledReplayRejectsMissingWireAuditFailureAndRunningLease() {
        String id=enqueueAudit();
        MessageStore.Claim claim=store.claim("outbox",List.of());
        MessageOperations operations=new MessageOperations(store,codec);
        assertThatThrownBy(()->operations.retry("outbox",id,"platform_fixture","不能夺租约")).hasMessageContaining("正在执行");
        publisher(store,new AtomicInteger()).outbox(claim);
        String wire=jdbc.queryForObject("SELECT wire_payload FROM outbox_event WHERE event_id=?",String.class,id);
        jdbc.update("UPDATE outbox_event SET wire_payload=NULL WHERE event_id=?",id);
        assertThatThrownBy(()->operations.retry("outbox",id,"platform_fixture","不能重建已发送正文")).hasMessageContaining("缺少冻结原文");
        assertThat(status(id)).isEqualTo("SENT");
        jdbc.update("UPDATE outbox_event SET wire_payload=? WHERE event_id=?",wire,id);
        JdbcTemplate failAudit=new JdbcTemplate(source) {
            @Override public int update(String sql,Object... args) {
                if(sql.startsWith("INSERT INTO message_operation")) throw new DataAccessResourceFailureException("fixture audit unavailable");
                return super.update(sql,args);
            }
        };
        assertThatThrownBy(()->new MessageOperations(new MessageStore(failAudit,new DataSourceTransactionManager(source)),codec).retry("outbox",id,"platform_fixture","审计必须共同提交")).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(status(id)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_operation",Long.class)).isZero();
        assertThat(rabbit.receive("q.order.audit",5000)).isNotNull();
        operations.retry("outbox",id,"platform_fixture","补投也不能绕过发送guard");
        AtomicBoolean guardRejected=new AtomicBoolean();
        new ConfirmedPublisher(store,codec,rabbit,List.of((aggregate,key)->{
            guardRejected.set(true); throw EventCodec.invalid("FIXTURE_GUARD_REJECTED");
        }),properties).outbox(store.claim("outbox",List.of()));
        assertThat(guardRejected.get()).isTrue();
        assertThat(status(id)).isEqualTo("MANUAL_REVIEW");
        assertThat(rabbit.receive("q.order.audit",200)).isNull();
        assertThat(jdbc.queryForObject("SELECT wire_payload FROM outbox_event WHERE event_id=?",String.class,id)).isEqualTo(wire);
        evidence("replay-rejections",Map.of("runningRejected",true,"missingWireRejected",true,"auditFailureRolledBack",true,"replayGuardRejectedWithoutPublishing",true));
    }

    /** 真正关闭并重启本次Broker进程，禁用definitions重导入；持久队列/绑定/原消息从同盘恢复后由生产监听ACK。 */
    @Test void persistentOriginalSurvivesFullBrokerProcessRestart() throws Exception {
        String id=enqueueAudit(); publisher(store,new AtomicInteger()).outbox(store.claim("outbox",List.of()));
        String wire=jdbc.queryForObject("SELECT wire_payload FROM outbox_event WHERE event_id=?",String.class,id);
        assertThat(status(id)).isEqualTo("SENT");
        Files.writeString(fixtureRoot.resolve("restart.request"),id);
        await(()->Files.exists(fixtureRoot.resolve("restart.complete")),120);
        connection.resetConnection();
        org.springframework.amqp.core.Message recovered=null;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(recovered==null&&System.nanoTime()<deadline) {
            try { recovered=rabbit.receive("q.order.audit",1000); } catch(RuntimeException ex) { Thread.sleep(200); }
        }
        assertThat(recovered).isNotNull();
        assertThat(recovered.getBody()).isEqualTo(wire.getBytes(StandardCharsets.UTF_8));
        assertThat(recovered.getMessageProperties().getMessageId()).isEqualTo(id);
        assertThat(recovered.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        // 收到后再以原消息重新入队，由生产监听实际完成本库持久接收与ACK，避免把basicGet当监听验收。
        rabbit.send("tradeflow.events","order.paid.v1",recovered);
        CachingConnectionFactory listeners=listenerConnection();
        MessagingRuntime runtime=runtime(store,listeners,List.of(handler("order.audit",Set.of("OrderPaid"),e->effect("order.audit",e))),List.of("order.audit"));
        try {
            runtime.start(); await(()->effects("order.audit",id)==1,20); await(()->queueDrained("q.order.audit"),15);
            evidence("broker-restart",Map.of("eventId",id,"wireUnchanged",true,"sameDisk",true,"definitionsReimported",false,"listenerEffects",effects("order.audit",id)));
        } finally { runtime.stop(); listeners.destroy(); }
    }

    private CachingConnectionFactory listenerConnection() {
        CachingConnectionFactory result=new CachingConnectionFactory("127.0.0.1",fixtureRun.path("amqpPort").asInt());
        result.setVirtualHost("/tradeflow");result.setUsername(fixtureSecret.path("user").asText());result.setPassword(fixtureSecret.path("password").asText());return result;
    }
    private MessagingRuntime runtime(MessageStore target,CachingConnectionFactory listeners,List<EventHandler> handlers,List<String> consumers) {
        MessagingProperties options=new MessagingProperties();options.setConsumers(consumers);options.setPollIntervalMs(60000);
        return new MessagingRuntime(target,new MessageReceiver(target,codec,handlers),publisher(target,new AtomicInteger()),listeners,options);
    }
    private EventHandler handler(String consumer,Set<String> types,java.util.function.Consumer<Event> body) {
        return new EventHandler(){public String consumer(){return consumer;} public Set<String> eventTypes(){return types;} public void handle(Event e){body.accept(e);}};
    }
    private void effect(String consumer,Event event){jdbc.update("INSERT INTO fixture_recovery_effect(consumer_name,event_id) VALUES(?,?)",consumer,event.eventId());}
    private long effects(String consumer,String id){return jdbc.queryForObject("SELECT COUNT(*) FROM fixture_recovery_effect WHERE consumer_name=? AND event_id=?",Long.class,consumer,id);}
    private ConfirmedPublisher publisher(MessageStore target,AtomicInteger guardCalls){return new ConfirmedPublisher(target,codec,rabbit,List.of((aggregate,id)->guardCalls.incrementAndGet()),properties);}
    private String enqueueAudit(){String order="order_"+UUID.randomUUID().toString().replace("-","");return store.tx(()->new MessageOutbox(source,jdbc,codec).enqueue("ORDER",order,1,"OrderPaid",order,"fixture_cmd",Map.of("orderNo",order,"paymentNo","payment_fixture")));}
    private String enqueueRefund(){return store.tx(()->new MessageOutbox(source,jdbc,codec).enqueue("REFUND","refund_recovery",1,"RefundSucceeded","order_recovery","cmd_recovery",Map.ofEntries(Map.entry("orderNo","order_recovery"),Map.entry("paymentNo","payment_recovery"),Map.entry("refundNo","refund_recovery"),Map.entry("sourceNo","source_recovery"),Map.entry("amountCents",100),Map.entry("currency","CNY"),Map.entry("status","SUCCEEDED"),Map.entry("channelVersion",1),Map.entry("channelRefundNo","channel_recovery"),Map.entry("updatedAt","2026-10-25T00:00:00Z"),Map.entry("succeededAt","2026-10-25T00:00:00Z"))));}
    private String status(String id){return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?",String.class,id);}
    private void bind(String queue,String route,boolean enabled){rabbit.execute(ch->{if(enabled)ch.queueBind(queue,"tradeflow.events",route);else ch.queueUnbind(queue,"tradeflow.events",route);return null;});}
    private JsonNode queue(String name){
        try {
            String auth=Base64.getEncoder().encodeToString((fixtureSecret.path("user").asText()+":"+fixtureSecret.path("password").asText()).getBytes(StandardCharsets.UTF_8));
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+fixtureRun.path("managementPort").asInt()+"/api/queues/%2Ftradeflow/"+name)).header("Authorization","Basic "+auth).build();
            var response=HttpClient.newBuilder().proxy(new java.net.ProxySelector(){public List<java.net.Proxy> select(URI uri){return List.of(java.net.Proxy.NO_PROXY);}public void connectFailed(URI uri,java.net.SocketAddress address,java.io.IOException error){}}).build().send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("fixture metadata unavailable");
            return json.readTree(response.body());
        } catch(Exception ex){throw new IllegalStateException("fixture metadata unavailable",ex);}
    }
    private long queueRedeliveries(String name){return queue(name).path("message_stats").path("redeliver").asLong();}
    private boolean queueDrained(String name){JsonNode value=queue(name);return value.has("messages")&&value.path("messages").asLong()==0&&value.path("messages_unacknowledged").asLong()==0;}
    private void await(BooleanSupplier condition,int seconds) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(100);}
        assertThat(condition.getAsBoolean()).as("隔离异步条件在限定时间内成立").isTrue();
    }
    private void evidence(String name,Map<String,Object> data){try{Files.writeString(fixtureRoot.resolve(name+".json"),json.writeValueAsString(data));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
