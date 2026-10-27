package com.tradeflow.order.infrastructure;
import com.tradeflow.contracts.RefundContracts.Authorization;
import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B4只访问订单库；权益写入先锁订单，再按商品行和购买件排序，随后锁授权。 */
@Mapper
public interface FulfillmentMapper {

 /** 授权只读快照，不替代执行状态校验。 */
 Authorization auth(@Param("sourceNo") String sourceNo);
 /** 已知绑定先读快照，再锁订单与授权，避免反向锁序。 */
 Authorization lockAuth(@Param("sourceNo") String sourceNo);
 /** 原授权首次创建与逐件预留在同一事务。 */
 int insertAuth(Authorization auth);
 /** 同一订单锁下读取履约锁的归属。 */
 String lockHolder(@Param("orderNo") String orderNo);
 /** 发货与未发货售后在同一订单条件更新竞争。 */
 int hold(@Param("orderNo") String orderNo, @Param("sourceNo") String sourceNo);
 /** 终止仅撤销自己的未执行履约锁。 */
 int unhold(@Param("orderNo") String orderNo, @Param("sourceNo") String sourceNo);
 /** 固定商品行顺序锁定后再锁购买件。 */
 List<Long> lockItems(@Param("orderNo") String orderNo);
 /** 逐件锁定真实成交金额与当前占用，不依据客户端自报数量。 */
 List<Map<String,Object>> lockUnits(@Param("orderNo") String orderNo);
 /** 全额售后每件独占，金额原子条件控制，不允许已退款或已退货件重用。 */
 int reserveUnit(@Param("id") long id, @Param("sourceNo") String sourceNo);
 /** 行汇总与购买件预留在同一短事务维护。 */
 int reserveItems(@Param("orderNo") String orderNo);
 /** 授权普通终止后释放逐件预留，执行阶段禁止调用。 */
 int releaseUnits(@Param("orderNo") String orderNo, @Param("sourceNo") String sourceNo);
 /** 按购买件重算行汇总，绝不清除其他来源的预留金额。 */
 int releaseItems(@Param("orderNo") String orderNo);
 /** 激活与普通终止竞争同一授权；执行后不能普通撤销。 */
 int transition(@Param("sourceNo") String sourceNo, @Param("fromState") String fromState, @Param("toState") String toState);
 /** 成功执行号只绑定一次，不同事件ID不能重复结算。 */
 int settling(@Param("sourceNo") String sourceNo, @Param("settledRefundNo") String settledRefundNo);
 /** 查询不可变的唯一成功执行号。 */
 String settled(@Param("sourceNo") String sourceNo);
 /** 退款成功时预留转成功权益，不因回补尚未完成重复退款。 */
 int settleUnits(@Param("orderNo") String orderNo, @Param("sourceNo") String sourceNo);
 /** 与逐件成功转换同事务重算行汇总，保留其他来源预留。 */
 int settleItems(@Param("orderNo") String orderNo);
 /** 发货只能在库存确认且没有售后锁时推进。 */
 int ship(@Param("orderNo") String orderNo);
 /** 一次整单发货，不修改原快照掩盖重复异参。 */
 int shipment(@Param("orderNo") String orderNo, @Param("carrier") String carrier, @Param("trackingNo") String trackingNo, @Param("actorId") String actorId, @Param("requestHash") String requestHash);
 /** 同号发货重放检查首次摘要。 */
 Map<String,Object> shipmentByOrder(@Param("orderNo") String orderNo);
 /** 本人确认收货只允许已发货订单，重复不再写状态日志。 */
 int receive(@Param("orderNo") String orderNo);
 /** 商家归属在SQL中过滤，应用仍必须复核最新商家身份。 */
 List<Map<String,Object>> merchantList(@Param("merchantId") String merchantId, @Param("size") int size, @Param("offset") long offset);
 /** 只扫描真实迟到资金义务，已有授权的状态由自己的任务继续恢复。 */
 List<String> lateOrders();
 /** 接收事件号防重只是第一层，授权唯一成功执行号是第二层。 */
 String factHash(@Param("eventId") String eventId);
 /** 事实证据与权益状态应用同事务，仅追加。 */
 int fact(@Param("eventId") String eventId, @Param("sourceNo") String sourceNo, @Param("refundNo") String refundNo, @Param("payloadHash") String payloadHash, @Param("status") String status);
 /** 新任务与退款授权或成功事实同事务落库，不能先RPC后记录恢复意图。 */
 int task(@Param("sourceNo") String sourceNo, @Param("step") String step);
 /** 完成回补后才终止未发货履约，原COMMIT预占不反写释放。 */
 int cancelled(@Param("orderNo") String orderNo, @Param("sourceNo") String sourceNo);
 /** 迟到支付已退款并释放确认后关闭，历史已CLOSED也幂等保留。 */
 int closeLate(@Param("orderNo") String orderNo);
 /** 任务领取短事务，不在持有任务锁时调用业务RPC。 */
 Map<String,Object> due();
 /** 递增领取版本使旧执行者不能回写。 */
 int claim(@Param("sourceNo") String sourceNo, @Param("leaseOwner") String leaseOwner);
 /** 围栏重读后才允许应用本地结果。 */
 Map<String,Object> fence(@Param("sourceNo") String sourceNo, @Param("claimVersion") long claimVersion);
 /** 阶段/状态条件更新保留人工任务，不直接释放未知退款权益。 */
 int taskResult(@Param("sourceNo") String sourceNo, @Param("claimVersion") long claimVersion, @Param("state") String state, @Param("step") String step, @Param("lastError") String lastError);
 /** 发货和收货按主体操作键绑定请求，重复键不能转用于其他订单。 */
 int claimOperation(@Param("actorType") String actorType, @Param("actorId") String actorId, @Param("operation") String operation, @Param("idempotencyKey") String idempotencyKey, @Param("requestHash") String requestHash, @Param("businessNo") String businessNo);
 /** 幂等行锁先于订单锁，保持同键并发串行。 */
 Map<String,Object> operation(@Param("actorType") String actorType, @Param("actorId") String actorId, @Param("operation") String operation, @Param("idempotencyKey") String idempotencyKey);
 /** 成功事实应用前先锁已有退款任务，保持退款任务→订单顺序。 */
 String lockRefundTask(@Param("sourceNo") String sourceNo);
 /** 新成功事实开始独立回补预算并使旧领取失效，人工等待资金结果也可恢复。 */
 int beginSettlement(@Param("sourceNo") String sourceNo);
 /** 平台只读恢复快照，不携带任何用户地址。 */
 Map<String,Object> refundTask(@Param("sourceNo") String sourceNo);
 /** 平台分页展示本库退款义务，不能直接改资金事实。 */
 List<Map<String,Object>> refundTasks(@Param("size") int size, @Param("offset") long offset);
 /** 只重启人工任务，原来源不变；新退款号仅在权威旧执行明确失败时传入。 */
 int restartRefund(@Param("sourceNo") String sourceNo, @Param("refundNo") String refundNo);
}
