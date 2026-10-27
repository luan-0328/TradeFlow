package com.tradeflow.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 外部订单输入与视图；地址不得进入默认toString、任务错误或公共进度响应。 */
public final class OrderModels {
    private OrderModels() { }
    public record Line(@NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String skuId,
            @NotNull @Min(1) @Max(99) Integer quantity,@NotNull @Min(1) @Max(100000000) Long expectedUnitPriceCents) { }
    public record Address(@NotBlank @Size(max=64) String receiverName,@NotBlank @Size(max=32) String receiverPhone,
            @NotBlank @Size(max=255) String region,@NotBlank @Size(max=500) String detailAddress) {
        @Override public String toString() { return "Address[REDACTED]"; }
    }
    public record PlaceOrder(@NotEmpty @Size(max=20) List<@NotNull @Valid Line> items,@NotNull @Valid Address address,
            @Pattern(regexp="merchant_[a-z0-9_]{1,48}") String merchantId,@Min(0) @Max(1000000) Long expectedShippingCents) {
        public PlaceOrder(List<Line> items,Address address) { this(items,address,"merchant_demo",0L); }
        public String effectiveMerchant() { return merchantId==null?"merchant_demo":merchantId; }
        @Override public String toString() { return "PlaceOrder[REDACTED]"; }
    }
    public record Reason(@NotBlank @Size(max=255) String reason) { }
    public record Accepted(String businessNo,String state,String queryUrl,List<String> allowedActions) { }
    public record Progress(String businessNo,String state,String resultCode,String orderQueryUrl,String recoveryState,List<String> allowedActions) { }
    public record RequestLookup(boolean found,Progress progress) { }
    /** 收货资料仅供当前所属商家履约读取，禁止默认打印到日志。 */
    public record ShippingAddress(String receiverName,String receiverPhone,String region,String detailAddress) {
        @Override public String toString() { return "ShippingAddress[REDACTED]"; }
    }
    public record UnitView(int unitNo,long paidAmountCents,long reservedRefundCents,long succeededRefundCents,
            long remainingRefundCents,String returnState,boolean hasActiveAftersale) { }
    public record ItemView(String orderItemId,String skuId,String productName,String specification,long unitPriceCents,int quantity,List<UnitView> units) { }
    public record Shipment(String carrier,String trackingNo) { }
    public record OrderView(String orderNo,String paymentNo,String status,String stockSyncStatus,String fulfillmentLock,
            long amountCents,String currency,Instant payDeadline,List<ItemView> items,List<String> allowedActions,Instant createdAt,Shipment shipment) { }
    public record Page<T>(List<T> items,int page,int pageSize,long total) { }
    public record TaskView(String taskNo,String businessNo,String state,int attemptCount,Instant nextRetryAt,
            String lastError,long claimVersion,Instant leaseUntil) { }
}
