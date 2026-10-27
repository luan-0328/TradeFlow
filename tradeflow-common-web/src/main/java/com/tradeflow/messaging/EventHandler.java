package com.tradeflow.messaging;

import com.tradeflow.contracts.MessagingContracts.Event;
import java.util.Set;

/** 消费用例在基础设施外层本地事务内执行，禁止 RPC；异常使业务整体回滚后再持久交接。 */
public interface EventHandler {
    String consumer();
    Set<String> eventTypes();
    void handle(Event event);
}
