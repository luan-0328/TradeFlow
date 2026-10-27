package com.tradeflow.aftersale.domain;

/** 售后持久快照；金额只取订单授权，退款失败仍保留执行义务。 */
public record Aftersale(String aftersaleNo,String orderNo,String userId,String merchantId,String type,String reason,
        String status,String refundNo,String refundStatus,Long amountCents,String currency,String lastError) { }
