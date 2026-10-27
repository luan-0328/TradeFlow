package com.tradeflow.messaging;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradeflow.contracts.MessagingContracts.Event;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 独立 JSON 配置兼容扩展字段；完整树（含未知字段）参与规范化摘要，持久正文不重写。 */
final class EventCodec {
    static final String EXCHANGE = "tradeflow.events";
    static final String DLX = "tradeflow.dlx";
    static final Set<String> CONSUMERS = Set.of("order.payment", "order.refund", "aftersale.refund", "order.audit");
    static final Map<String, String> ROUTES = Map.of(
            "PaymentSucceeded", "payment.succeeded.v1", "PaymentFailed", "payment.failed.v1",
            "PaymentClosed", "payment.closed.v1", "RefundSucceeded", "refund.succeeded.v1",
            "RefundFailed", "refund.failed.v1", "OrderPaid", "order.paid.v1",
            "OrderClosed", "order.closed.v1", "AftersaleCompleted", "aftersale.completed.v1");
    private final ObjectMapper json;
    EventCodec(ObjectMapper mapper) {
        json = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    record Decoded(Event event, String hash, byte[] raw) { }

    /** 校验资金事实形状和信封绑定；最终金额/业务状态核验仍由本库 handler 完成。 */
    Decoded decode(byte[] raw) {
        try {
            if (raw.length > 1_048_576) throw invalid("MESSAGE_TOO_LARGE");
            JsonNode root = json.readTree(utf8(raw));
            if (root == null || !root.isObject()) throw invalid("INVALID_ENVELOPE");
            for (String field : Set.of("eventId", "eventType", "aggregateType", "aggregateId", "correlationId", "causationId", "traceId"))
                id(root, field);
            if (!root.path("eventVersion").isIntegralNumber() || !root.path("eventVersion").canConvertToInt()
                    || root.path("eventVersion").intValue() != 1)
                throw invalid("UNSUPPORTED_EVENT_VERSION");
            if (!root.path("aggregateVersion").isIntegralNumber() || !root.path("aggregateVersion").canConvertToLong()
                    || root.path("aggregateVersion").longValue() < 0) throw invalid("INVALID_AGGREGATE_VERSION");
            instant(root, "occurredAt");
            String type = root.path("eventType").asText();
            if (!ROUTES.containsKey(type)) throw invalid("UNSUPPORTED_EVENT_TYPE");
            JsonNode payload = root.path("payload");
            if (!payload.isObject()) throw invalid("INVALID_PAYLOAD");
            String aggregate = type.startsWith("Payment") ? "PAYMENT" : type.startsWith("Refund") ? "REFUND"
                    : type.startsWith("Order") ? "ORDER" : "AFTERSALE";
            equal(root.path("aggregateType").asText(), aggregate);
            equal(id(payload, "orderNo"), root.path("correlationId").asText());
            String aggregateField = switch (aggregate) { case "PAYMENT" -> "paymentNo"; case "REFUND" -> "refundNo";
                case "AFTERSALE" -> "aftersaleNo"; default -> "orderNo"; };
            equal(id(payload, aggregateField), root.path("aggregateId").asText());
            if (aggregate.equals("PAYMENT") || aggregate.equals("REFUND")) {
                id(payload, "paymentNo");
                if (aggregate.equals("REFUND")) id(payload, "sourceNo");
                JsonNode amount = payload.path("amountCents");
                if (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() <= 0
                        || amount.longValue() > 100_000_000L || !"CNY".equals(payload.path("currency").asText()))
                    throw invalid("INVALID_MONEY_FACT");
                JsonNode version = payload.path("channelVersion");
                if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 1)
                    throw invalid("INVALID_CHANNEL_VERSION");
                if (version.longValue() != root.path("aggregateVersion").longValue()) throw invalid("EVENT_BINDING_MISMATCH");
                String status = type.endsWith("Succeeded") ? "SUCCEEDED" : type.endsWith("Failed") ? "FAILED" : "CLOSED";
                equal(payload.path("status").asText(), status);
                instant(payload, "updatedAt");
                // 完整 ChannelFact 的 nullable 字段也必须显式出现，缺字段不能作为 null 猜测。
                if (!payload.has("succeededAt")) throw invalid("INVALID_EVENT_TIME");
                if (!status.equals("SUCCEEDED") && !payload.path("succeededAt").isNull())
                    throw invalid("NON_SUCCESS_WITH_SUCCEEDED_AT");
                if (aggregate.equals("PAYMENT")) id(payload, "channelTransactionNo");
                else {
                    if (!payload.has("channelRefundNo")) throw invalid("INVALID_CHANNEL_REFUND_NO");
                    if (!payload.path("channelRefundNo").isNull()) text(payload, "channelRefundNo", 64);
                }
                if (status.equals("SUCCEEDED")) {
                    instant(payload, "succeededAt");
                    id(payload, aggregate.equals("PAYMENT") ? "channelTransactionNo" : "channelRefundNo");
                }
            } else if (type.equals("OrderPaid")) id(payload, "paymentNo");
            else if (type.equals("OrderClosed")) text(payload, "reason", 255);
            else if (type.equals("AftersaleCompleted")) id(payload, "refundNo");
            return new Decoded(json.treeToValue(root, Event.class), hash(json.writeValueAsBytes(canonical(root))), raw.clone());
        } catch (QuarantinedMessageException ex) { throw ex; }
        catch (Exception ex) { throw invalid("INVALID_EVENT_JSON"); }
    }
    void forConsumer(Decoded decoded, String consumer, String routingKey) {
        if (!accepted(consumer).contains(decoded.event().eventType())) throw invalid("CONSUMER_EVENT_MISMATCH");
        if (routingKey != null && !ROUTES.get(decoded.event().eventType()).equals(routingKey))
            throw invalid("ROUTING_KEY_MISMATCH");
    }
    static Set<String> accepted(String consumer) {
        return switch (consumer) {
            case "order.payment" -> Set.of("PaymentSucceeded", "PaymentFailed", "PaymentClosed");
            case "order.refund", "aftersale.refund" -> Set.of("RefundSucceeded", "RefundFailed");
            case "order.audit" -> Set.of("OrderPaid", "OrderClosed", "AftersaleCompleted");
            default -> throw new IllegalArgumentException("未知消息消费者");
        };
    }
    byte[] encode(Event event) {
        try { byte[] raw = json.writeValueAsBytes(event); decode(raw); return raw; }
        catch (QuarantinedMessageException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("不能序列化事件", ex); }
    }
    Map<String, Object> payload(String payload, String expectedEventId) {
        try {
            JsonNode node = json.readTree(payload);
            // B3/B4 历史 outbox 是 Event{eventId,fact}，仅首次冻结时展开完整 ChannelFact。
            if (node.has("fact")) {
                equal(id(node, "eventId"), expectedEventId);
                node = node.get("fact");
            }
            if (!node.isObject()) throw invalid("INVALID_LEGACY_PAYLOAD");
            return json.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (QuarantinedMessageException ex) { throw ex; }
        catch (Exception ex) { throw invalid("INVALID_LEGACY_PAYLOAD"); }
    }
    String payloadJson(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("不能序列化载荷", ex); }
    }
    String eventIdOrHash(byte[] raw) {
        try { return id(json.readTree(utf8(raw)), "eventId"); }
        catch (Exception ex) { return "invalid_" + hash(raw).substring(0, 48); }
    }
    String hashOrRaw(byte[] raw) {
        try { return hash(json.writeValueAsBytes(canonical(json.readTree(utf8(raw))))); }
        catch (Exception ex) { return hash(raw); }
    }
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = json.createObjectNode();
            TreeSet<String> names = new TreeSet<>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> object.set(name, canonical(value.get(name)))); return object;
        }
        if (value.isArray()) { ArrayNode array = json.createArrayNode(); value.forEach(item -> array.add(canonical(item))); return array; }
        return value;
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private String utf8(byte[] raw) throws java.nio.charset.CharacterCodingException {
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }
    static String id(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        if (value == null || !value.isTextual() || !value.textValue().matches("[A-Za-z0-9_:.-]{1,64}"))
            throw invalid("INVALID_" + field.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT));
        return value.textValue();
    }
    private void instant(JsonNode root, String field) {
        if (!root.path(field).isTextual()) throw invalid("INVALID_EVENT_TIME");
        Instant.parse(root.path(field).textValue());
    }
    private void text(JsonNode root, String field, int maximum) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.textValue().codePointCount(0, value.textValue().length()) > maximum)
            throw invalid("INVALID_PAYLOAD_FIELD");
    }
    private void equal(String actual, String expected) { if (!expected.equals(actual)) throw invalid("EVENT_BINDING_MISMATCH"); }
    static QuarantinedMessageException invalid(String code) { return new QuarantinedMessageException(code); }
}
