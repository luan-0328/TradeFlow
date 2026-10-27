package com.tradeflow.messaging;

import com.tradeflow.contracts.MessagingContracts.Event;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 短事务冻结正文，事务外 mandatory+correlated confirm，围栏回写；未知结果仍可原 ID 重发。 */
final class ConfirmedPublisher {
    private final MessageStore store;
    private final EventCodec codec;
    private final RabbitTemplate rabbit;
    private final List<OutboxGuard> guards;
    private final MessagingProperties properties;
    ConfirmedPublisher(MessageStore store, EventCodec codec, RabbitTemplate rabbit, List<OutboxGuard> guards, MessagingProperties properties) {
        this.store = store; this.codec = codec; this.rabbit = rabbit; this.guards = guards; this.properties = properties;
    }
    void outbox(MessageStore.Claim claim) {
        try {
            if (store.expiredBeforeAttempt(claim)) throw EventCodec.invalid("OUTBOX_RETRY_EXHAUSTED");
            byte[] body = store.tx(() -> {
                Map<String, Object> row = store.fence(claim);
                if (row == null) return null;
                String wire = (String)row.get("wire_payload");
                if (wire == null) {
                    Event event = new Event(MessageStore.str(row, "event_id"), MessageStore.str(row, "event_type"),
                            Math.toIntExact(MessageStore.number(row, "event_version")), MessageStore.str(row, "aggregate_type"),
                            MessageStore.str(row, "aggregate_id"), MessageStore.number(row, "aggregate_version"),
                            MessageStore.instant(row.get("created_at")), MessageStore.str(row, "correlation_id"),
                            MessageStore.str(row, "causation_id"), MessageStore.str(row, "trace_id"), codec.payload(MessageStore.str(row, "payload"), claim.id()));
                    wire = new String(codec.encode(event), StandardCharsets.UTF_8);
                    if (store.db.update("UPDATE outbox_event SET wire_payload=?,version=version+1 WHERE event_id=? AND status='RUNNING' AND lease_owner=? AND claim_version=? AND lease_until>UTC_TIMESTAMP(3)",
                            wire, claim.id(), claim.owner(), claim.version()) != 1) throw new IllegalStateException("冻结正文围栏已失效");
                }
                Event event = codec.decode(wire.getBytes(StandardCharsets.UTF_8)).event();
                if (!event.eventId().equals(claim.id()) || !event.eventType().equals(row.get("event_type"))
                        || !event.aggregateType().equals(row.get("aggregate_type")) || !event.aggregateId().equals(row.get("aggregate_id"))
                        || event.aggregateVersion() != MessageStore.number(row, "aggregate_version")
                        || event.eventVersion() != MessageStore.number(row, "event_version")
                        || !event.correlationId().equals(row.get("correlation_id")) || !event.causationId().equals(row.get("causation_id"))
                        || !event.traceId().equals(row.get("trace_id"))) throw EventCodec.invalid("OUTBOX_ENVELOPE_CONFLICT");
                return wire.getBytes(StandardCharsets.UTF_8);
            });
            if (body == null) return;
            if (guards.isEmpty() && List.of("PAYMENT", "REFUND").contains(MessageStore.str(claim.row(), "aggregate_type")))
                throw EventCodec.invalid("OUTBOX_GUARD_MISSING");
            for (OutboxGuard guard : guards) guard.check(MessageStore.str(claim.row(), "aggregate_type"), MessageStore.str(claim.row(), "aggregate_id"));
            if (!store.owns(claim)) return;
            publish(EventCodec.EXCHANGE, EventCodec.ROUTES.get(MessageStore.str(claim.row(), "event_type")), claim.id(), body, Map.of());
            store.tx(() -> { store.finish(claim, "SENT", ""); return null; });
        } catch (Exception ex) { failed(claim, ex); }
    }
    /** 隔离已落库才 ACK；此发送器独立确认 broker DLQ 到达，原始 bytes 从不变更。 */
    void failure(MessageStore.Claim claim) {
        try {
            if (store.expiredBeforeAttempt(claim)) throw EventCodec.invalid("DLQ_RETRY_EXHAUSTED");
            if (!store.owns(claim)) return;
            String consumer = MessageStore.str(claim.row(), "consumer_name");
            if (!EventCodec.CONSUMERS.contains(consumer)) throw EventCodec.invalid("INVALID_FAILURE_CONSUMER");
            publish(EventCodec.DLX, "q." + consumer + ".dlq", MessageStore.str(claim.row(), "event_id"), MessageStore.bytes(claim.row()),
                    Map.of("x-failure-id", claim.id(), "x-failure-code", MessageStore.str(claim.row(), "reason_code"),
                            "x-original-event-id", MessageStore.str(claim.row(), "event_id"), "x-consumer", consumer));
            store.tx(() -> { store.finish(claim, "SENT", ""); return null; });
        } catch (Exception ex) { failed(claim, ex); }
    }
    private void publish(String exchange, String route, String eventId, byte[] body, Map<String, Object> headers) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("消息网络发布不能处于数据库事务中");
        MessageProperties props = new MessageProperties();
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT); props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding("UTF-8"); props.setMessageId(eventId); headers.forEach(props::setHeader);
        CorrelationData correlation = new CorrelationData(MessageStore.newId("pub_"));
        rabbit.send(exchange, route, new Message(body, props), correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(properties.getConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
        // Spring AMQP 保证 returned message 在 future 完成之前写入 CorrelationData。
        if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("BROKER_NOT_CONFIRMED_OR_RETURNED");
    }
    private void failed(MessageStore.Claim claim, Exception error) {
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        boolean permanent = error instanceof QuarantinedMessageException || store.exhausted(claim);
        String code = error instanceof QuarantinedMessageException q ? q.code() : permanent ? "PUBLISH_RETRY_EXHAUSTED" : "PUBLISH_UNCONFIRMED";
        store.tx(() -> { store.finish(claim, permanent ? "MANUAL_REVIEW" : "READY", code); return null; });
    }
}
