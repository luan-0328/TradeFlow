package com.tradeflow.product.domain;

/** 商品服务持久化读模型；不跨服务共享实体。 */
public final class ProductRows {
    private ProductRows() { }
    public record ProductRow(String id,String merchantId,String name,String description,String status,long version) { }
    public record SkuRow(String id,String productId,String skuCode,String specification,long priceCents,
            long priceVersion,String status,long version,String merchantId,String productStatus) { }
    public record InventoryRow(long availableQty,long reservedQty) { }
    public record IdempotencyRow(String requestHash,String state,String businessNo,String resultSnapshot) { }
}
