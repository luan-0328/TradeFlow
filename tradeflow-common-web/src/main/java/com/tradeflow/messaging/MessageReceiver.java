package com.tradeflow.messaging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;

/** ACK 前可靠交接：成功与 inbox 同事务，失败先回滚业务再独立持久延期/隔离。 */
final class MessageReceiver {
    private final MessageStore store;
    private final EventCodec codec;
    private final Map<String, EventHandler> handlers;
    MessageReceiver(MessageStore store, EventCodec codec, List<EventHandler> configured) {
        this.store = store; this.codec = codec;
        Map<String, EventHandler> found = new LinkedHashMap<>();
        for (EventHandler handler : configured) {
            if (!EventCodec.CONSUMERS.contains(handler.consumer()) || handler.eventTypes() == null || handler.eventTypes().isEmpty()
                    || !EventCodec.accepted(handler.consumer()).containsAll(handler.eventTypes()))
                throw new IllegalArgumentException("处理器声明的消费者/事件类型非法");
            if (found.putIfAbsent(handler.consumer(), handler) != null) throw new IllegalArgumentException("同消费者只能注册一个处理器");
        }
        handlers = Map.copyOf(found);
    }
    List<String> consumers(List<String> requested) { return requested.stream().filter(handlers::containsKey).distinct().toList(); }

    /** Broker 入口重投若已有 DEFERRED 仅确认本库可靠接收，真正重试走 resume 专门入口。 */
    void receive(String consumer, byte[] raw, String routingKey) {
        EventCodec.Decoded decoded;
        try {
            decoded = codec.decode(raw); codec.forConsumer(decoded, consumer, routingKey);
            handler(consumer, decoded.event().eventType());
        } catch (QuarantinedMessageException ex) {
            isolateRaw(consumer, raw, ex.code()); return;
        }
        try {
            store.tx(() -> {
                store.inboxInsert(consumer, decoded.event().eventId(), decoded.hash());
                Map<String, Object> inbox = store.inbox(consumer, decoded.event().eventId());
                if (!decoded.hash().equals(inbox.get("payload_hash"))) {
                    store.failure(consumer, decoded.event().eventId(), decoded.hash(), raw, "EVENT_ID_CONTENT_CONFLICT"); return null;
                }
                if (!"RECEIVED".equals(inbox.get("status"))) return null;
                handler(consumer, decoded.event().eventType()).handle(decoded.event());
                store.inboxStatus(consumer, decoded.event().eventId(), "APPLIED");
                com.tradeflow.observability.BusinessLog.messageCommitted("MESSAGE_APPLIED",decoded.event());
                return null;
            });
        } catch (DataAccessException ex) { throw ex; }
        catch (RuntimeException ex) {
            // handler 的任何部分失败都先回滚；不在已标 rollback-only 的事务中保存延期证据。
            handoff(consumer, decoded, ex);
        }
    }
    private void handoff(String consumer, EventCodec.Decoded decoded, RuntimeException error) {
        store.tx(() -> {
            store.inboxInsert(consumer, decoded.event().eventId(), decoded.hash());
            Map<String, Object> inbox = store.inbox(consumer, decoded.event().eventId());
            if (!decoded.hash().equals(inbox.get("payload_hash"))) {
                store.failure(consumer, decoded.event().eventId(), decoded.hash(), decoded.raw(), "EVENT_ID_CONTENT_CONFLICT"); return null;
            }
            // 失败事务和交接事务之间可能另一副本已完成；不能回退 APPLIED/已有隔离。
            if (!"RECEIVED".equals(inbox.get("status"))) return null;
            if (error instanceof QuarantinedMessageException permanent) {
                store.failure(consumer, decoded.event().eventId(), decoded.hash(), decoded.raw(), permanent.code());
                store.inboxStatus(consumer, decoded.event().eventId(), "QUARANTINED");
            } else {
                store.deferred(consumer, decoded, code(error));
                store.inboxStatus(consumer, decoded.event().eventId(), "DEFERRED");
            }
            return null;
        });
    }
    private void isolateRaw(String consumer, byte[] raw, String reason) {
        String eventId = codec.eventIdOrHash(raw), hash = codec.hashOrRaw(raw);
        store.tx(() -> {
            store.inboxInsert(consumer, eventId, hash);
            Map<String, Object> inbox = store.inbox(consumer, eventId);
            store.failure(consumer, eventId, hash, raw, hash.equals(inbox.get("payload_hash")) ? reason : "EVENT_ID_CONTENT_CONFLICT");
            if (hash.equals(inbox.get("payload_hash")) && "RECEIVED".equals(inbox.get("status")))
                store.inboxStatus(consumer, eventId, "QUARANTINED");
            return null;
        });
    }

