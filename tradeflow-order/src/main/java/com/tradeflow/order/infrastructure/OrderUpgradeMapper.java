package com.tradeflow.order.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 交易组、运费和结算只读事实仅访问订单本库；写操作由订单短事务统一提交。 */
@Mapper
public interface OrderUpgradeMapper {
    Map<String,Object> shipping(@Param("merchantId") String merchantId);
    int ensureShipping(@Param("merchantId") String merchantId);
    /** 锁规则确保同一事务中快照金额与版本一致。 */
    Map<String,Object> lockShipping(@Param("merchantId") String merchantId);
    Map<String,Object> shippingCommand(@Param("actor") String actor,@Param("key") String key);
    int saveShippingCommand(@Param("actor") String actor,@Param("key") String key,@Param("hash") String hash,@Param("merchantId") String merchantId,@Param("fee") long fee,@Param("version") long version);
    int shippingUpdate(@Param("merchantId") String merchantId,@Param("version") long version,@Param("fee") long fee);
    int charge(@Param("orderNo") String orderNo,@Param("merchantId") String merchantId,@Param("fee") long fee,@Param("version") long version);
    Map<String,Object> chargeByOrder(@Param("orderNo") String orderNo);
    /** 幂等唯一键与组本身同事务创建，重放后必须加锁核对摘要。 */
    int groupClaim(@Param("groupNo") String groupNo,@Param("userId") String userId,@Param("key") String key,@Param("hash") String hash);
    Map<String,Object> groupByKey(@Param("userId") String userId,@Param("key") String key);
    Map<String,Object> group(@Param("groupNo") String groupNo);
    int child(@Param("groupNo") String groupNo,@Param("merchantId") String merchantId,@Param("orderNo") String orderNo);
    List<Map<String,Object>> children(@Param("groupNo") String groupNo);
    List<Map<String,Object>> groups(@Param("userId") String userId,@Param("size") int size,@Param("offset") long offset);
    /** 结算只读一致快照，活动权益与收货时间来自权威订单记录。 */
    Map<String,Object> settlement(@Param("orderNo") String orderNo);
    List<String> searchOrders(@Param("userId") String userId,@Param("status") String status,@Param("no") String no,@Param("size") int size,@Param("offset") long offset);
    long countOrders(@Param("userId") String userId,@Param("status") String status,@Param("no") String no);
    List<Map<String,Object>> timeline(@Param("orderNo") String orderNo);
}
