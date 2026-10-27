package com.tradeflow.product.infrastructure;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 不可售余额只访问商品库；所有写入先锁对应可售库存行，避免退货与人工处置反向锁序。 */
@Mapper
public interface InventoryConditionMapper {
    /** 同SKU由库存行串行化建桶；重复建桶不改变原有数量。 */
    int ensure(@Param("warehouseId")String warehouseId,@Param("skuId")String skuId,@Param("stockCondition")String stockCondition);
    /** 原子边界同时限制负数与上限，不能先读余额再无条件扣减。 */
    int adjust(@Param("warehouseId")String warehouseId,@Param("skuId")String skuId,@Param("stockCondition")String stockCondition,@Param("delta")long delta);
    /** 只返回所属商家的不可售实物余额；报损为不可变处置记录，不计在库可售余额。 */
    List<Map<String,Object>> list(@Param("merchantId")String merchantId,@Param("size")int size,@Param("offset")long offset);
    /** 总数沿用列表的商家归属范围，不能跨商家汇总不可售库存。 */
    long count(@Param("merchantId") String merchantId);
    /** 单SQL限定原主体、固定操作、原键与SKU；查不到仅表示当前未确认，不能据此新建处置。 */
    com.tradeflow.product.application.InventoryConditionService.Result lookup(@Param("actorId")String actorId,@Param("idempotencyKey")String idempotencyKey,@Param("skuId")String skuId);
    /** 审计单据与条件余额、可售余额及幂等结果同事务提交；回滚不留下半张处置单。 */
    int operation(@Param("operationNo")String operationNo,@Param("skuId")String skuId,@Param("fromCondition")String fromCondition,@Param("toCondition")String toCondition,@Param("quantity")long quantity,@Param("actorId")String actorId,@Param("reason")String reason);
}
