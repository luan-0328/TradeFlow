package com.tradeflow.payment.infrastructure;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.tradeflow.contracts.PaymentContracts.*;

/** 支付持久化边界；所有资金修改依赖唯一键与短事务，锁顺序为恢复任务、支付意图、附属记录。 */
@Mapper
public interface PaymentMapper {
    /** 当前主体+固定支付操作+原键+订单号同语句关联读取，普通一致读不加锁，也不创建任务或触碰通道。 */
    Map<String,Object> acceptedRequest(@Param("userId") String userId, @Param("idempotencyKey") String idempotencyKey, @Param("orderNo") String orderNo);
    /** 对账在支付锁内检查唯一成功流水，缺失才由受控命令补记，不能覆盖已有流水。 */
    int reconciliationLedgerCount(@Param("businessNo") String businessNo);
    /** 只读支付快照，不能据此越过人工核验或关闭屏障。 */
    Map<String,Object> find(@Param("paymentNo") String paymentNo);
    /** 事务内锁唯一支付意图，串行应用事实与关闭决策。 */
    Map<String,Object> lock(@Param("paymentNo") String paymentNo);
    /** 并发pay/close在唯一意图上串行；重复插入不修改原金额归属。 */
    int ensure(Intent intent);
    /** 持锁保存单向关闭意图，通道关闭由事务外任务完成。 */
    int closing(@Param("paymentNo") String paymentNo);
    /** 仅由统一应用器持锁保存已校验通道事实。 */
    int fact(ChannelFact fact);
    /** 冻结自动处理并保留原终态供人工审计。 */
    int issue(@Param("paymentNo") String paymentNo, @Param("lastError") String lastError);
    /** 网络未知仅在无通道事实时置UNKNOWN，已有版本不得降级。 */
    int unknown(@Param("paymentNo") String paymentNo);
    /** 成功流水与支付事实同事务，唯一业务号防重复记账。 */
    int ledger(ChannelFact fact);
    /** 首次成功同事务建立可退款本金，退款用例持锁维护双额度。 */
    int refundAccount(ChannelFact fact);
    /** 客户端键绑定用户及请求摘要；同键异参由事务内读取拒绝。 */
    int idempotency(@Param("actorId") String actorId, @Param("idempotencyKey") String idempotencyKey, @Param("requestHash") String requestHash, @Param("businessNo") String businessNo);
    /** 事务内持锁核对用户幂等键，不允许同键异订单。 */
    Map<String,Object> idem(@Param("actorId") String actorId, @Param("idempotencyKey") String idempotencyKey);
    /** 创建稳定恢复任务，首次提交前即落库；后续只恢复原编号。 */
    int ensureTask(@Param("taskNo") String taskNo);
    /** 先于支付意图锁任务，保持统一锁顺序。 */
    Map<String,Object> lockTask(@Param("taskNo") String taskNo);
    /** close抢占旧租约，旧执行者不能再覆盖恢复步骤；通道自身屏障兜住已在途RPC。 */
    int closeTask(@Param("taskNo") String taskNo);
    /** 恢复器持租约切入关闭查询；条件失败必须回滚同事务关闭意图，不能以过期任务转换状态。 */
    int closeOwnedTask(@Param("taskNo") String taskNo, @Param("claimVersion") long claimVersion, @Param("leaseOwner") String leaseOwner);
    /** 写请求前持久QUERY步骤，崩溃恢复先查原业务号。 */
    int beforeSubmit(@Param("taskNo") String taskNo);
    /** 短事务跳过竞争锁，允许接管到期租约。 */
    String dueTask();
    /** 领取递增版本围栏，远程副作用仍依赖业务号幂等。 */
    int claimTask(@Param("taskNo") String taskNo, @Param("leaseOwner") String leaseOwner);
    /** 网络返回后锁定仍有效的原 owner 和领取版本；到期但尚未被接管也不能写入业务。 */
    Map<String,Object> fenceTask(@Param("taskNo") String taskNo, @Param("claimVersion") long claimVersion, @Param("leaseOwner") String leaseOwner);
    /** 条件完成原有效租约；调用方必须检查一行，否则回滚同事务的资金变化。 */
    int finishTask(@Param("taskNo") String taskNo, @Param("claimVersion") long claimVersion, @Param("leaseOwner") String leaseOwner, @Param("state") String state, @Param("step") String step, @Param("lastError") String lastError);
    /** 验真后查询已绑定通知摘要，拒绝同号异参。 */
    Map<String,Object> notification(@Param("notificationNo") String notificationNo);
    /** 与事实应用同事务保存已验真通知，非法签名不占号。 */
    int notificationInsert(@Param("notificationNo") String notificationNo, @Param("businessNo") String businessNo, @Param("payloadHash") String payloadHash);
    /** 已验真但事实矛盾时隔离，不伪造成功应用。 */
    int quarantine(@Param("notificationNo") String notificationNo);
    /** 同事务生成唯一终态事件；B3仅HTTP可靠交付，尚未接入MQ发布。 */
    int outbox(@Param("eventId") String eventId, @Param("aggregateId") String aggregateId, @Param("aggregateVersion") long aggregateVersion, @Param("eventType") String eventType, @Param("orderNo") String orderNo, @Param("payload") String payload);
    /** 领取持久事件，冻结正文以原事件号重放。 */
    Map<String,Object> dueEvent();
    /** 短事务递增发送围栏，HTTP在事务外完成。 */
    int claimEvent(@Param("eventId") String eventId, @Param("leaseOwner") String leaseOwner);
    /** 原领取版本才能标记ACK、重试或人工隔离。 */
    int finishEvent(@Param("eventId") String eventId, @Param("claimVersion") long claimVersion, @Param("status") String status, @Param("lastError") String lastError);
    /** 矛盾处置只追加审计，与冻结决策同事务。 */
    int audit(@Param("businessNo") String businessNo, @Param("requestId") String requestId, @Param("reason") String reason);
    /** 平台只读分页真实持久资金流水。 */
    List<Map<String,Object>> ledgerPage(@Param("size") int size, @Param("offset") long offset);
    /** 只统计实际资金证据，不生成演示数量。 */
    long ledgerCount();
}
