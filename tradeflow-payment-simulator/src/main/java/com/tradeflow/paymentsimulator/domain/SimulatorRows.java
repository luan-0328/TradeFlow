package com.tradeflow.paymentsimulator.domain;
import java.time.Instant;
/** 模拟通道自己的持久化行，不向其他服务共享实体。 */
public final class SimulatorRows {
 private SimulatorRows() { }
 public record Channel(String paymentNo,String orderNo,long amountCents,String currency,String status,
   String channelTransactionNo,long channelVersion,Instant updatedAt,Instant succeededAt) { }
 public record Scenario(String paymentNo,String mode) { }
 public record Task(String taskNo,String paymentNo,String state,int attemptCount,long claimVersion,
   String leaseOwner,String payload,int deliveriesRequired,int deliveriesDone) { }
}
