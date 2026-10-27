package com.tradeflow.payment.infrastructure;

import com.tradeflow.payment.application.reconciliation.ReconciliationFacts.LocalRecord;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 对账本地事实检查，仅访问支付库；支付锁保证退款额度、来源与逐笔汇总来自同一稳定状态。 */
@Repository
public class ReconciliationLocalStore {
 private final JdbcTemplate jdbc;private final PaymentMapper payments;private final RefundMapper refunds;private final TransactionTemplate tx;
 public ReconciliationLocalStore(JdbcTemplate jdbc,PaymentMapper payments,RefundMapper refunds,TransactionTemplate tx){this.jdbc=jdbc;this.payments=payments;this.refunds=refunds;this.tx=tx;}

 /** 先普通读取不可变退款绑定，再按支付、额度、来源、退款加锁；不能先锁退款再等待原支付。 */
 public LocalRecord inspect(String type,String no){validate(type,no);return tx.execute(s->{
  boolean refund="REFUND".equals(type);var initial=refund?refunds.find(no):payments.find(no);if(initial==null)return null;
  String paymentNo=refund?text(initial,"payment_no"):no;var payment=payments.lock(paymentNo);if(payment==null)return record(type,no,initial,true,0,0,"PAYMENT_BINDING_MISSING");
  var account=refunds.account(paymentNo);if(refund)refunds.sourceLock(text(initial,"source_no"));var row=refund?refunds.lock(no):payment;if(row==null)return null;
  String integrity=quotaIntegrity(payment,account);
  if(refund&&(!Objects.equals(text(payment,"order_no"),text(row,"order_no"))||!Objects.equals(text(payment,"currency"),text(row,"currency"))))integrity="REFUND_PARENT_BINDING_CONFLICT";
  var ledger=jdbc.queryForList("SELECT business_no,payment_no,refund_no,entry_type,direction,amount_cents,currency,occurred_at FROM payment_ledger WHERE business_no=?",no);
  long amount=0;for(var entry:ledger)amount=Math.addExact(amount,number(entry,"amount_cents"));
  if(ledger.size()>1)integrity="LEDGER_DUPLICATE";
  for(var entry:ledger){boolean valid=Objects.equals(no,text(entry,"business_no"))&&Objects.equals(paymentNo,text(entry,"payment_no"))&&Objects.equals(refund?no:null,text(entry,"refund_no"))&&Objects.equals(refund?"REFUND_SUCCEEDED":"PAYMENT_SUCCEEDED",text(entry,"entry_type"))&&Objects.equals(refund?"OUT":"IN",text(entry,"direction"))&&number(entry,"amount_cents")==number(row,"amount_cents")&&Objects.equals(text(row,"currency"),text(entry,"currency"))&&Objects.equals(time(row,"succeeded_at"),time(entry,"occurred_at"))&&"SUCCEEDED".equals(text(row,"status"));if(!valid)integrity="LEDGER_BINDING_CONFLICT";}
  boolean review="YES".equals(text(payment,"review_required"))||"YES".equals(text(row,"review_required"));return record(type,no,row,review,ledger.size(),amount,integrity);
 });}

 /** 范围内通道变更双向扫描，同时覆盖截止前所有未决记录，旧未决不会因三天窗口滚动丢失。 */
 public List<LocalRecord> page(Instant start,Instant end,Instant cutoff,String after,int size){if(size<1||size>100)throw new IllegalArgumentException("分页无效");String cursor=after==null?"":after;var rows=jdbc.queryForList("SELECT transaction_type,business_no,cursor_key FROM (SELECT 'PAYMENT' transaction_type,payment_no business_no,CONCAT('PAYMENT:',payment_no) cursor_key FROM payment_order WHERE created_at<=? AND ((COALESCE(channel_updated_at,created_at)>=? AND COALESCE(channel_updated_at,created_at)<?) OR status IN ('PENDING','PROCESSING','UNKNOWN')) UNION ALL SELECT 'REFUND' transaction_type,refund_no business_no,CONCAT('REFUND:',refund_no) cursor_key FROM refund_order WHERE created_at<=? AND ((COALESCE(channel_updated_at,created_at)>=? AND COALESCE(channel_updated_at,created_at)<?) OR status IN ('PENDING','PROCESSING','UNKNOWN'))) candidates WHERE cursor_key>? ORDER BY cursor_key LIMIT ?",Timestamp.from(cutoff),Timestamp.from(start),Timestamp.from(end),Timestamp.from(cutoff),Timestamp.from(start),Timestamp.from(end),cursor,size);var result=new ArrayList<LocalRecord>();for(var row:rows){var record=inspect(text(row,"transaction_type"),text(row,"business_no"));if(record!=null)result.add(record);}return List.copyOf(result);}

