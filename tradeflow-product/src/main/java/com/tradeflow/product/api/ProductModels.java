package com.tradeflow.product.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 商品与库存接口契约；所有主键在JSON中使用字符串，金额固定整数分。 */
public final class ProductModels {
    private ProductModels() { }
    public enum ProductStatus { DRAFT, ON_SALE, OFF_SALE }
    public enum SkuStatus { ACTIVE, DISABLED }
    public record WriteSku(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String skuCode,
            @NotNull @Size(max=500) String specification,@NotNull @Min(1) @Max(100000000) Long priceCents) { }
    public record ProductWrite(@NotBlank @Size(max=200) String name,@NotNull @Size(max=2000) String description,
            @NotNull ProductStatus status,@NotEmpty @Size(max=100) List<@Valid WriteSku> skus) { }
    public record ProductEdit(@NotNull @Min(0) Long version,@NotBlank @Size(max=200) String name,
            @NotNull @Size(max=2000) String description,@NotNull ProductStatus status) { }
    public record SkuEdit(@NotNull @Min(0) Long version,@NotNull @Min(1) @Max(100000000) Long priceCents,
            @NotNull SkuStatus status) { }
    public record SkuView(String skuId,String skuCode,String specification,long priceCents,String status,
            long version,long priceVersion,long availableQty,long reservedQty) { }
    public record ProductView(String productId,String merchantId,String name,String description,String status,
            long version,List<SkuView> skus) { }
    public record Page<T>(List<T> items,int page,int pageSize,long total) { }
    public record AdjustmentLine(@NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String skuId,
            @NotNull @Min(-1000000) @Max(1000000) Long quantityDelta) { }
    public record Adjustment(@NotBlank @Pattern(regexp="warehouse_main") String warehouseId,
            @NotBlank @Size(max=500) String reason,@NotEmpty @Size(max=100) List<@Valid AdjustmentLine> items) { }
    public record ReceiptView(String businessNo,String status) { }
    public record LedgerView(String businessNo,String skuId,String operationType,long availableDelta,
            long reservedDelta,long availableAfter,long reservedAfter,Instant createdAt) { }
    public record QuoteLine(@NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String skuId,
            @NotNull @Min(1) @Max(99) Integer quantity,
            @NotNull @Min(1) @Max(100000000) Long expectedUnitPriceCents) { }
    public record QuoteRequest(@NotBlank @Pattern(regexp="ord_[a-f0-9]{32}") String orderNo,
            @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String userId,
            @NotBlank @Pattern(regexp="merchant_[a-z0-9_]{1,48}") String merchantId,
            @NotEmpty @Size(max=20) List<@NotNull @Valid QuoteLine> items) { }
    public record QuoteView(String quoteNo,long amountCents,Instant expiresAt,List<com.tradeflow.contracts.TradingContracts.QuotedItem> items) { }
}
