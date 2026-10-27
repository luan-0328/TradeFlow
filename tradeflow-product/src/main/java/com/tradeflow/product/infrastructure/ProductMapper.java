package com.tradeflow.product.infrastructure;

import com.tradeflow.product.domain.ProductRows.*;
import com.tradeflow.product.api.ProductModels.LedgerView;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 仅操作trade_product；所有多表写由应用事务控制，SQL中不访问用户库。 */
@Mapper
public interface ProductMapper {
    /** 创建商品，库存不会随商品创建凭空增加。 */
    int createProduct(Map<String,Object> row);
    /** SKU全局编码唯一，任何一个编码冲突都会回滚整个商品创建。 */
    int createSku(Map<String,Object> row);
    /** 首次余额必须为零；后续库存只能通过单据和不可变流水改变。 */
    int zeroInventory(@Param("skuId") String skuId);
    /** 主键读取不代表授权，应用必须按公开可见性或商家归属复核。 */
    ProductRow product(@Param("id") String id);
    /** 追加规格及图片时锁定商品，序列化数量上限和归属判断。 */
    ProductRow lockProduct(@Param("id") String id);
    /** 公开查询仅上架商品，商家列表必须按权威归属过滤。 */
    List<ProductRow> products(@Param("merchantId") String merchantId,@Param("size") int size,@Param("offset") long offset);
    /** 统计使用与列表相同的商家/上架过滤条件。 */
    long countProducts(@Param("merchantId") String merchantId);
    /** 商品关键词按普通文本匹配，公开店铺查询仍限定上架，分页和计数完全同条件。 */
    List<ProductRow> searchProducts(@Param("merchantId") String merchantId,@Param("merchant") boolean merchant,@Param("keyword") String keyword,@Param("size") int size,@Param("offset") long offset);
    /** 过滤总数不泄露其他商家草稿。 */
    long countSearchProducts(@Param("merchantId") String merchantId,@Param("merchant") boolean merchant,@Param("keyword") String keyword);
    /** 商品内规格按主键稳定排序，公开接口进一步过滤停用规格。 */
    List<SkuRow> skus(@Param("productId") String productId);
    /** SKU所属商品和商家不可由编辑接口变更，归属读取可安全用于本地条件更新。 */
    SkuRow sku(@Param("id") String id);
    /** 报价时共享锁固定当前商品/价格事实；期间只做本地DB读写，不调用远端。 */
    SkuRow quoteSku(@Param("id") String id);
    /** 乐观版本与归属同时作为更新条件；影响0行视为冲突，禁止无条件覆盖。 */
    int editProduct(@Param("id") String id,@Param("merchantId") String merchantId,@Param("version") long version,
            @Param("name") String name,@Param("description") String description,@Param("status") String status);
    /** 先基于原单价计算price_version，再写新价；版本冲突必须由客户端重新读取。 */
    int editSku(@Param("id") String id,@Param("version") long version,@Param("priceCents") long priceCents,@Param("status") String status);
    /** 普通余额展示不加锁，不可据此先读后无条件写库存。 */
    InventoryRow inventory(@Param("warehouseId") String warehouseId,@Param("skuId") String skuId);
    /** 调整事务按SKU主键升序取得余额锁，不锁跨服务资源或调用远端。 */
    InventoryRow lockInventory(@Param("warehouseId") String warehouseId,@Param("skuId") String skuId);
    /** 最终数据库条件防负库存及溢出；预占余额不受人工调整影响。 */
    int adjust(@Param("warehouseId") String warehouseId,@Param("skuId") String skuId,@Param("delta") long delta);
    /** 单据、明细、余额、流水与审计在同事务创建，崩溃回滚后可用原幂等键重试。 */
    int receipt(@Param("receiptNo") String receiptNo,@Param("merchantId") String merchantId,@Param("reason") String reason,@Param("operatorId") String operatorId);
    /** 调整明细与余额/流水同事务写入，唯一单据SKU键防重复。 */
    int receiptItem(@Param("receiptNo") String receiptNo,@Param("warehouseId") String warehouseId,@Param("skuId") String skuId,@Param("quantityDelta") long quantityDelta);
    /** 唯一业务流水防止同一单据同一SKU重复记账，流水禁止UPDATE/DELETE。 */
    int ledger(@Param("businessNo") String businessNo,@Param("warehouseId") String warehouseId,@Param("skuId") String skuId,
            @Param("availableDelta") long availableDelta,@Param("availableAfter") long availableAfter,@Param("reservedAfter") long reservedAfter);
    /** 流水查询按商家过滤并分页，不能用猜测SKU查询其他商家库存。 */
    List<LedgerView> ledgers(@Param("merchantId") String merchantId,@Param("size") int size,@Param("offset") long offset);
    /** 流水总数使用商家范围，禁止跨归属统计。 */
    long countLedgers(@Param("merchantId") String merchantId);
    /** 只有确切同作用域唯一键冲突才复用，后续加锁比较摘要，不能吞掉其他写错误。 */
    int claim(@Param("actorType") String actorType,@Param("actorId") String actorId,@Param("operation") String operation,@Param("idempotencyKey") String idempotencyKey,@Param("requestHash") String requestHash,@Param("businessNo") String businessNo);
    /** 幂等行锁持有到业务本地事务结束，同请求重试等待提交后读取同一快照。 */
    IdempotencyRow lockRequest(@Param("actorType") String actorType,@Param("actorId") String actorId,@Param("operation") String operation,@Param("idempotencyKey") String idempotencyKey);
    /** 仅旧成功键兼容时读取原快照/单据商家事实；查不到证明就拒绝重放，禁止猜测历史商家。 */
    String legacyRequestMerchant(@Param("operation") String operation,@Param("businessNo") String businessNo,@Param("snapshot") String snapshot);
    /** 只在本地业务已完成时保存快照，与该业务同一事务提交。 */
    int completed(@Param("actorType") String actorType,@Param("actorId") String actorId,@Param("operation") String operation,@Param("idempotencyKey") String idempotencyKey,@Param("resultSnapshot") String resultSnapshot);
    /** 报价头与明细同事务，不提前绑定订单，后续预占事务再做唯一绑定。 */
    int quote(@Param("quoteNo") String quoteNo,@Param("userId") String userId,@Param("merchantId") String merchantId,@Param("sourceOrderNo") String sourceOrderNo,@Param("requestHash") String requestHash,@Param("expiresAt") Instant expiresAt);
    /** 固定已确认的单价及价格版本，之后商品改价不改写既有报价。 */
    int quoteItem(@Param("quoteNo") String quoteNo,@Param("skuId") String skuId,@Param("quantity") int quantity,@Param("unitPriceCents") long unitPriceCents,@Param("priceVersion") long priceVersion,
            @Param("productIdSnapshot") String productIdSnapshot,@Param("productNameSnapshot") String productNameSnapshot,@Param("specSnapshot") String specSnapshot);
    /** 所有敏感商家写审计与业务提交一致，不记录用户令牌或服务凭证。 */
    int audit(@Param("actorId") String actorId,@Param("action") String action,@Param("businessNo") String businessNo,@Param("requestId") String requestId,
            @Param("traceId") String traceId,@Param("reason") String reason,@Param("changeSummary") String changeSummary);
}
