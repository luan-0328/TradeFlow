package com.tradeflow.settlement;
import java.util.*;
import org.apache.ibatis.annotations.*;
/** 锁顺序固定任务围栏、商家账户、支付结算单；所有记账同本地短事务，复杂SQL集中XML。 */
@Mapper public interface SettlementMapper {
 /** 初始化稳定扫描任务，重复调用不重置游标或租约。 */
 int ensureTask();
 /** 平台查询任务恢复进度，不输出服务凭据。 */
 List<Map<String,Object>> tasks(); Map<String,Object> lockTask(); int claim(@Param("owner")String owner); Map<String,Object> fence(@Param("owner")String owner,@Param("version")long version);
 int finish(@Param("owner")String owner,@Param("version")long version,@Param("cursor")long cursor,@Param("error")String error);
 int candidateCursor(@Param("cursor")long cursor);
 int account(@Param("merchant")String merchant); Map<String,Object> lockAccount(@Param("merchant")String merchant);
 Map<String,Object> item(@Param("payment")String payment); Map<String,Object> lockItem(@Param("payment")String payment);
 Map<String,Object> fact(@Param("kind")String kind,@Param("business")String business);
 int insertFact(@Param("f")SettlementRemote.Fact f,@Param("merchant")String merchant);
 int insertItem(@Param("f")SettlementRemote.Fact f,@Param("merchant")String merchant,@Param("bps")int bps,@Param("days")int days,@Param("fee")long fee);
 int refund(@Param("payment")String payment,@Param("amount")long amount,@Param("fee")long fee);
 int debt(@Param("merchant")String merchant,@Param("delta")long delta);
 int ledger(@Param("key")String key,@Param("payment")String payment,@Param("merchant")String merchant,@Param("type")String type,@Param("principal")long principal,@Param("fee")long fee,@Param("payable")long payable,@Param("simulated")long simulated,@Param("batch")String batch);
 List<Map<String,Object>> candidates(@Param("after")long after); int settle(@Param("payment")String payment,@Param("batch")String batch);
 int batch(@Param("batch")String batch,@Param("merchant")String merchant,@Param("payment")String payment,@Param("net")long net,@Param("payout")long payout,@Param("offset")long offset);
 /** 商家账单联查本库单据和模拟批次；退款后保留原批次并展示当前净权利。 */
 List<Map<String,Object>> statements(@Param("merchant")String merchant,@Param("size")int size,@Param("offset")long offset);
 List<Map<String,Object>> page(@Param("table")String table,@Param("merchant")String merchant,@Param("size")int size,@Param("offset")long offset);
 Map<String,Object> summary(@Param("merchant")String merchant); List<Map<String,Object>> differences(); int reconciliation(@Param("run")String run,@Param("result")String result,@Param("detail")String detail);
 int audit(@Param("actor")String actor,@Param("action")String action);
}
