package com.tradeflow.messaging;

import com.tradeflow.common.BusinessException;
import java.util.List;
import java.util.Map;

/** Controller 须先核验最新平台身份；这里仅执行原 ID/原正文恢复，并与原因审计同事务提交。 */
public final class MessageOperations {
    private final MessageStore store;
    private final EventCodec codec;
    MessageOperations(MessageStore store, EventCodec codec) { this.store = store; this.codec = codec; }

    /** 平台分页只返回处置元数据，不把资金正文或原始异常内容暴露给列表。 */
    public Object list(String kind, int page, int size) {
        requireKind(kind);
        String table = MessageStore.table(kind), id = MessageStore.idColumn(kind);
        if (page < 1 || size < 1 || size > 100) throw new BusinessException(400, "INVALID_MESSAGE_PAGE", "page从1开始，size为1到100");
        String fields = id + ",status,attempt_count,next_retry_at,lease_until,claim_version,last_error,created_at,updated_at";
        // 列表只暴露冻结原文是否存在；SENT补投仍须在写入入口锁内校验完整原文契约，不泄露正文。
        fields += kind.equals("outbox") ? ",aggregate_type,aggregate_id,event_type,published_at,(wire_payload IS NOT NULL AND CHAR_LENGTH(wire_payload)>0) AS wire_frozen"
                : ",consumer_name,event_id,payload_hash";
        if (kind.equals("failure")) fields += ",reason_code,disposition,published_at";
        long offset = Math.multiplyExact((long)page - 1, size);
        List<Map<String, Object>> rows = store.db.queryForList("SELECT " + fields + " FROM " + table + " ORDER BY id DESC LIMIT ? OFFSET ?", size, offset);
        Long count = store.db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return Map.of("page", page, "size", size, "total", count == null ? 0L : count, "items", rows);
    }

