package com.tradeflow.observability;

import java.util.*;
import org.slf4j.*;
import org.springframework.transaction.support.*;

/** 事务提交后记录有限业务上下文；数据库审计仍为权威记录，日志失败不参与资金决策。 */
public final class BusinessLog {
 private static final Logger LOG=LoggerFactory.getLogger("com.tradeflow.business");
 private static final Set<String> KEYS=Set.of("orderNo","paymentNo","refundNo","aftersaleNo","taskId","eventId","traceId","requestId","state","eventType");
 private BusinessLog(){}
 /** 异步日志以原事件trace与业务号关联，不输出事件正文；重复投递只在真实本地应用提交后记录。 */
 public static void messageCommitted(String name,com.tradeflow.contracts.MessagingContracts.Event event){Map<String,String> fields=new HashMap<>();fields.put("eventId",event.eventId());fields.put("traceId",event.traceId());fields.put("orderNo",event.correlationId());fields.put("eventType",event.eventType());String key=switch(event.aggregateType()){case "PAYMENT"->"paymentNo";case "REFUND"->"refundNo";case "ORDER"->"orderNo";case "AFTERSALE"->"aftersaleNo";default->null;};if(key!=null)fields.put(key,event.aggregateId());afterCommit(name,fields);}
 /** 只接收白名单字段和安全短标识，不打印body/地址/原因或token；回滚事务不输出已提交状态。 */
 public static void afterCommit(String event,Map<String,String> fields){if(event==null||!event.matches("[A-Z0-9_]{1,64}"))throw new IllegalArgumentException("业务日志事件类型无效");Map<String,String> context=new HashMap<>();var current=MDC.getCopyOfContextMap();if(current!=null)for(var entry:current.entrySet())if(KEYS.contains(entry.getKey())&&safe(entry.getValue()))context.put(entry.getKey(),entry.getValue());fields.forEach((key,value)->{if(KEYS.contains(key)&&safe(value))context.put(key,value);});Runnable write=()->{Map<String,String> before=MDC.getCopyOfContextMap();try{MDC.setContextMap(context);LOG.info("event={}",event);}catch(RuntimeException ignored){/* 观测日志不可把已提交业务转换为失败响应。 */}finally{if(before==null)MDC.clear();else MDC.setContextMap(before);}};if(TransactionSynchronizationManager.isSynchronizationActive()&&TransactionSynchronizationManager.isActualTransactionActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCommit(){write.run();}});else write.run();}
 private static boolean safe(String value){return value!=null&&value.matches("[A-Za-z0-9_.:-]{1,128}");}
}
