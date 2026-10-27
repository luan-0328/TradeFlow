package com.tradeflow.paymentsimulator.infrastructure;
import com.tradeflow.contracts.PaymentContracts.ChannelCommand;
import com.tradeflow.paymentsimulator.domain.SimulatorRows.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 所有状态变更必须在用例短事务内；唯一记录和行锁是支付关闭屏障。 */
@Mapper
public interface SimulatorMapper {
 /** 同号竞争插入只保留首次绑定，不覆盖旧金额或终态；随后必须重读行锁校验绑定。 */
 int ensure(@Param("channelCommand") ChannelCommand channelCommand, @Param("channelTransactionNo") String channelTransactionNo);
 /** pay、close和异步成功共用此锁；不存在查询不能推断为已关闭。 */
 Channel lock(@Param("paymentNo") String paymentNo);
 /** 只返回已经提交的通道事实。 */
 Channel find(@Param("paymentNo") String paymentNo);
 /** 终态写入由持锁用例判断，版本与通道更新时间同一事务递增。 */
 int transition(@Param("paymentNo") String paymentNo, @Param("status") String status);
 /** 首次pay绑定默认成功或事先配置的场景，配置不可随机变动。 */
 int defaultScenario(@Param("paymentNo") String paymentNo);
 /** 单笔场景只写一次；并发唯一冲突由管理用例返回409。 */
 int scenario(@Param("paymentNo") String paymentNo, @Param("mode") String mode, @Param("actorId") String actorId, @Param("reason") String reason);
 /** 场景先于通道锁定，管理配置只插入不更新，避免锁顺序反转。 */
 Scenario scenarioLock(@Param("paymentNo") String paymentNo);
 /** 已完成任务仍保留唯一号，不会因重复pay生成第二次异步成功。 */
 int process(@Param("taskNo") String taskNo, @Param("paymentNo") String paymentNo);
 /** 通知体在首次创建时冻结；重试和重复通知都复用原始JSON与通知号。 */
 int callback(@Param("taskNo") String taskNo, @Param("paymentNo") String paymentNo, @Param("payload") String payload, @Param("deliveriesRequired") int deliveriesRequired);
 /** 审计只追加，不允许业务账号修改历史证据。 */
 int audit(@Param("actorId") String actorId, @Param("action") String action, @Param("businessNo") String businessNo, @Param("requestId") String requestId, @Param("reason") String reason, @Param("result") String result);
}
