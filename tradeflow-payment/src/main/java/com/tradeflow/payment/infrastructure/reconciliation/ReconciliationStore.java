package com.tradeflow.payment.infrastructure.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.sql.ResultSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 本库对账持久层；任务领取独立提交，所有回写与资金动作均先验证30秒租约围栏。 */
@Repository
public class ReconciliationStore {
 private final JdbcTemplate db; private final TransactionTemplate tx; private final ObjectMapper json;
 public ReconciliationStore(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json){this.db=db;this.tx=tx;this.json=json;}
 public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("对账证据编码失败",e);}}
 public <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("对账证据解码失败",e);}}
 /** 表名仅来自内部白名单，禁止外部输入拼接SQL。 */
 private String table(String kind){if(!Set.of("run","action","issue").contains(kind))throw new IllegalArgumentException();return "reconciliation_"+kind;}
 /** 平台身份由应用层预先验证；按不可变任务号读取本库状态，不领取租约、不触发远程或资金副作用，缺失统一404。 */
 public Map<String,Object> find(String kind,String no){var rows=db.queryForList("SELECT * FROM "+table(kind)+" WHERE task_no=?",no);if(rows.isEmpty())throw new BusinessException(404,"RECONCILIATION_NOT_FOUND","对账记录不存在");return rows.getFirst();}
 /** 只读平台分页限制最多100条，固定创建时间与任务号排序；仅访问白名单本库表，调用方负责事务外授权。 */
 public List<Map<String,Object>> list(String kind,int page,int size){if(page<1||size<1||size>100)throw new BusinessException(400,"INVALID_ARGUMENT","分页范围不合法");return db.queryForList("SELECT * FROM "+table(kind)+" ORDER BY created_at DESC,task_no LIMIT ? OFFSET ?",size,(long)(page-1)*size);}
 /** 命令行锁串行化同主体同操作幂等；同键异参拒绝，编号与初始任务同事务提交。 */
 public String command(String actor,String operation,String key,String hash,String audit,Supplier<String> create){
  if(key==null||!key.matches("[A-Za-z0-9._:-]{1,64}"))throw new BusinessException(400,"INVALID_IDEMPOTENCY_KEY","幂等键不合法");
  return tx.execute(t->{db.update("INSERT IGNORE INTO reconciliation_command(actor,operation_name,idempotency_key,request_hash,request_body,result_no) VALUES(?,?,?,?,?,NULL)",actor,operation,key,hash,audit);
   var row=db.queryForMap("SELECT request_hash,result_no FROM reconciliation_command WHERE actor=? AND operation_name=? AND idempotency_key=? FOR UPDATE",actor,operation,key);
   if(!hash.equals(row.get("request_hash")))throw new BusinessException(409,"IDEMPOTENCY_CONFLICT","同幂等键参数不同");
   if(row.get("result_no")!=null)return row.get("result_no").toString();String result=create.get();db.update("UPDATE reconciliation_command SET result_no=? WHERE actor=? AND operation_name=? AND idempotency_key=?",result,actor,operation,key);return result;});
 }
 /** 调用方在命令或运行围栏事务内插入稳定新任务号；唯一主键阻止重复建任务，初始payload与原请求同事务持久化。 */
 public void insert(String kind,String no,String state,Object payload){db.update("INSERT INTO "+table(kind)+"(task_no,state,payload) VALUES(?,?,?)",no,state,encode(payload));}
 /** 已到期任务包括失联RUNNING；领取只锁任务行，不锁任何资金表。 */
 public Map<String,Object> claim(String kind,String owner){return tx.execute(t->{var rows=db.queryForList("SELECT * FROM "+table(kind)+" WHERE state IN ('READY','RUNNING') AND next_at<=UTC_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6)) ORDER BY next_at,created_at,task_no LIMIT 1 FOR UPDATE SKIP LOCKED");if(rows.isEmpty())return null;String no=rows.getFirst().get("task_no").toString();db.update("UPDATE "+table(kind)+" SET state='RUNNING',lease_owner=?,claim_version=claim_version+1,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 SECOND) WHERE task_no=?",owner,no);return find(kind,no);});}
 /** 在同一短事务先锁且验证owner/version/expiry，再执行本地副作用；过期执行者不能应用资金或保存检查点。 */
 public <T>T fenced(String kind,Map<String,Object> claim,Supplier<T> action){return tx.execute(t->{db.queryForMap("SELECT * FROM "+table(kind)+" WHERE task_no=? FOR UPDATE",claim.get("task_no"));
   var valid=db.queryForObject("SELECT COUNT(*) FROM "+table(kind)+" WHERE task_no=? AND state='RUNNING' AND lease_owner=? AND claim_version=? AND lease_until>UTC_TIMESTAMP(6)",Integer.class,claim.get("task_no"),claim.get("lease_owner"),claim.get("claim_version"));
   if(valid==null||valid!=1)throw new StaleLease();T result=action.get();
   // 锁资金期间也可能跨过租约到期，提交前再次验证；失败使同事务资金变化整体回滚。
   if(!validLease(kind,claim))throw new StaleLease();db.update("UPDATE "+table(kind)+" SET lease_owner=NULL,lease_until=NULL WHERE task_no=? AND state<>'RUNNING'",claim.get("task_no"));return result;});}
 /** 提交前验证同一领取版本与未到期租约，不依赖刚被更新的阶段状态。 */
 private boolean validLease(String kind,Map<String,Object> claim){return db.queryForObject("SELECT COUNT(*) FROM "+table(kind)+" WHERE task_no=? AND lease_owner=? AND claim_version=? AND lease_until>UTC_TIMESTAMP(6)",Integer.class,claim.get("task_no"),claim.get("lease_owner"),claim.get("claim_version"))==1;}
 /** 进度成功清零连续依赖失败预算；释放租约，下一小步重新领取。 */
 public void save(String kind,Map<String,Object> claim,String state,Object payload){if(java.util.Set.of("SUCCEEDED","MANUAL_REVIEW","INCOMPLETE").contains(state))com.tradeflow.observability.BusinessLog.afterCommit("RECONCILIATION_TASK_STATE",java.util.Map.of("taskId",claim.get("task_no").toString(),"state",state));db.update("UPDATE "+table(kind)+" SET state=?,payload=?,consecutive_failures=0,next_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE task_no=?",state,encode(payload),claim.get("task_no"));}
 /** 依赖异常最多连续30次转人工；失败预算不会因旧执行者回写被覆盖。 */
 public void failed(String kind,Map<String,Object> claim,Exception error){try{fenced(kind,claim,()->{db.update("UPDATE "+table(kind)+" SET state=IF(consecutive_failures+1>=30,'MANUAL_REVIEW','READY'),consecutive_failures=consecutive_failures+1,last_error=?,next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 SECOND),updated_at=UTC_TIMESTAMP(6) WHERE task_no=?",error.getClass().getSimpleName(),claim.get("task_no"));if("action".equals(kind)){var latest=find(kind,claim.get("task_no").toString());if("MANUAL_REVIEW".equals(latest.get("state"))&&latest.get("issue_no")!=null)db.update("UPDATE reconciliation_issue SET state='MANUAL_REVIEW',action_no=?,updated_at=UTC_TIMESTAMP(6) WHERE task_no=? AND action_no=?",claim.get("task_no"),latest.get("issue_no"),claim.get("task_no"));}return null;});}catch(StaleLease ignored){}}
 /** 人工只恢复原任务原payload原快照；INCOMPLETE快照禁止伪装重建。 */
 public String retry(String kind,String no){
  // 动作重放先锁自身，再锁差异；与执行器保持同向，repair只锁差异并普通读取已有动作。
  find(kind,no);var r=db.queryForMap("SELECT * FROM "+table(kind)+" WHERE task_no=? FOR UPDATE",no);
  if("INCOMPLETE".equals(r.get("state")))throw new BusinessException(409,"NEW_RUN_REQUIRED","不完整快照必须新建对账批次");
  if(!"MANUAL_REVIEW".equals(r.get("state")))throw new BusinessException(409,"TASK_NOT_MANUAL","只有人工待处置任务可以原号重试");
  String issue="action".equals(kind)&&r.get("issue_no")!=null?r.get("issue_no").toString():null;
  if(issue!=null){lockIssue(issue);if(activeActionNo(issue)!=null)throw new BusinessException(409,"ACTION_ALREADY_ACTIVE","该差异已有其他活动动作，不能重放旧动作");}
  int changed=db.update("UPDATE "+table(kind)+" SET state='READY',consecutive_failures=0,claim_version=claim_version+1,lease_owner=NULL,lease_until=NULL,next_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE task_no=? AND state='MANUAL_REVIEW'",no);
  if(changed!=1)throw new BusinessException(409,"TASK_NOT_MANUAL","任务状态已变化");if(issue!=null)attachAction(issue,no);return no;
 }

 /** 修复命令锁差异后只读活动动作编号，不反向锁正在执行的action，避免与资金到差异锁序成环。 */
 public Map<String,Object> lockIssue(String no){var rows=db.queryForList("SELECT * FROM reconciliation_issue WHERE task_no=? FOR UPDATE",no);if(rows.isEmpty())throw new BusinessException(404,"RECONCILIATION_NOT_FOUND","差异不存在");return rows.getFirst();}
 /** 前提为调用方持有差异行锁；普通读取活动动作而不加动作锁，用于复用或拒绝重放，避免差异到已有动作的反向锁序。 */
 public String activeActionNo(String issue){var rows=db.queryForList("SELECT task_no FROM reconciliation_action WHERE issue_no=? AND state IN ('READY','RUNNING') ORDER BY created_at,task_no LIMIT 1",String.class,issue);return rows.isEmpty()?null:rows.getFirst();}
 /** 运行与差异历史证据仅追加、分页读取，重复观察不覆盖原始证据。 */
 public List<Map<String,Object>> observations(String scope,String no,int page,int size){if(!Set.of("run_no","issue_no").contains(scope))throw new IllegalArgumentException();if(page<1||size<1||size>100)throw new BusinessException(400,"INVALID_ARGUMENT","分页范围不合法");return db.queryForList("SELECT run_no,issue_no,evidence,created_at FROM reconciliation_observation WHERE "+scope+"=? ORDER BY created_at,run_no,issue_no LIMIT ? OFFSET ?",no,size,(long)(page-1)*size);}

 /** 暂存页与run游标由调用方同一围栏事务写入，崩溃不会跳过未持久行。 */
 public void stage(String run,long position,String type,String businessNo,Object line){db.update("INSERT INTO reconciliation_stage_line(run_no,position_no,transaction_type,business_no,payload) VALUES(?,?,?,?,?)",run,position,type,businessNo,encode(line));}
 /** MySQL前向流式读取暂存证据，只保留单行，避免最大十万行在小堆同时反序列化；回调禁止RPC或另发SQL。 */
 public void forEachLine(String run,Consumer<String> consume){db.query(connection->{var statement=connection.prepareStatement("SELECT payload FROM reconciliation_stage_line WHERE run_no=? ORDER BY position_no",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);statement.setString(1,run);statement.setFetchSize(Integer.MIN_VALUE);return statement;},(org.springframework.jdbc.core.RowCallbackHandler) result->consume.accept(result.getString(1)));}

 /** 仅在完整账单已验证阶段按冻结位置读取下一条；暂存绑定不可改，单步上限一条避免多次远程查询耗尽租约。 */
 public List<Map<String,Object>> stagePage(String run,long after){return db.queryForList("SELECT * FROM reconciliation_stage_line WHERE run_no=? AND position_no>? ORDER BY position_no LIMIT 1",run,after);}
 /** 只读检查原运行中类型与业务号是否已有不可变暂存事实；下载用于拒绝重复，反向扫描用于判定是否需要实时补查。 */
 public boolean staged(String run,String type,String no){return db.queryForObject("SELECT COUNT(*) FROM reconciliation_stage_line WHERE run_no=? AND transaction_type=? AND business_no=?",Long.class,run,type,no)>0;}
 /** 稳定指纹去重；run观察独立保存，已解决问题重现时重新打开并复用原问题号。 */
 public String issue(String fingerprint,String no,String run,String type,String business,String issueType,Object evidence){
  db.update("INSERT INTO reconciliation_issue(task_no,state,payload,fingerprint,transaction_type,business_no,issue_type) VALUES(?,'OPEN',?,?,?,?,?) ON DUPLICATE KEY UPDATE state=IF(state='RESOLVED','OPEN',state),payload=VALUES(payload),updated_at=UTC_TIMESTAMP(6)",no,encode(evidence),fingerprint,type,business,issueType);
  String issue=db.queryForObject("SELECT task_no FROM reconciliation_issue WHERE fingerprint=?",String.class,fingerprint);
  db.update("INSERT IGNORE INTO reconciliation_observation(run_no,issue_no,evidence) VALUES(?,?,?)",run,issue,encode(evidence));return issue;
 }
 /** 只统计原运行不可变观察，恢复检查点时以实际持久行数为准，避免崩溃或先行修复使差异计数丢失。 */
 public long observationCount(String run){return db.queryForObject("SELECT COUNT(*) FROM reconciliation_observation WHERE run_no=?",Long.class,run);}
 /** 必须加入已验证动作围栏的本地事务；仅当前action_no可写差异状态和复查证据，旧动作不得覆盖新修复指针。 */
 public void issueState(String issue,String state,String action,Object evidence){db.update("UPDATE reconciliation_issue SET state=?,payload=?,updated_at=UTC_TIMESTAMP(6) WHERE task_no=? AND action_no=?",state,encode(evidence),issue,action);}
 /** 调用方持有差异锁时安装当前动作，旧动作后续写差异状态必须匹配此指针。 */
 public void attachAction(String issue,String action){db.update("UPDATE reconciliation_issue SET state='VERIFYING',action_no=?,updated_at=UTC_TIMESTAMP(6) WHERE task_no=?",action,issue);}
 /** 前提为当前事务已锁定差异；只读检测READY或RUNNING动作，自动扫描据此避免重复排队，人工状态不冒充活动任务。 */
 public boolean activeAction(String issue){return db.queryForObject("SELECT COUNT(*) FROM reconciliation_action WHERE issue_no=? AND state IN ('READY','RUNNING')",Long.class,issue)>0;}
 /** 自动动作保存原运行归属；人工与定向动作不冒充某次账单的修复计数。 */
 public void originRun(String action,String run){db.update("UPDATE reconciliation_action SET origin_run_no=? WHERE task_no=?",run,action);}
 /** 已授权平台只读聚合本库统计，不取得资金锁；修复数仅计实际apply且复查成功的原运行动作，纯匹配核验单列，耗时为创建至最近持久进度。 */
 public Map<String,Object> runStatistics(String no){Map<String,Object> result=new LinkedHashMap<>(db.queryForMap("SELECT COUNT(*) AS action_count,COALESCE(SUM(state='SUCCEEDED'),0) AS verified_action_count,COALESCE(SUM(state='SUCCEEDED' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.changed'))='true'),0) AS repaired_action_count FROM reconciliation_action WHERE origin_run_no=?",no));result.putAll(db.queryForMap("SELECT GREATEST(0,TIMESTAMPDIFF(MICROSECOND,created_at,updated_at) DIV 1000) AS duration_millis FROM reconciliation_run WHERE task_no=?",no));result.put("observed_issue_count",observationCount(no));return result;}
 /** 仅在新动作创建事务内绑定原差异号，尚未发布给领取器；运行账户只更新动作关联，不直接操作业务资金或历史观察。 */
 public void linkAction(String no,String issue){db.update("UPDATE reconciliation_action SET issue_no=? WHERE task_no=?",issue,no);}
 public static class StaleLease extends RuntimeException {}
}
