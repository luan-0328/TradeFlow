package com.tradeflow.contracts;

import java.time.Instant;
import java.util.Map;

/** B5 不可变事件信封；payload 仅包含事实 DTO，不共享业务实体。 */
public final class MessagingContracts {
    private MessagingContracts() { }

    public record Event(String eventId, String eventType, int eventVersion, String aggregateType,
            String aggregateId, long aggregateVersion, Instant occurredAt, String correlationId,
            String causationId, String traceId, Map<String, Object> payload) { }
}
