package com.tradeflow.payment.infrastructure;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.tradeflow.contracts.RefundContracts.*;
/** 退款关键持久化：业务锁顺序固定payment_order、refund_account、refund_source、refund_order；调用方必须持短事务。 */
@Mapper
public interface RefundMapper {
 /** 退款锁内查询唯一流水；只补缺失，不能修改已有金额或重复结算额度。 */
 int reconciliationLedgerCount(@Param("businessNo") String businessNo);
 /** 不加锁读取不可变绑定，随后按统一顺序重读业务锁。 */
 Map<String,Object> find(@Param("refundNo") String refundNo);
 Map<String,Object> lock(@Param("refundNo") String refundNo);
 Map<String,Object> account(@Param("paymentNo") String paymentNo);
 /** 支付行锁先成立后创建来源，竞争插入不改授权证据。 */
 int source(@Param("authorization") Authorization authorization, @Param("authorizationHash") String authorizationHash);
 Map<String,Object> sourceLock(@Param("sourceNo") String sourceNo);
 /** 支付本金原子占用，任何一个额度失败必须整个事务回滚。 */
 int reserveAccount(@Param("paymentNo") String paymentNo, @Param("amountCents") long amountCents);
 /** 同一来源仅一笔未决退款，成功累计不得超授权。 */
 int reserveSource(@Param("sourceNo") String sourceNo, @Param("refundNo") String refundNo, @Param("amountCents") long amountCents);
 int insert(ChannelCommand channelCommand);
 /** 可信终态转换执行占用，明确失败释放两层执行额度但不撤销订单权益。 */
 int settleAccount(@Param("paymentNo") String paymentNo, @Param("amountCents") long amountCents, @Param("succeededRefundCents") long succeededRefundCents);
 int settleSource(@Param("sourceNo") String sourceNo, @Param("refundNo") String refundNo, @Param("amountCents") long amountCents, @Param("succeededCents") long succeededCents);
 int fact(ChannelFact channelFact);
 int issue(@Param("refundNo") String refundNo, @Param("lastError") String lastError);
 int unknown(@Param("refundNo") String refundNo);
 int ledger(ChannelFact channelFact);
 /** 任务先落库，写请求之前将step置QUERY，重启不能盲目新号执行。 */
 int task(@Param("taskNo") String taskNo);
 Map<String,Object> due();
 /** 租约必须尚未到期且owner与claim同时匹配；不能让旧执行者应用网络结果。 */
 Map<String,Object> fence(@Param("taskNo") String taskNo, @Param("claimVersion") long claimVersion, @Param("leaseOwner") String leaseOwner);
 Map<String,Object> dueEvent();
 /** 退款事件同事务写入，独立聚合类型避免支付发送器误投递。 */
 int outbox(@Param("eventId") String eventId, @Param("channelFact") ChannelFact channelFact, @Param("eventType") String eventType, @Param("payload") String payload);
 int notification(@Param("notificationNo") String notificationNo, @Param("businessNo") String businessNo, @Param("payloadHash") String payloadHash, @Param("processStatus") String processStatus);
 /** 只追加退款业务审计，避免把合法额度占用误标为通道冲突。 */
 int audit(@Param("action") String action, @Param("businessNo") String businessNo, @Param("requestId") String requestId, @Param("reason") String reason);
 /** HTTP回写必须是原owner及claim且租约有效，旧网络执行者不能完成新租约。 */
 int finishEvent(@Param("eventId") String eventId, @Param("claimVersion") long claimVersion, @Param("leaseOwner") String leaseOwner, @Param("status") String status, @Param("lastError") String lastError);
}
