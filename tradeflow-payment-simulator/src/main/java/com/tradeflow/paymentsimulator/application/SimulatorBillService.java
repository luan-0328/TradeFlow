package com.tradeflow.paymentsimulator.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.PaymentContracts;
import com.tradeflow.contracts.RefundContracts;
import com.tradeflow.contracts.ReconciliationContracts;
import com.tradeflow.contracts.ReconciliationContracts.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** 冻结通道本库的一致可见终态；账单只提供核对证据，不阻止将来的迟到交易。 */
@Service
public class SimulatorBillService {
 private final JdbcTemplate db;private final ObjectMapper json;
 public SimulatorBillService(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 private record Header(String snapshotNo,String requestJson,String manifestJson,String mode){}
 /** 同号插入先获得唯一键串行屏障；RR中全部普通源SELECT共享快照，账头与全部明细原子提交。不得改为INSERT SELECT或锁源表当前读。 */
 @Transactional(isolation=Isolation.REPEATABLE_READ)
 public BillManifest freeze(BillRequest request){
  validate(request);
  String requestJson=encode(request),snapshot="BS"+UUID.randomUUID().toString().replace("-","");
  db.update("INSERT INTO bill_projection_scenario(request_no,mode,actor_id,reason) VALUES(?,'NONE','SYSTEM','冻结时绑定正常投影') ON DUPLICATE KEY UPDATE request_no=request_no",request.requestNo());
  String mode=db.queryForObject("SELECT mode FROM bill_projection_scenario WHERE request_no=? FOR UPDATE",String.class,request.requestNo());
  db.update("INSERT INTO bill_snapshot(snapshot_no,request_no,request_json,projection_mode) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE request_no=request_no",snapshot,request.requestNo(),requestJson,mode);
  Header header=db.queryForObject("SELECT snapshot_no,request_json,manifest_json,projection_mode FROM bill_snapshot WHERE request_no=? FOR UPDATE",(r,n)->new Header(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),request.requestNo());
  if(!decode(header.requestJson(),BillRequest.class).equals(request))throw error(409,"BILL_BINDING_CONFLICT","原请求号已绑定其他账期或截止点");
  if(header.manifestJson()!=null)return decode(header.manifestJson(),BillManifest.class);
  Instant watermark=db.queryForObject("SELECT UTC_TIMESTAMP(3)",(r,n)->instant(r,1));
  if(request.cutoff().isAfter(watermark))throw error(400,"BILL_CUTOFF_INVALID","截止点不得晚于数据库当前UTC时间");
  // 固定五百行keyset批次读取；全部普通SELECT仍在当前RR事务的一致视图内，不随分页切换快照。
  FreezeTotals totals=new FreezeTotals();String cursor=null;
  while(true){
   String seek=cursor==null?"":" AND payment_no>?";
   Object[] args=cursor==null?new Object[]{Timestamp.from(request.start()),Timestamp.from(request.end())}:new Object[]{Timestamp.from(request.start()),Timestamp.from(request.end()),cursor};
   List<PaymentContracts.ChannelFact> payments=db.query("SELECT payment_no,order_no,amount_cents,currency,status,channel_transaction_no,channel_version,updated_at,succeeded_at FROM channel_transaction WHERE currency='CNY' AND status IN ('SUCCEEDED','FAILED','CLOSED') AND updated_at>=? AND updated_at<?"+seek+" ORDER BY payment_no LIMIT 500",(r,n)->new PaymentContracts.ChannelFact(r.getString(1),r.getString(2),r.getLong(3),r.getString(4),r.getString(5),r.getString(6),r.getLong(7),instant(r,8),instant(r,9)),args);
   if(payments.isEmpty())break;
   List<BillLine> lines=new ArrayList<>(payments.size());
   for(var fact:payments){
    BillLine line=new BillLine(totals.lineCount+1,"PAYMENT",fact.paymentNo(),fact,null);totals.accept(line);lines.add(line);totals.paymentCount++;
    if("SUCCEEDED".equals(fact.status()))totals.paid=Math.addExact(totals.paid,fact.amountCents());
   }
   persistBatch(header.snapshotNo(),lines);cursor=payments.getLast().paymentNo();
   if(payments.size()<500)break;
  }
  cursor=null;
  while(true){
   String seek=cursor==null?"":" AND refund_no>?";
   Object[] args=cursor==null?new Object[]{Timestamp.from(request.start()),Timestamp.from(request.end())}:new Object[]{Timestamp.from(request.start()),Timestamp.from(request.end()),cursor};
   List<RefundContracts.ChannelFact> refunds=db.query("SELECT refund_no,payment_no,order_no,source_no,amount_cents,currency,status,channel_refund_no,channel_version,updated_at,succeeded_at FROM channel_refund WHERE currency='CNY' AND status IN ('SUCCEEDED','FAILED') AND updated_at>=? AND updated_at<?"+seek+" ORDER BY refund_no LIMIT 500",(r,n)->new RefundContracts.ChannelFact(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),r.getString(7),r.getString(8),r.getLong(9),instant(r,10),instant(r,11)),args);
   if(refunds.isEmpty())break;
   List<BillLine> lines=new ArrayList<>(refunds.size());
   for(var fact:refunds){
    BillLine line=new BillLine(totals.lineCount+1,"REFUND",fact.refundNo(),null,fact);totals.accept(line);lines.add(line);totals.refundCount++;
    if("SUCCEEDED".equals(fact.status()))totals.refunded=Math.addExact(totals.refunded,fact.amountCents());
   }
   persistBatch(header.snapshotNo(),lines);cursor=refunds.getLast().refundNo();
   if(refunds.size()<500)break;
  }
  String checksum=HexFormat.of().formatHex(totals.digest.digest());
  if("BAD_CHECKSUM".equals(header.mode()))checksum=(checksum.charAt(0)=='0'?"1":"0")+checksum.substring(1);
  var manifest=new BillManifest(header.snapshotNo(),request.requestNo(),"SIMULATOR",request.currency(),request.start(),request.end(),request.cutoff(),watermark,"INCOMPLETE".equals(header.mode())?"INCOMPLETE":"COMPLETE",totals.lineCount,totals.paymentCount,totals.refundCount,totals.paid,totals.refunded,checksum);
  db.update("UPDATE bill_snapshot SET manifest_json=? WHERE snapshot_no=? AND manifest_json IS NULL",encode(manifest),header.snapshotNo());
  return manifest;
 }
 /** 逐批冻结不可变明细，调用者必须处于首次冻结事务；超过上限时已写批次也全部回滚。 */
 private void persistBatch(String snapshot,List<BillLine> lines){
  db.batchUpdate("INSERT INTO bill_snapshot_line(snapshot_no,position,transaction_type,business_no,payload) VALUES(?,?,?,?,?)",lines,500,(ps,l)->{ps.setString(1,snapshot);ps.setLong(2,l.position());ps.setString(3,l.transactionType());ps.setString(4,l.businessNo());ps.setString(5,encode(l));});
 }
 /** 只保留常数大小的计数和摘要状态；第100001行立即拒绝，不发布已写入的截断账单。 */
 private static final class FreezeTotals {
  long lineCount,paymentCount,refundCount,paid,refunded;
  final java.security.MessageDigest digest;
  FreezeTotals(){try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  void accept(BillLine line){
   if(lineCount>=100000)throw new BusinessException(422,"BILL_TOO_LARGE","账单超过100000行，请缩小账期");
   digest.update(ReconciliationContracts.canonical(line));lineCount++;
  }
 }
 /** 仅查询已提交的不可变清单；不存在返回404，不临时生成或刷新账单。 */
 public BillManifest manifest(String snapshot){return decode(header(snapshot).manifestJson(),BillManifest.class);}
 /** position是稳定游标，拒绝越界；正常分页不跳行，空账单返回原游标和hasMore=false。 */
 @Transactional(readOnly=true)
 public BillPage page(String snapshot,long after,int pageSize){
  Header h=header(snapshot);BillManifest m=decode(h.manifestJson(),BillManifest.class);
  if(after<0||after>m.lineCount()||pageSize<1||pageSize>100)throw error(400,"BILL_CURSOR_INVALID","账单游标越界或页大小不在1至100");
  List<BillLine> lines=new ArrayList<>(db.query("SELECT payload FROM bill_snapshot_line WHERE snapshot_no=? AND position>? ORDER BY position LIMIT ?",(r,n)->decode(r.getString(1),BillLine.class),snapshot,after,pageSize));
  long next=lines.isEmpty()?after:lines.getLast().position();boolean more=next<m.lineCount();
  // 开发故障只投影固定快照的最后一页，故意形成可检测缺页；不修改资金事实或持久明细。
  if("TRUNCATED_PAGE".equals(h.mode())&&!more&&!lines.isEmpty()){lines.removeLast();next=lines.isEmpty()?after:lines.getLast().position();}
  return new BillPage(snapshot,after,List.copyOf(lines),next,more);
 }
 /** 场景在首次冻结前一次绑定，与冻结共用唯一键屏障；管理身份由入口验证，审计与绑定同事务。 */
 @Transactional
 public void configure(String requestNo,String mode,String actor,String reason,String requestId){
  if(!validNo(requestNo)||!Set.of("INCOMPLETE","BAD_CHECKSUM","TRUNCATED_PAGE").contains(mode==null?"":mode)||actor==null||actor.isBlank()||actor.length()>64||reason==null||reason.isBlank()||reason.length()>500)throw error(400,"BILL_SCENARIO_INVALID","需合法请求号、投影模式、执行人和原因");
  int inserted=db.update("INSERT IGNORE INTO bill_projection_scenario(request_no,mode,actor_id,reason) VALUES(?,?,?,?)",requestNo,mode,actor,reason);
  if(inserted!=1)throw error(409,"BILL_SCENARIO_BOUND","请求号已冻结或已配置场景");
  db.update("INSERT INTO audit_log(actor_id,action,business_no,request_id,reason,result) VALUES(?,'BILL_PROJECTION_CONFIG',?,?,?,'CONFIGURED')",actor,requestNo,requestId,reason);
 }
 /** 时间精度必须能无损存入DATETIME(3)；宽限与区间在读源数据前校验，数据库时钟负责截止点上界。 */
 private void validate(BillRequest r){
  if(r==null||!validNo(r.requestNo())||!"CNY".equals(r.currency())||r.start()==null||r.end()==null||r.cutoff()==null)throw error(400,"BILL_REQUEST_INVALID","账单参数不完整或币种非CNY");
  if(r.start().getNano()%1000000!=0||r.end().getNano()%1000000!=0||r.cutoff().getNano()%1000000!=0||!r.start().isBefore(r.end())||Duration.between(r.start(),r.end()).compareTo(Duration.ofDays(31))>0||Duration.between(r.end(),r.cutoff()).compareTo(Duration.ofSeconds(300))<0)throw error(400,"BILL_RANGE_INVALID","账期最多31天、精度毫秒且截止点须在结束五分钟之后");
  // MySQL DATETIME范围，禁止驱动把无法存储的Instant截断成不同幂等元数据。
  if(r.start().isBefore(Instant.parse("1000-01-01T00:00:00Z"))||r.cutoff().isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw error(400,"BILL_RANGE_INVALID","时间超出数据库范围");
 }
 private boolean validNo(String s){return s!=null&&s.matches("[A-Za-z0-9_:-]{1,64}");}
 /** 账头仅在同事务内存在暂态空清单；对外查询不会读到未提交的半成品。 */
 private Header header(String snapshot){var found=db.query("SELECT snapshot_no,request_json,manifest_json,projection_mode FROM bill_snapshot WHERE snapshot_no=?",(r,n)->new Header(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),snapshot);if(found.isEmpty())throw error(404,"BILL_NOT_FOUND","账单快照不存在");return found.getFirst();}
 private static Instant instant(ResultSet r,int column)throws SQLException{Timestamp t=r.getTimestamp(column);return t==null?null:t.toInstant();}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("账单编码失败",e);}}
 private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(JsonProcessingException e){throw new IllegalStateException("账单持久化内容不可读",e);}}
 private BusinessException error(int status,String code,String message){return new BusinessException(status,code,message);}
}

