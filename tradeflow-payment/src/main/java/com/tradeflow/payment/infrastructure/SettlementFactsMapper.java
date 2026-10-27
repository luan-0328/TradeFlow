package com.tradeflow.payment.infrastructure;
import java.util.*;
import org.apache.ibatis.annotations.*;
/** 仅投影不可变成功资金账，不跨库查询商家，也不返回通道凭据和用户信息。 */
@Mapper public interface SettlementFactsMapper {
 /** 账本与原支付绑定提供订单号；金额以账本为准，调用方必须重复扫描修复提交乱序。 */
 List<Map<String,Object>> page(@Param("after") long after,@Param("size") int size);
}
