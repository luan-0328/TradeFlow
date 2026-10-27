package com.tradeflow.paymentsimulator.infrastructure;
import com.tradeflow.paymentsimulator.domain.SimulatorRows.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 只允许恢复器四个固定入口给出的表名；领取与网络发送分离，claim_version防旧执行者回写。 */
@Mapper
public interface SimulatorTaskMapper {
 /** SKIP LOCKED允许多个实例领取；过期租约仍可恢复，通知副作用依靠稳定通知号幂等。 */
 Task next(@Param("tableName") String tableName);
 /** 领取提交后才执行RPC；每次领取递增版本，30秒租约大于单次HTTP超时。 */
 int claim(@Param("tableName") String tableName, @Param("taskNo") String taskNo, @Param("leaseOwner") String leaseOwner);
 /** 应用结果前锁定仍有效的租约，旧执行者不得完成新执行者的任务。 */
 Task fence(@Param("tableName") String tableName, @Param("taskNo") String taskNo, @Param("leaseOwner") String leaseOwner, @Param("claimVersion") long claimVersion);
 /** 已发送ACK后计数；故障重复模式至少两次投递同通知号和相同body。 */
 int complete(@Param("tableName") String tableName, @Param("taskNo") String taskNo, @Param("leaseOwner") String leaseOwner, @Param("claimVersion") long claimVersion);
 /** 未知网络结果保留原始body并退避；达到预算进入人工，不伪造已送达。 */
 int retry(@Param("tableName") String tableName, @Param("taskNo") String taskNo, @Param("leaseOwner") String leaseOwner, @Param("claimVersion") long claimVersion, @Param("retryDelaySeconds") int retryDelaySeconds, @Param("lastError") String lastError);
}
