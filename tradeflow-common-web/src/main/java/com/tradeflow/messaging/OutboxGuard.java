package com.tradeflow.messaging;

/** 发布前本库核验冻结/绑定状态；只读本地数据，不跨库或调用网络，不跨发布持有行锁。 */
public interface OutboxGuard {
    void check(String aggregateType, String aggregateId);
}
