package com.tradeflow.paymentsimulator.infrastructure;
import java.util.Map;
import com.tradeflow.contracts.RefundContracts.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 退款预算锁顺序固定原支付后退款；原支付上的条件更新是通道真实退款总额的最终约束。 */
@Mapper
public interface SimulatorRefundMapper {
 Map<String,Object> find(@Param("refundNo") String refundNo);
 Map<String,Object> lock(@Param("refundNo") String refundNo);
 /** 持原支付锁后才占用预算，未支付或超过剩余本金一律不能成功。 */
 int reserve(@Param("paymentNo") String paymentNo, @Param("amountCents") long amountCents);
 int settle(@Param("paymentNo") String paymentNo, @Param("amountCents") long amountCents, @Param("succeededRefundCents") long succeededRefundCents);
 int insert(@Param("channelCommand") ChannelCommand channelCommand, @Param("status") String status, @Param("channelRefundNo") String channelRefundNo);
 int succeed(@Param("refundNo") String refundNo);
 /** 场景只绑定一次；默认写入不能覆盖管理端首次配置。 */
 int defaultScenario(@Param("refundNo") String refundNo);
 String scenarioLock(@Param("refundNo") String refundNo);
 int configure(@Param("refundNo") String refundNo, @Param("mode") String mode, @Param("actorId") String actorId, @Param("reason") String reason);
 /** 通道处理中必须持久恢复；任务列payment_no仅复用统一结构，实际绑定退款号。 */
 int process(@Param("taskNo") String taskNo, @Param("paymentNo") String paymentNo);
 /** 冻结回调正文，重复投递也不能改写通知编号和业务事实。 */
 int callback(@Param("taskNo") String taskNo, @Param("paymentNo") String paymentNo, @Param("payload") String payload, @Param("deliveriesRequired") int deliveriesRequired);
}
