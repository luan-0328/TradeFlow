package com.tradeflow.contracts;

import java.time.Instant;
import java.util.List;

/** B4退款契约：授权金额由订单权威生成，客户端不能直接向支付指定金额。 */
public final class RefundContracts {
 private RefundContracts() { }
 /** 每个虚拟购买件的申请金额；标识用字符串，金额为整数分。 */
 public record Allocation(String orderItemId,int unitNo,long amountCents) { }
 public record Reserve(String aftersaleNo,String orderNo,String userId,String type,List<Allocation> allocations) {
  public Reserve(String aftersaleNo,String orderNo,String userId,String type){this(aftersaleNo,orderNo,userId,type,List.of());}
 }
 /** 预览只读，不占权益；空分配预览全部剩余，提交必须再次逐件校验。 */
 public record PreviewRequest(String orderNo,String userId,String type,List<Allocation> allocations) { }
 public record EntitlementUnit(String orderItemId,int unitNo,String skuId,long paidAmountCents,long succeededRefundCents,long reservedRefundCents,long remainingCents,String activeAftersaleNo,String returnState) { }
 public record Preview(String orderNo,String userId,String merchantId,String type,long amountCents,String currency,List<Allocation> allocations,List<EntitlementUnit> units) { }
 /** 验收通过意图使用固定RET:售后号，重复登记不得改变购买件绑定。 */
 public record ReturnAccept(String orderNo,String receiptNo,List<ReturnDisposition> dispositions) {
  public ReturnAccept(String orderNo,String receiptNo){this(orderNo,receiptNo,List.of());}
 }
 /** 验收认可后逐件指定实物去向；金额仍取原授权，不允许该字段改退款额度。 */
 public record ReturnDisposition(String orderItemId,int unitNo,String disposition) { }
 public record ReturnLine(String orderItemId,int unitNo,String skuId,String disposition) {
  public ReturnLine(String orderItemId,int unitNo,String skuId){this(orderItemId,unitNo,skuId,"SALEABLE");}
 }
 public record ReturnReceipt(String receiptNo,String sourceNo,String orderNo,String reservationNo,String status,List<ReturnLine> lines) { }
 public record ReturnRestock(String receiptNo,String sourceNo,String orderNo,String reservationNo) { }
 public record Change(String orderNo,String reason) { }
 public record Authorization(String sourceNo,String sourceType,String orderNo,String paymentNo,String userId,String merchantId,long amountCents,String currency,String state,long version) { }
 public record Submit(String refundNo,String sourceNo,String orderNo) { }
 public record View(String refundNo,String sourceNo,String orderNo,String paymentNo,long amountCents,String currency,String status,String lastError) { }
 public record ChannelCommand(String refundNo,String paymentNo,String orderNo,String sourceNo,long amountCents,String currency) { }
 public record ChannelFact(String refundNo,String paymentNo,String orderNo,String sourceNo,long amountCents,String currency,String status,String channelRefundNo,long channelVersion,Instant updatedAt,Instant succeededAt) { }
 public record Callback(String notificationNo,ChannelFact fact) { }
 public record Event(String eventId,ChannelFact fact) { }
 public record Restock(String receiptNo,String orderNo,String reservationNo,String sourceNo) { }
 public record RestockView(String receiptNo,String orderNo,String reservationNo,String sourceNo,String status) { }
}
