package com.tradeflow.contracts;

import java.time.Instant;

/** B3内部支付/通道事实DTO；金额只能取订单权威快照，不由前端指定。 */
public final class PaymentContracts {
    private PaymentContracts() { }
    public record Intent(String orderNo,String paymentNo,String userId,long amountCents,String currency,
            Instant payDeadline,String orderStatus,String merchantId) {
        public Intent(String orderNo,String paymentNo,String userId,long amountCents,String currency,Instant payDeadline,String orderStatus) { this(orderNo,paymentNo,userId,amountCents,currency,payDeadline,orderStatus,null); }
    }
    public record CloseCommand(String orderNo) { }
    public record ChannelCommand(String paymentNo,String orderNo,long amountCents,String currency) { }
    public record ChannelFact(String paymentNo,String orderNo,long amountCents,String currency,String status,
            String channelTransactionNo,long channelVersion,Instant updatedAt,Instant succeededAt) { }
    public record View(String paymentNo,String orderNo,String userId,long amountCents,String currency,String status,
            String closeIntent,String channelTransactionNo,Instant succeededAt,String lastError) { }
    public record Event(String eventId,ChannelFact fact) { }
    public record Callback(String notificationNo,ChannelFact fact) { }
}