    /** 不夺取正在执行的租约；不改 payload、不生成新事件 ID、不改变任何资金或库存事实。 */
    public Map<String, String> retry(String kind, String id, String actor, String reason) {
        requireKind(kind);
        String table = MessageStore.table(kind), column = MessageStore.idColumn(kind);
        if (id == null || !id.matches("[A-Za-z0-9_:.-]{1,64}") || actor == null || !actor.matches("[A-Za-z0-9_:.-]{1,64}")
                || reason == null || reason.isBlank() || reason.length() > 1000)
            throw new BusinessException(400, "INVALID_MESSAGE_RETRY", "必须提供合法原编号、平台操作者和1至1000字原因");
        // 普通读取不可变绑定，不提前持目标记录锁；所有多表操作统一 inbox→deferred→failure。
        Map<String, Object> binding = kind.equals("outbox") ? null
                : store.one("SELECT consumer_name,event_id,payload_hash FROM " + table + " WHERE " + column + "=?", id);
        if (!kind.equals("outbox") && binding == null) throw new BusinessException(404, "MESSAGE_NOT_FOUND", "消息记录不存在");
        return store.tx(() -> {
            Map<String, Object> lockedInbox = null;
            Map<String, Object> lockedDeferred = null;
            if (binding != null) {
                lockedInbox = store.inbox(MessageStore.str(binding, "consumer_name"), MessageStore.str(binding, "event_id"));
                if (lockedInbox == null)
                    throw conflict("接收记录缺失，无法安全恢复原消息");
                if (kind.equals("failure"))
                    lockedDeferred = store.one("SELECT * FROM deferred_event WHERE consumer_name=? AND event_id=? FOR UPDATE",
                            binding.get("consumer_name"), binding.get("event_id"));
            }
            Map<String, Object> row = store.one("SELECT * FROM " + table + " WHERE " + column + "=? FOR UPDATE", id);
            if (row == null) throw new BusinessException(404, "MESSAGE_NOT_FOUND", "消息记录不存在");
            if (binding != null && !MessageStore.sameBinding(binding, row)) throw conflict("消息不可变绑定已变化，拒绝恢复");
            // 即使租约已过期也交给统一领取回收，人工入口不会与旧执行者并行夺租约。
            if ("RUNNING".equals(row.get("status"))) throw conflict("消息正在执行，等待自动回收或结束");
            String before = MessageStore.str(row, "status");
            String action = "RETRY_ORIGINAL", target = kind;
            if (kind.equals("outbox") && "SENT".equals(before)) {
                // SENT仅证明Broker接收，并不证明全部预期绑定收到。人工补投必须已有冻结原文，
                // 禁止重新生成事件；与原因审计同事务复位，后续仍经领取围栏和资金guard。
                requireFrozenOriginal(row);
                action = "REDELIVER_SENT_ORIGINAL";
            }
            if (kind.equals("failure") && "MANUAL_REVIEW".equals(before)) {
                // DLQ 尚未确认的人工状态先恢复其可靠投递，非法Schema原文同样可以重投DLQ。
                store.db.update("UPDATE message_failure SET status='READY',attempt_count=0,cycle_started_at=UTC_TIMESTAMP(3),next_retry_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL,last_error=NULL,claim_version=claim_version+1,version=version+1 WHERE failure_id=?", id);
                action = "RETRY_DLQ_ORIGINAL"; target = "dlq";
            } else if (kind.equals("failure")) {
                replayFailure(row, lockedInbox, lockedDeferred); action = "REPLAY_ORIGINAL"; target = "deferred";
            }
            else {
                if (kind.equals("deferred")) {
                    if ("APPLIED".equals(before)) throw conflict("已应用的延期消息无需重放");
                    prepareInbox(row, lockedInbox);
                }
                // SENT outbox 允许同 ID 同 wire 重发，用于下游恢复，不生成新的事实。
                String cycle = kind.equals("outbox") ? "" : ",cycle_started_at=UTC_TIMESTAMP(3)";
                store.db.update("UPDATE " + table + " SET status='READY',attempt_count=0,next_retry_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL,last_error=NULL,claim_version=claim_version+1,version=version+1" + cycle + " WHERE " + column + "=?", id);
            }
            String operationId = MessageStore.newId("mop_");
            store.db.update("INSERT INTO message_operation(operation_id,kind,target_id,actor_id,reason,before_status,action) VALUES(?,?,?,?,?,?,?)",
                    operationId, kind, id, actor, reason.trim(), before, action);
            return Map.of("id", id, "kind", kind, "status", "READY", "target", target, "operationId", operationId);
        });
    }
    /** 历史投递只能复用首次wire；契约或元数据不一致时整笔回滚，不产生成功审计。 */
    private void requireFrozenOriginal(Map<String, Object> row) {
        if (!(row.get("wire_payload") instanceof String wire) || wire.isBlank())
            throw conflict("已发送消息缺少冻结原文，不能重建后补投");
        try {
            var event = codec.decode(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8)).event();
            if (!event.eventId().equals(row.get("event_id")) || !event.eventType().equals(row.get("event_type"))
                    || !event.aggregateType().equals(row.get("aggregate_type")) || !event.aggregateId().equals(row.get("aggregate_id"))
                    || event.aggregateVersion() != MessageStore.number(row, "aggregate_version")
                    || event.eventVersion() != MessageStore.number(row, "event_version")
                    || !event.correlationId().equals(row.get("correlation_id")) || !event.causationId().equals(row.get("causation_id"))
                    || !event.traceId().equals(row.get("trace_id"))) throw conflict("冻结原文与元数据不一致，拒绝补投");
        } catch (QuarantinedMessageException ex) { throw conflict("冻结原文契约不合法，拒绝补投"); }
    }
    private EventCodec.Decoded prepareInbox(Map<String, Object> row, Map<String, Object> inbox) {
        EventCodec.Decoded decoded;
        try { decoded = codec.decode(MessageStore.bytes(row)); }
        catch (QuarantinedMessageException ex) { throw conflict("原文契约仍不合法，不能修改后重放"); }
        String consumer = MessageStore.str(row, "consumer_name"), eventId = MessageStore.str(row, "event_id");
        try { codec.forConsumer(decoded, consumer, null); }
        catch (QuarantinedMessageException ex) { throw conflict("原文与目标消费者不匹配"); }
        if (!decoded.event().eventId().equals(eventId) || !decoded.hash().equals(row.get("payload_hash")))
            throw conflict("隔离原文与元数据不一致，拒绝修改后重放");
        if (inbox == null || !decoded.hash().equals(inbox.get("payload_hash")))
            throw conflict("同ID异内容冲突不能覆盖原inbox，须人工核验来源");
        if ("APPLIED".equals(inbox.get("status"))) throw conflict("原消息已应用，无需重放");
        store.inboxStatus(consumer, eventId, "DEFERRED");
        return decoded;
    }
    /** 人工重放走专用延期入口；broker DLQ 的独立发送状态保留，不伪造已送达/已应用。 */
    private void replayFailure(Map<String, Object> row, Map<String, Object> inbox, Map<String, Object> deferred) {
        EventCodec.Decoded decoded = prepareInbox(row, inbox);
        String consumer = MessageStore.str(row, "consumer_name"), eventId = MessageStore.str(row, "event_id");
        if (deferred == null) {
            // 所有创建同事件延期行的入口都先持同一inbox；此时只会新插入，不会反向等待既有延期锁。
            store.deferred(consumer, decoded, "MANUAL_REPLAY_REQUESTED");
            deferred = store.one("SELECT * FROM deferred_event WHERE consumer_name=? AND event_id=? FOR UPDATE", consumer, eventId);
        }
        if (deferred == null || "RUNNING".equals(deferred.get("status")) || "APPLIED".equals(deferred.get("status"))
                || !decoded.hash().equals(deferred.get("payload_hash"))) throw conflict("延期消息正执行、已完成或正文冲突");
        store.db.update("UPDATE deferred_event SET status='READY',attempt_count=0,next_retry_at=UTC_TIMESTAMP(3),cycle_started_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL,last_error='MANUAL_REPLAY_REQUESTED',claim_version=claim_version+1,version=version+1 WHERE deferred_id=?", deferred.get("deferred_id"));
        store.db.update("UPDATE message_failure SET disposition='RETRY_REQUESTED',version=version+1 WHERE failure_id=?", row.get("failure_id"));
    }
    private void requireKind(String kind) {
        if (kind == null || !List.of("outbox", "deferred", "failure").contains(kind))
            throw new BusinessException(400, "INVALID_MESSAGE_KIND", "kind仅允许outbox/deferred/failure");
    }
    private BusinessException conflict(String message) { return new BusinessException(409, "MESSAGE_RETRY_CONFLICT", message); }
}
