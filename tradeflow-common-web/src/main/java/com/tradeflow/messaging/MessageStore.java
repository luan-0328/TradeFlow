package com.tradeflow.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅操作当前服务 DataSource；领取/回写均为短事务，网络从不进入本类事务回调。 */
final class MessageStore {
    final JdbcTemplate db;
    private final TransactionTemplate transactions;
    final String owner = "msg_" + UUID.randomUUID().toString().replace("-", "");
    MessageStore(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(20);
    }
    <T> T tx(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = db.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    record Claim(String kind, String id, String owner, long version, Map<String, Object> row) { }

    /** SKIP LOCKED 抢占到期任务并立刻提交；重领过期 RUNNING 同样递增围栏和尝试次数。 */
    Claim claim(String kind, List<String> consumers) {
        if (kind.equals("deferred") && consumers.isEmpty()) return null;
        return tx(() -> {
            String table = table(kind), id = idColumn(kind);
            String cycle = kind.equals("outbox")
                    ? ",COALESCE((SELECT MAX(o.created_at) FROM message_operation o WHERE o.kind='outbox' AND o.target_id=t.event_id),t.created_at) AS cycle_started_at" : "";
            String filter = kind.equals("deferred") ? " AND consumer_name IN (" + String.join(",", java.util.Collections.nCopies(consumers.size(), "?")) + ")" : "";
            Map<String, Object> row = one("SELECT t.*" + cycle + " FROM " + table + " t WHERE ((status='READY' AND next_retry_at<=UTC_TIMESTAMP(3)) OR (status='RUNNING' AND lease_until<=UTC_TIMESTAMP(3)))"
                    + filter + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", kind.equals("deferred") ? consumers.toArray() : new Object[0]);
            if (row == null) return null;
            String value = str(row, id);
            db.update("UPDATE " + table + " SET status='RUNNING',lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),claim_version=claim_version+1,attempt_count=attempt_count+1,version=version+1 WHERE " + id + "=?", owner, value);
            row.put("attempt_count", number(row, "attempt_count") + 1);
            row.put("claim_version", number(row, "claim_version") + 1);
            return new Claim(kind, value, owner, number(row, "claim_version"), row);
        });
    }
    /** 所有业务/完成回写同时核对 owner、claim_version、RUNNING 与未过期租约。 */
    Map<String, Object> fence(Claim claim) {
        return one("SELECT * FROM " + table(claim.kind()) + " WHERE " + idColumn(claim.kind())
                + "=? AND status='RUNNING' AND lease_owner=? AND claim_version=? AND lease_until>UTC_TIMESTAMP(3) FOR UPDATE",
                claim.id(), claim.owner(), claim.version());
    }
    boolean owns(Claim claim) {
        return one("SELECT id FROM " + table(claim.kind()) + " WHERE " + idColumn(claim.kind())
                + "=? AND status='RUNNING' AND lease_owner=? AND claim_version=? AND lease_until>UTC_TIMESTAMP(3)",
                claim.id(), claim.owner(), claim.version()) != null;
    }
    boolean finish(Claim claim, String status, String code) {
        String published = claim.kind().equals("deferred") ? "" : ",published_at=IF(?='SENT',UTC_TIMESTAMP(3),published_at)";
        List<Object> args = new java.util.ArrayList<>();
        args.add(status); args.add(code); args.add(delay(number(claim.row(), "attempt_count")));
        if (!claim.kind().equals("deferred")) args.add(status);
        args.addAll(List.of(claim.id(), claim.owner(), claim.version()));
        return db.update("UPDATE " + table(claim.kind()) + " SET status=?,last_error=?,next_retry_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL ? SECOND),lease_owner=NULL,lease_until=NULL,version=version+1" + published
                + " WHERE " + idColumn(claim.kind()) + "=? AND status='RUNNING' AND lease_owner=? AND claim_version=? AND lease_until>UTC_TIMESTAMP(3)", args.toArray()) == 1;
    }
    boolean exhausted(Claim claim) {
        Instant start = instant(claim.row().get("cycle_started_at"));
        return number(claim.row(), "attempt_count") >= 10 || start.plusSeconds(1800).isBefore(Instant.now());
    }
    boolean expiredBeforeAttempt(Claim claim) {
        return number(claim.row(), "attempt_count") > 10 || instant(claim.row().get("cycle_started_at")).plusSeconds(1800).isBefore(Instant.now());
    }
    void inboxInsert(String consumer, String eventId, String hash) {
        db.update("INSERT INTO inbox_message(consumer_name,event_id,payload_hash,status) VALUES(?,?,?,'RECEIVED') ON DUPLICATE KEY UPDATE event_id=event_id", consumer, eventId, hash);
    }
    Map<String, Object> inbox(String consumer, String eventId) {
        return one("SELECT * FROM inbox_message WHERE consumer_name=? AND event_id=? FOR UPDATE", consumer, eventId);
    }
    void inboxStatus(String consumer, String eventId, String status) {
        db.update("UPDATE inbox_message SET status=?,updated_at=UTC_TIMESTAMP(3) WHERE consumer_name=? AND event_id=?", status, consumer, eventId);
    }
    /** 同 consumer+ID 只允许原摘要；冲突正文写独立隔离行，不覆盖已接受的 inbox。 */
    void failure(String consumer, String eventId, String hash, byte[] raw, String code) {
        db.update("INSERT INTO message_failure(failure_id,consumer_name,event_id,payload_hash,raw_body,reason_code,status,next_retry_at,cycle_started_at) VALUES(?,?,?,?,?,?,'READY',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE failure_id=failure_id",
                newId("fail_"), consumer, eventId, hash, raw, code);
    }
    void deferred(String consumer, EventCodec.Decoded decoded, String code) {
        db.update("INSERT INTO deferred_event(deferred_id,consumer_name,event_id,event_type,payload_hash,raw_body,status,last_error,next_retry_at,cycle_started_at) VALUES(?,?,?,?,?,?,'READY',?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 SECOND),UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE deferred_id=deferred_id",
                newId("def_"), consumer, decoded.event().eventId(), decoded.event().eventType(), decoded.hash(), decoded.raw(), code);
    }
    static int delay(long attempt) { return switch ((int)Math.min(Math.max(attempt, 1), 5)) { case 1 -> 5; case 2 -> 15; case 3 -> 60; case 4 -> 300; default -> 900; }; }
    static String table(String kind) { return switch (kind) { case "outbox" -> "outbox_event"; case "deferred" -> "deferred_event"; case "failure" -> "message_failure"; default -> throw new IllegalArgumentException("kind仅允许outbox/deferred/failure"); }; }
    static String idColumn(String kind) { return switch (kind) { case "outbox" -> "event_id"; case "deferred" -> "deferred_id"; case "failure" -> "failure_id"; default -> throw new IllegalArgumentException("未知kind"); }; }
    static String newId(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
    static String str(Map<String, Object> row, String key) { return String.valueOf(row.get(key)); }
    static long number(Map<String, Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    /** 先读不可变绑定再按统一顺序加锁；锁后必须重验，不能跟随被修改的关联去锁其他 inbox。 */
    static boolean sameBinding(Map<String, Object> before, Map<String, Object> after) {
        return java.util.Objects.equals(before.get("consumer_name"), after.get("consumer_name"))
                && java.util.Objects.equals(before.get("event_id"), after.get("event_id"))
                && java.util.Objects.equals(before.get("payload_hash"), after.get("payload_hash"));
    }
    static Instant instant(Object value) {
        if (value instanceof Timestamp stamp) return stamp.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
        if (value instanceof Instant instant) return instant;
        throw new IllegalStateException("无法解析消息UTC时间");
    }
    static byte[] bytes(Map<String, Object> row) {
        Object value = row.get("raw_body");
        if (value instanceof byte[] raw) return raw;
        try { if (value instanceof java.sql.Blob blob) return blob.getBytes(1, Math.toIntExact(blob.length())); }
        catch (java.sql.SQLException ex) { throw new IllegalStateException("无法读取持久消息正文", ex); }
        throw new IllegalStateException("消息正文类型错误");
    }
}
