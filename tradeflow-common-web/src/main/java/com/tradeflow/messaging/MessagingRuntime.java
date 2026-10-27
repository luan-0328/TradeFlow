package com.tradeflow.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.context.SmartLifecycle;

/** 不声明任何 broker 资源；仅订阅显式配置且确实注册 handler 的本服务队列。 */
final class MessagingRuntime implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(MessagingRuntime.class);
    private final MessageStore store;
    private final MessageReceiver receiver;
    private final ConfirmedPublisher publisher;
    private final ConnectionFactory connections;
    private final MessagingProperties properties;
    private final List<SimpleMessageListenerContainer> listeners = new ArrayList<>();
    private final List<String> consumers;
    private ScheduledExecutorService executor;
    private volatile boolean running;
    MessagingRuntime(MessageStore store, MessageReceiver receiver, ConfirmedPublisher publisher,
            ConnectionFactory connections, MessagingProperties properties) {
        this.store = store; this.receiver = receiver; this.publisher = publisher;
        this.connections = connections; this.properties = properties;
        consumers = receiver.consumers(properties.getConsumers());
    }
    @Override public synchronized void start() {
        if (running) return;
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tradeflow-message-recovery"); thread.setDaemon(true); return thread;
        });
        try {
            for (String consumer : consumers) {
                SimpleMessageListenerContainer listener = new SimpleMessageListenerContainer(connections);
                listener.setQueueNames("q." + consumer);
                listener.setAutoDeclare(false);
                listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
                listener.setPrefetchCount(1);
                listener.setConcurrentConsumers(1);
                listener.setMaxConcurrentConsumers(1);
                listener.setRecoveryInterval(5000);
                listener.setMessageListener((ChannelAwareMessageListener)(message, channel) -> {
                    // 本库不可用不 ACK；每次 delivery 至多三次、逐次退避，再 NACK requeue，避免热循环。
                    long[] delays = {500, 1500, 3000};
                    boolean accepted = false;
                    for (long delay : delays) {
                        try {
                            receiver.receive(consumer, message.getBody(), message.getMessageProperties().getReceivedRoutingKey());
                            accepted = true; break;
                        } catch (RuntimeException ex) {
                            LOG.warn("消息本地可靠接收失败 consumer={} errorType={}", consumer, ex.getClass().getSimpleName());
                            try { Thread.sleep(delay); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                        }
                    }
                    if (channel == null) throw new IllegalStateException("手工ACK缺少Rabbit channel");
                    if (accepted) channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                    else channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
                });
                listeners.add(listener); listener.start();
            }
            executor.scheduleWithFixedDelay(this::tick, properties.getPollIntervalMs(), properties.getPollIntervalMs(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) { stop(); throw ex; }
    }
    /** 每类任务独立捕获故障，数据库恢复后继续领取；日志只包含类型，不输出载荷/连接秘密。 */
    private void tick() {
        for (int i = 0; running && i < properties.getBatchSize(); i++) {
            boolean found = false;
            for (String kind : List.of("outbox", "deferred", "failure")) {
                try {
                    MessageStore.Claim claim = store.claim(kind, consumers);
                    if (claim == null) continue;
                    found = true;
                    switch (kind) {
                        case "outbox" -> publisher.outbox(claim);
                        case "deferred" -> receiver.resume(claim);
                        case "failure" -> publisher.failure(claim);
                        default -> throw new IllegalStateException("未知任务类型");
                    }
                } catch (RuntimeException ex) {
                    LOG.warn("消息恢复暂未完成 kind={} errorType={}", kind, ex.getClass().getSimpleName());
                }
            }
            if (!found) break;
        }
    }
    @Override public synchronized void stop() {
        running = false;
        listeners.forEach(SimpleMessageListenerContainer::stop); listeners.clear();
        if (executor != null) executor.shutdownNow();
    }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