    /** 延期任务绕过入口去重；任务围栏、原 inbox、业务效果及 APPLIED 在同一事务内。 */
    void resume(MessageStore.Claim claim) {
        try {
            store.tx(() -> {
                // claim领取已提交，只用其不可变绑定先锁inbox，随后再锁延期行并重验围栏。
                Map<String, Object> inbox = store.inbox(MessageStore.str(claim.row(), "consumer_name"), MessageStore.str(claim.row(), "event_id"));
                Map<String, Object> row = store.fence(claim);
                if (row == null) return null;
                if (!MessageStore.sameBinding(claim.row(), row)) throw new IllegalStateException("延期不可变绑定已变化");
                String consumer = MessageStore.str(row, "consumer_name");
                String eventId = MessageStore.str(row, "event_id");
                if (inbox == null || !row.get("payload_hash").equals(inbox.get("payload_hash"))) throw EventCodec.invalid("INBOX_BINDING_CONFLICT");
                if ("APPLIED".equals(inbox.get("status"))) { store.finish(claim, "APPLIED", ""); return null; }
                if (!"DEFERRED".equals(inbox.get("status"))) throw EventCodec.invalid("INBOX_STATE_CONFLICT");
                if (store.expiredBeforeAttempt(claim)) throw EventCodec.invalid("DEFERRED_RETRY_EXHAUSTED");
                EventCodec.Decoded decoded = codec.decode(MessageStore.bytes(row));
                codec.forConsumer(decoded, consumer, null);
                if (!decoded.hash().equals(row.get("payload_hash"))) throw EventCodec.invalid("PERSISTED_BODY_HASH_CONFLICT");
                handler(consumer, decoded.event().eventType()).handle(decoded.event());
                // 即使本地业务耗时超出租约，也必须回滚业务，不能成功后丢失围栏。
                if (!store.owns(claim)) throw new IllegalStateException("延期执行租约到期");
                store.inboxStatus(consumer, eventId, "APPLIED");
                if (!store.finish(claim, "APPLIED", "")) throw new IllegalStateException("延期完成围栏已失效");
                store.db.update("UPDATE message_failure SET disposition='RESOLVED' WHERE consumer_name=? AND event_id=? AND payload_hash=? AND disposition='RETRY_REQUESTED'", consumer, eventId, decoded.hash());
                return null;
            });
        } catch (DataAccessException ex) { throw ex; }
        catch (RuntimeException ex) {
            store.tx(() -> {
                Map<String, Object> inbox = store.inbox(MessageStore.str(claim.row(), "consumer_name"), MessageStore.str(claim.row(), "event_id"));
                Map<String, Object> row = store.fence(claim);
                if (row == null) return null;
                if (!MessageStore.sameBinding(claim.row(), row)) throw new IllegalStateException("延期不可变绑定已变化");
                String consumer = MessageStore.str(row, "consumer_name"), eventId = MessageStore.str(row, "event_id");
                boolean permanent = ex instanceof QuarantinedMessageException || store.exhausted(claim);
                if (permanent) {
                    String reason = ex instanceof QuarantinedMessageException q ? q.code() : "DEFERRED_RETRY_EXHAUSTED";
                    if (inbox != null && row.get("payload_hash").equals(inbox.get("payload_hash")) && !"APPLIED".equals(inbox.get("status")))
                        store.inboxStatus(consumer, eventId, "QUARANTINED");
                    store.failure(consumer, eventId, MessageStore.str(row, "payload_hash"), MessageStore.bytes(row), reason);
                    // 原文人工重放再次永久失败时撤销待恢复标记；不改首次原因、原文或独立DLQ发送状态。
                    store.db.update("UPDATE message_failure SET disposition='QUARANTINED',version=version+1 WHERE consumer_name=? AND event_id=? AND payload_hash=? AND disposition='RETRY_REQUESTED'",
                            consumer, eventId, row.get("payload_hash"));
                    if (!store.finish(claim, "QUARANTINED", reason)) throw new IllegalStateException("延期隔离围栏已失效");
                } else store.finish(claim, "READY", code(ex));
                return null;
            });
        }
    }
    private EventHandler handler(String consumer, String type) {
        EventHandler handler = handlers.get(consumer);
        if (handler == null || !handler.eventTypes().contains(type)) throw EventCodec.invalid("HANDLER_EVENT_UNSUPPORTED");
        return handler;
    }
    private String code(RuntimeException error) {
        return error instanceof DeferredMessageException d ? d.code() : "HANDLER_RETRYABLE_ERROR";
    }
}
