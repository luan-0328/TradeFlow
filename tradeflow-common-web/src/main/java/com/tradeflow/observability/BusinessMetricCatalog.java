package com.tradeflow.observability;

import java.util.*;

/** 只读指标SQL白名单；kind均为固定业务类别，不从请求、业务号或数据库内容生成标签。 */
final class BusinessMetricCatalog {
 record Query(String kind,String sql){}
 private BusinessMetricCatalog(){}

 /** 每个服务只查询所属逻辑库已实现的表；人工待办与自动恢复分开，避免把人工等待算成自动逾期。 */
 static List<Query> forService(String code){var queries=new ArrayList<Query>();
  if(Set.of("order","payment","aftersale").contains(code)){
   task(queries,"outbox","outbox_event","status","next_retry_at");task(queries,"deferred","deferred_event","status","next_retry_at");
   queries.add(query("message_failure","message_failure","disposition<>'RESOLVED'",null));
   queries.add(query("message_failure_manual","message_failure","status='MANUAL_REVIEW'",null));
  }
  switch(code){
   case "order"->{task(queries,"order_recovery","process_task","state","next_retry_at");task(queries,"refund_flow","refund_flow_task","state","next_retry_at");task(queries,"return_stock","return_stock_task","state","next_retry_at");queries.add(query("inventory_pending","orders","stock_sync_status='PENDING' AND stock_decision IN ('COMMIT','RELEASE')",null));}
   case "payment"->{task(queries,"payment_recovery","process_task","state","next_retry_at");task(queries,"reconciliation_run","reconciliation_run","state","next_at");task(queries,"reconciliation_action","reconciliation_action","state","next_at");queries.add(query("payment_unknown","payment_order","status='UNKNOWN'",null));queries.add(query("refund_unknown","refund_order","status='UNKNOWN'",null));queries.add(query("refund_reserved","refund_order","status IN ('PENDING','PROCESSING','UNKNOWN')",null));queries.add(query("payment_frozen","payment_order","review_required='YES'",null));queries.add(query("refund_frozen","refund_order","review_required='YES'",null));queries.add(query("reconciliation_issue","reconciliation_issue","state<>'RESOLVED'",null));queries.add(query("reconciliation_conflict","reconciliation_issue","state<>'RESOLVED' AND issue_type LIKE '%CONFLICT'",null));}
   case "aftersale"->{task(queries,"aftersale_recovery","aftersale_task","status","next_run_at");}
   case "product"->{queries.add(query("reservation_pending","reservation_header","status='RESERVED'","expires_at"));}
   case "payment-simulator"->{task(queries,"channel_process","process_task","state","next_retry_at");task(queries,"channel_callback","callback_task","state","next_retry_at");task(queries,"refund_process","refund_process_task","state","next_retry_at");task(queries,"refund_callback","refund_callback_task","state","next_retry_at");}
   default->{/* 用户模块没有资金恢复表，只暴露平台标准指标及实例锁等待。 */}
  }
  return List.copyOf(queries);
 }
 /** 自动恢复只统计READY/RUNNING；到期时间分别取计划重试点或当前租约截止，不能从领取次数猜逾期。 */
 private static void task(List<Query> result,String kind,String table,String state,String retry){result.add(query(kind,table,state+" IN ('READY','RUNNING')","CASE WHEN "+state+"='RUNNING' THEN lease_until ELSE "+retry+" END"));result.add(query(kind+"_manual",table,state+"='MANUAL_REVIEW'",null));}
 /** COUNT和最老创建时长来自同一聚合语句；空集合零，数据库异常由采样器转NaN而不是伪造空集合。 */
 private static Query query(String kind,String table,String where,String due){String lag=due==null?"0":"COALESCE(MAX(GREATEST(TIMESTAMPDIFF(MICROSECOND,"+due+",UTC_TIMESTAMP(6))/1000000.0,0)),0)";return new Query(kind,"SELECT COUNT(*) AS backlog,COALESCE(MAX(GREATEST(TIMESTAMPDIFF(MICROSECOND,created_at,UTC_TIMESTAMP(6))/1000000.0,0)),0) AS age,"+lag+" AS due_lag FROM "+table+" WHERE "+where);}
}