 /** 累计本金与来源汇总只核验不重算覆盖；不变量异常交给上层冻结及人工审计。 */
 private String quotaIntegrity(Map<String,Object> paid,Map<String,Object> account){String no=text(paid,"payment_no");var sums=jdbc.queryForMap("SELECT COUNT(*) total_count,COALESCE(SUM(IF(status='SUCCEEDED',amount_cents,0)),0) succeeded,COALESCE(SUM(IF(status IN ('PENDING','PROCESSING','UNKNOWN'),amount_cents,0)),0) reserved FROM refund_order WHERE payment_no=?",no);if(!"SUCCEEDED".equals(text(paid,"status")))return account!=null||number(sums,"total_count")>0?"NON_SUCCESS_PAYMENT_HAS_REFUND_ACCOUNT":null;
  if(account==null)return "REFUND_ACCOUNT_MISSING";
  if(number(account,"paid_amount_cents")!=number(paid,"amount_cents")||number(account,"succeeded_refund_cents")!=number(sums,"succeeded")||number(account,"reserved_refund_cents")!=number(sums,"reserved")||Math.addExact(number(sums,"succeeded"),number(sums,"reserved"))>number(paid,"amount_cents"))return "REFUND_ACCOUNT_INTEGRITY";
  var sources=jdbc.queryForList("SELECT s.source_no,s.order_no,s.authorized_cents,s.reserved_cents,s.succeeded_cents,s.active_refund_no,COALESCE(SUM(IF(r.status='SUCCEEDED',r.amount_cents,0)),0) actual_success,COALESCE(SUM(IF(r.status IN ('PENDING','PROCESSING','UNKNOWN'),r.amount_cents,0)),0) actual_reserved,COALESCE(SUM(IF(r.status IN ('PENDING','PROCESSING','UNKNOWN'),1,0)),0) active_count,MAX(IF(r.status IN ('PENDING','PROCESSING','UNKNOWN'),r.refund_no,NULL)) actual_active,COALESCE(SUM(IF(r.refund_no IS NOT NULL AND (r.payment_no<>s.payment_no OR r.order_no<>s.order_no OR r.currency<>'CNY'),1,0)),0) invalid_count FROM refund_source s LEFT JOIN refund_order r ON r.source_no=s.source_no WHERE s.payment_no=? GROUP BY s.source_no,s.order_no,s.authorized_cents,s.reserved_cents,s.succeeded_cents,s.active_refund_no",no);
  long sourceSuccess=0,sourceReserved=0;for(var source:sources){if(!Objects.equals(text(paid,"order_no"),text(source,"order_no"))||number(source,"invalid_count")!=0||number(source,"active_count")>1||!Objects.equals(text(source,"active_refund_no"),text(source,"actual_active"))||number(source,"succeeded_cents")!=number(source,"actual_success")||number(source,"reserved_cents")!=number(source,"actual_reserved")||Math.addExact(number(source,"actual_success"),number(source,"actual_reserved"))>number(source,"authorized_cents"))return "REFUND_SOURCE_INTEGRITY";sourceSuccess=Math.addExact(sourceSuccess,number(source,"actual_success"));sourceReserved=Math.addExact(sourceReserved,number(source,"actual_reserved"));}return sourceSuccess!=number(sums,"succeeded")||sourceReserved!=number(sums,"reserved")?"REFUND_SOURCE_AGGREGATE_CONFLICT":null;
 }

 /** 数据库实体转为固定对账读模型，禁止将地址、凭据等无关内容放入证据。 */
 private LocalRecord record(String type,String no,Map<String,Object> row,boolean review,int ledgerCount,long ledgerAmount,String integrity){boolean refund="REFUND".equals(type);return new LocalRecord(type,no,refund?text(row,"payment_no"):no,text(row,"order_no"),refund?text(row,"source_no"):null,number(row,"amount_cents"),text(row,"currency"),text(row,"status"),text(row,refund?"channel_refund_no":"channel_transaction_no"),number(row,"channel_version"),time(row,"channel_updated_at"),time(row,"succeeded_at"),review,ledgerCount,ledgerAmount,integrity);}
 /** 只接受固定类型和原业务号，动态SQL中不插入用户表名或排序字段。 */
 public static void validate(String type,String no){if(!Set.of("PAYMENT","REFUND").contains(type==null?"":type))throw new com.tradeflow.common.BusinessException(400,"INVALID_TRANSACTION_TYPE","交易类型无效");PaymentRemote.safe(no);}
 private static String text(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:v.toString();}
 private static long number(Map<String,Object> row,String key){Object v=row.get(key);if(v instanceof java.math.BigDecimal d)return d.longValueExact();if(v instanceof java.math.BigInteger i)return i.longValueExact();return ((Number)v).longValue();}
 private static Instant time(Map<String,Object> row,String key){Object v=row.get(key);if(v==null)return null;if(v instanceof Timestamp t)return t.toInstant();if(v instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);if(v instanceof Instant t)return t;throw new IllegalStateException("未知数据库时间类型");}
}
