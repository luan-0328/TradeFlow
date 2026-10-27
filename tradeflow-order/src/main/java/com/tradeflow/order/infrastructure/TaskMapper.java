package com.tradeflow.order.infrastructure;

import com.tradeflow.order.domain.OrderRows.Task;
import com.tradeflow.order.api.OrderModels.TaskView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 任务租约只协调执行，不宣称网络副作用恰好一次；业务编号仍是最终幂等边界。 */
@Mapper
public interface TaskMapper {

    /**
     * 查询订单库process_task：只选CREATE_ORDER类型，READY且到重试时间，或RUNNING但租约已过期的任务。
     * 按主键顺序取1条，用FOR UPDATE SKIP LOCKED锁住候选行并跳过其他执行者已锁行。
     * 必须在领取短事务中调用；返回候选只代表已锁定，尚未写入本次执行者及新租约。
     */
    Task next();
    /**
     * 更新next已锁住的process_task行：state=RUNNING、lease_owner=本JVM执行者、租约截止=数据库UTC+30秒。
     * 同时递增attempt_count、claim_version和version；这里只执行UPDATE，提交由调用方事务边界负责。
     * 当前Worker使用独立OrderTaskClaimService的注解事务，不能在Mapper调用之间自行提交。
     * 新claim_version区分每次领取；即使旧线程后来恢复运行，也不能使用旧版本提交业务结果。
     */
    int claim(@Param("taskNo") String taskNo, @Param("leaseOwner") String leaseOwner);
    /** 查询并锁process_task原任务；要求RUNNING、执行者和领取版本匹配且租约仍有效，失效返回null。用于提交业务结果前的围栏复核。 */
    Task fence(@Param("taskNo") String taskNo, @Param("leaseOwner") String leaseOwner, @Param("claimVersion") long claimVersion);
    /** 按task_no读取process_task的当前快照，不额外加锁；领取事务中用它读回刚更新的执行者、领取版本及租约，不能替代fence写前复核。 */
    Task byNo(@Param("taskNo") String taskNo);
    /** 供本人创建进度读取，不向本人展示操作者凭据或敏感错误原文。 */
    Task byOrder(@Param("businessNo") String businessNo);
    /** 业务状态和任务完成在同一事务，旧版本不能完成新执行者的任务。 */
    int finish(@Param("taskNo") String taskNo, @Param("leaseOwner") String leaseOwner, @Param("claimVersion") long claimVersion);
    /** 中间进展或不确定结果持久退避重试，继续复用原编号；只记录稳定错误码。 */
    int retry(@Param("taskNo") String taskNo, @Param("leaseOwner") String leaseOwner, @Param("claimVersion") long claimVersion, @Param("retryDelaySeconds") int retryDelaySeconds, @Param("lastError") String lastError);
    /** 超限或事实矛盾保留任务和业务占用，进入人工，不能按失败直接释放。 */
    int manual(@Param("taskNo") String taskNo, @Param("lastError") String lastError);
    /** 平台受控重启先锁任务，随后递增栅栏并重置本轮预算，原业务号不变。 */
    Task lock(@Param("taskNo") String taskNo);
    /** 只允许重启人工任务，不能重置已成功任务触发第二次业务执行。 */
    int restart(@Param("taskNo") String taskNo);
    /** 订单阶段变更前先锁原流程任务，统一task→intent→order顺序。 */
    Task lockByOrder(@Param("businessNo") String businessNo);
    /** 成功创建后的支付/关闭后续阶段受控激活原流程任务，栅栏递增使旧执行者失效。 */
    int activate(@Param("taskNo") String taskNo);
    /** 平台分页查询任务，不包含含地址的创建意图材料。 */
    List<TaskView> list(@Param("size") int size, @Param("offset") long offset);
    /** 仅统计本批任务类型，避免未来其他任务权限混入。 */
    long count();
}
