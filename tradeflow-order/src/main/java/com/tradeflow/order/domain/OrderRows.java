package com.tradeflow.order.domain;

import java.time.Instant;

/** 订单库内部读模型，不跨服务共享；含地址的创建材料禁止默认打印。 */
public final class OrderRows {
    private OrderRows() { }
    public record Intent(String orderNo,String userId,String paymentNo,String reservationNo,String requestHash,
            String requestPayload,String state,String resultCode,Instant createdAt) {
        @Override public String toString() { return "Intent[REDACTED]"; }
    }
    public record Header(String orderNo,String userId,String merchantId,String paymentNo,String reservationNo,String quoteNo,
            long totalAmountCents,String currency,String status,String stockDecision,String stockSyncStatus,String fulfillmentLock,Instant payDeadline,Instant createdAt,String closePhase) { }
    public record Item(String id,String skuId,String productNameSnapshot,String specSnapshot,long unitPriceCents,int quantity) { }
    public record RequestRow(String requestHash,String businessNo,String state) { }
    public record Task(String taskNo,String businessNo,String state,int attemptCount,long claimVersion,String leaseOwner,Instant leaseUntil,Instant createdAt) { }
}
