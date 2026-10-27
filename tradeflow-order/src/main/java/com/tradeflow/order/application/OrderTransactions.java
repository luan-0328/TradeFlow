package com.tradeflow.order.application;

import com.tradeflow.common.BusinessException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单库编程式事务工具，作为Spring Bean由Worker和业务Service通过构造器注入。
 * 调用方变量通常叫tx，tx.write(() -> {...})表示把括号内的本库操作交给Spring TransactionTemplate执行。
 * Lambda在当前线程同步执行，不是线程池任务；正常返回时按事务状态提交，异常时由模板回滚。
 * 不允许把库存、支付等RPC放入回调，否则既延长持锁时间，又可能在本地重试时重复远程副作用。
 */
@Component
public class OrderTransactions {
    private final TransactionTemplate write;private final TransactionTemplate read;
    /** 使用Spring提供的事务管理器建立读写模板；默认REQUIRED，已有外层事务时参与，不强制新建事务。 */
    public OrderTransactions(PlatformTransactionManager manager) {
        write=new TransactionTemplate(manager);read=new TransactionTemplate(manager);read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    /**
     * 在事务内同步调用action.get()，返回值T就是调用方拿到的结果，例如领取后的Task。
     * Worker.scan没有外层事务时，每次模板执行建立自己的短事务，write返回前提交或回滚已结束。
     * 本库瞬时竞争异常最多执行3次并短暂退避；重试必须沿用原业务号，只允许可幂等重放的本库操作。
     * 默认REQUIRED不会隔离已有外层事务，不能把参与外层事务的重试理解为每次都重新开启独立事务。
     */
    public <T> T write(Supplier<T> action) {
        for(int attempt=0;attempt<3;attempt++)try { return write.execute(s->action.get()); }
        catch(org.springframework.dao.TransientDataAccessException e) {
            if(attempt==2)throw new BusinessException(503,"DATABASE_BUSY","订单数据库繁忙，请按原编号查询或重试");
            try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(20,81)); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new BusinessException(503,"REQUEST_INTERRUPTED","请求中断，请查询原编号"); }
        }
        throw new IllegalStateException("不可达分支");
    }
    /** 聚合只读快照，防止详情多条查询在并发状态变化中拼出自相矛盾视图。 */
    public <T> T read(Supplier<T> action) { return read.execute(s->action.get()); }
}
