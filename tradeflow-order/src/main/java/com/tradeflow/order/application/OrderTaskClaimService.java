package com.tradeflow.order.application;

import com.tradeflow.order.domain.OrderRows.Task;
import com.tradeflow.order.infrastructure.TaskMapper;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 订单创建任务的领取事务Bean；只访问订单库process_task，不执行库存或支付RPC。 */
@Service
public class OrderTaskClaimService {
    private final TaskMapper tasks;

    public OrderTaskClaimService(TaskMapper tasks) {
        this.tasks = tasks;
    }

    /**
     * 从process_task领取一个到期READY任务或过期RUNNING任务。
     * Worker通过注入的Spring代理调用本public方法；没有外层事务时，在方法返回给Worker前完成提交。
     * next锁住候选行，claim登记执行者和30秒租约并递增领取版本，byNo读回新快照。
     * 超过本轮恢复预算时同事务转人工；异常交给代理回滚，再由Worker在事务外有限重试。
     * 默认REQUIRED：已有外层事务时会参与，故调用方不能给整个scan加事务或把RPC塞进本方法。
     */
    @Transactional(rollbackFor = Exception.class)
    public Task claimNext(String owner) {
        // 查询订单库process_task，用FOR UPDATE SKIP LOCKED只锁一个当前可处理的候选。
        Task candidate = tasks.next();
        if (candidate == null) {
            return null;
        }

        // Task.createdAt映射本轮恢复起点：最多10次或30分钟，超限保留原任务转人工。
        if (candidate.attemptCount() >= 10
                || candidate.createdAt().plusSeconds(1800).isBefore(Instant.now())) {
            tasks.manual(candidate.taskNo(), "RECOVERY_BUDGET_EXHAUSTED");
            return null;
        }

        // UPDATE process_task；此调用本身不提交，方法正常返回后才由Spring事务代理提交。
        tasks.claim(candidate.taskNo(), owner);
        // 按task_no查询刚更新的process_task，返回本次owner、claimVersion和leaseUntil。
        return tasks.byNo(candidate.taskNo());
    }
}
