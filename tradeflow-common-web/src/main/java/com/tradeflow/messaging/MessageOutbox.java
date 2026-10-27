package com.tradeflow.messaging;

import com.tradeflow.contracts.MessagingContracts.Event;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 追加能力独立于传输开关；业务和事件在当前服务同一个本地事务中提交。 */
public final class MessageOutbox {
    private final DataSource source;
    private final JdbcTemplate db;
    private final EventCodec codec;
    MessageOutbox(DataSource source, JdbcTemplate db, EventCodec codec) { this.source = source; this.db = db; this.codec = codec; }

    /** 只接受已开启且绑定本 DataSource 的本地事务；不隐式开事务、不进行网络发布。 */
    public String enqueue(String aggregateType, String aggregateId, long aggregateVersion, String eventType,
            String orderNo, String causationId, Map<String, Object> payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(source)
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Outbox必须参与本DataSource的可写业务事务");
        String id = MessageStore.newId("evt_");
        Event event = new Event(id, eventType, 1, aggregateType, aggregateId, aggregateVersion, Instant.now(),
                orderNo, causationId, id, payload);
        String wire = new String(codec.encode(event), StandardCharsets.UTF_8);
        db.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,correlation_id,causation_id,trace_id,payload,wire_payload,next_retry_at) VALUES(?,?,?,?,?,1,?,?,?,?,?,UTC_TIMESTAMP(3))",
                id, aggregateType, aggregateId, aggregateVersion, eventType, orderNo, causationId, id, codec.payloadJson(payload), wire);
        com.tradeflow.observability.BusinessLog.messageCommitted("OUTBOX_APPENDED",event);
        return id;
    }
}
