package com.tradeflow.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 服务间纯DTO与固定格式指纹，不共享数据库实体或业务仓储。 */
public final class TradingContracts {
    private TradingContracts() { }
    public record Line(String skuId,int quantity,long expectedUnitPriceCents) { }
    public record QuoteCommand(String orderNo,String userId,String merchantId,List<Line> items) { }
    public record QuotedItem(String skuId,int quantity,long expectedUnitPriceCents,String productId,
            String productName,String specification,long priceVersion) { }
    public record Quote(String quoteNo,long amountCents,Instant expiresAt,List<QuotedItem> items) { }
    public record ReservationLine(String skuId,int quantity) { }
    public record ReservationCommand(String reservationNo,String orderNo,String quoteNo,String userId,
            String requestHash,Instant payDeadline,List<ReservationLine> items) { }
    public record Reservation(String reservationNo,String orderNo,String status,String requestHash,String rejectCode) { }
    public record ReleaseCommand(String orderNo,String decision) { }

    /** 字段先验证为受控ASCII，数量排序；排除请求自身摘要，避免两端JSON配置影响幂等。 */
    public static String reservationHash(ReservationCommand command) {
        var lines=command.items().stream().sorted(Comparator.comparing(i->new java.math.BigInteger(i.skuId()))).toList();
        StringBuilder canonical=new StringBuilder("reservation-v1|").append(command.reservationNo()).append('|')
                .append(command.orderNo()).append('|').append(command.quoteNo()).append('|').append(command.userId())
                .append('|').append(command.payDeadline().toEpochMilli()).append("|warehouse_main");
        lines.forEach(i->canonical.append('|').append(i.skuId()).append(':').append(i.quantity()));
        return sha256(canonical.toString());
    }
    /** 仅生成不可逆请求摘要，不将地址、凭据或原始请求写日志。 */
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA256不可用"); }
    }
}
