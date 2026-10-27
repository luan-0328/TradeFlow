package com.tradeflow.product.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 库存协议只访问商品库；顺序为预占头→报价→有序SKU/库存→明细和流水。 */
@Mapper
public interface ReservationMapper {
    record Header(String reservationNo,String orderNo,String quoteNo,String requestHash,String status,String decision,String rejectReason) { }
    record QuoteRow(String quoteNo,String userId,String sourceOrderNo,String boundOrderNo,String status,boolean valid) { }
    record Item(String skuId,int quantity) { }
    /** 唯一预占号及唯一订单号共同约束；先到RELEASE会留下不可重占墓碑。 */
    int create(@Param("reservationNo") String reservationNo,@Param("orderNo") String orderNo,@Param("quoteNo") String quoteNo,@Param("requestHash") String requestHash,
            @Param("status") String status,@Param("decision") String decision,@Param("expiresAt") Instant expiresAt,@Param("rejectReason") String rejectReason);
    /** 预占、拒绝和释放统一取得同一头锁，不能在不同入口反转锁顺序。 */
    Header lock(@Param("reservationNo") String reservationNo);
    /** 权威结果查询无需占有库存锁；没有记录返回404而不是虚构失败。 */
    Header find(@Param("reservationNo") String reservationNo);
    /** 已锁定的报价头提供商家事实；不信任调用方另传商家，也不依赖固定演示商家。 */
    String quoteMerchant(@Param("quoteNo") String quoteNo);
    /** 原报价按主键加锁，绑定与库存预占一起提交；超时不等于已占用。 */
    QuoteRow lockQuote(@Param("quoteNo") String quoteNo);
    /** 明细按SKU排序用于数量比对与固定顺序释放；已成功重试先返回终态。 */
    List<Item> quoteItems(@Param("quoteNo") String quoteNo);
    /** 受头锁保护，存在至少一行说明整单事务已成功提交。 */
    List<Item> items(@Param("reservationNo") String reservationNo);
    /** 一个报价只绑定一单；绑定失败必须回滚整个预占，不能部分扣库存。 */
    int bind(@Param("quoteNo") String quoteNo,@Param("boundOrderNo") String boundOrderNo);
    /** 可用转预占的原子条件更新，数量必须已验证为1至99。 */
    int reserve(@Param("skuId") String skuId,@Param("quantity") int quantity);
    /** 只能释放已记录预占数量；释放事务失败必须全部回滚。 */
    int release(@Param("skuId") String skuId,@Param("quantity") int quantity);
    /** 预占明细与两类delta流水同事务，唯一键防重复业务副作用。 */
    int item(@Param("reservationNo") String reservationNo,@Param("skuId") String skuId,@Param("quantity") int quantity);
    /** 单行流水同时记录可用/预占变化，两种数量守恒且不可改写。 */
    int ledger(@Param("businessNo") String businessNo,@Param("operationType") String operationType,@Param("skuId") String skuId,@Param("availableDelta") long availableDelta,
            @Param("reservedDelta") long reservedDelta,@Param("availableAfter") long availableAfter,@Param("reservedAfter") long reservedAfter);
    /** 终态失败仅在成功事务已回滚后的新事务保存，不能覆盖并发成功或释放。 */
    int rejected(@Param("reservationNo") String reservationNo,@Param("rejectReason") String rejectReason);
    /** 持久化RELEASE不能翻成COMMIT；已确认的预占禁止普通释放。 */
    int released(@Param("reservationNo") String reservationNo);
    /** COMMIT确认只扣预占，不加可用；只允许仍RESERVED且未释放的记录。 */
    int confirmBalance(@Param("skuId") String skuId,@Param("quantity") int quantity);
    /** 库存决策不可反转，已释放墓碑永远不能确认。 */
    int confirmed(@Param("reservationNo") String reservationNo);
}
