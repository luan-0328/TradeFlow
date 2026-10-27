package com.tradeflow.aftersale.infrastructure;

import com.tradeflow.aftersale.domain.Aftersale;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

/** 仅访问售后独立库。加锁及状态修改必须由上层短事务包围，禁止持锁RPC。 */
@Repository
public class AftersaleStore {
 private final JdbcTemplate jdbc;
 public AftersaleStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 /** 原验收逐件处置不可变；恢复任务与材料查询都读取同一份事实，不从当前界面重建。 */
 public java.util.List<com.tradeflow.contracts.RefundContracts.ReturnDisposition> dispositions(String no){
  return jdbc.query("SELECT CAST(order_item_id AS CHAR),unit_no,disposition FROM aftersale_return_disposition WHERE aftersale_no=? ORDER BY order_item_id,unit_no",
   (rs,n)->new com.tradeflow.contracts.RefundContracts.ReturnDisposition(rs.getString(1),rs.getInt(2),rs.getString(3)),no);
 }
 /** 通过可代理的方法访问目标实例依赖，禁止直接读取CGLIB代理上未初始化的实例字段；调用方仍遵守本库短事务边界。 */
 public JdbcTemplate jdbc(){return jdbc;}
 /** 只关联当前买家CREATE原键与所属订单的已提交售后；单SQL快照不锁业务，不启动授权或退款。 */
 public Aftersale acceptedCreate(String user,String key,String order){return jdbc.query("SELECT a.* FROM aftersale a JOIN aftersale_idempotency i ON i.aftersale_no=a.aftersale_no WHERE i.actor_id=? AND i.operation='CREATE' AND i.idempotency_key=? AND a.user_id=? AND a.order_no=?",mapper,user,key,user,order).stream().findFirst().orElse(null);}
 private final RowMapper<Aftersale> mapper=(r,n)->new Aftersale(r.getString("aftersale_no"),r.getString("order_no"),r.getString("user_id"),r.getString("merchant_id"),r.getString("type"),r.getString("reason"),r.getString("status"),r.getString("refund_no"),r.getString("refund_status"),(Long)r.getObject("amount_cents"),r.getString("currency"),r.getString("last_error"));
 /** 锁顺序统一售后主单再任务；并发审核与撤销由该行串行仲裁。 */
 public Aftersale get(String no,boolean lock){return jdbc.query("SELECT * FROM aftersale WHERE aftersale_no=?"+(lock?" FOR UPDATE":""),mapper,no).stream().findFirst().orElse(null);}
 /** 本人/商家列表只读取无地址的售后快照，限制单页最大100条。 */
 public List<Aftersale> list(String column,String owner,int limit,String before){if(!Set.of("user_id","merchant_id").contains(column))throw new IllegalArgumentException();return jdbc.query("SELECT * FROM aftersale WHERE "+column+"=? AND aftersale_no<? ORDER BY aftersale_no DESC LIMIT ?",mapper,owner,before,limit);}
 /** 任务持久化与业务状态同事务；提升围栏使旧领取结果失效。 */
 public void task(String no,String phase){jdbc.update("INSERT INTO aftersale_task(aftersale_no,phase,status) VALUES(?,?,'READY') ON DUPLICATE KEY UPDATE phase=VALUES(phase),status='READY',attempts=0,claim_version=claim_version+1,lease_until=NULL,next_run_at=CURRENT_TIMESTAMP(6),last_error=NULL",no,phase);}
 /** 原申请逐件分配仅追加，恢复任务按固定商品行/件序读取，不重新采用客户端输入。 */
 public java.util.List<com.tradeflow.contracts.RefundContracts.Allocation> allocations(String no){return jdbc.query("SELECT order_item_id,unit_no,amount_cents FROM aftersale_allocation WHERE aftersale_no=? ORDER BY order_item_id,unit_no",(r,n)->new com.tradeflow.contracts.RefundContracts.Allocation(r.getString("order_item_id"),r.getInt("unit_no"),r.getLong("amount_cents")),no);}
 public record Claim(String no,String phase,long version,int attempts){}
 /** 领取事务只锁任务、不锁业务主单，避免跨层等待；30秒租约回收过期RUNNING，每次领取增加版本。 */
 public Claim claim(){var rows=jdbc.queryForList("SELECT aftersale_no FROM aftersale_task WHERE (status='READY' AND next_run_at<=CURRENT_TIMESTAMP(6)) OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6)) ORDER BY next_run_at LIMIT 1");if(rows.isEmpty())return null;String no=(String)rows.get(0).get("aftersale_no");var tasks=jdbc.queryForList("SELECT * FROM aftersale_task WHERE aftersale_no=? FOR UPDATE",no);var t=tasks.get(0);int changed=jdbc.update("UPDATE aftersale_task SET status='RUNNING',lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND),claim_version=claim_version+1,attempts=attempts+1 WHERE aftersale_no=? AND ((status='READY' AND next_run_at<=CURRENT_TIMESTAMP(6)) OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6)))",no);return changed==0?null:new Claim(no,(String)t.get("phase"),((Number)t.get("claim_version")).longValue()+1,((Number)t.get("attempts")).intValue()+1);}
 /** 结果应用前锁定当前任务并复核围栏和租期；失效执行者不能覆盖新阶段。 */
 public boolean owns(Claim c){var v=jdbc.queryForList("SELECT claim_version FROM aftersale_task WHERE aftersale_no=? AND status='RUNNING' AND claim_version=? AND lease_until>CURRENT_TIMESTAMP(6) FOR UPDATE",c.no(),c.version());return !v.isEmpty();}
 /** 预算耗尽只隔离人工，不伪造资金终态、不释放订单授权。 */
 public void reschedule(Claim c,String error,boolean normal){int count=c.attempts();String code=error==null&&count>=30?"RECOVERY_BUDGET_EXHAUSTED":error;jdbc.update("UPDATE aftersale_task SET status=?,attempts=?,next_run_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 5 SECOND),lease_until=NULL,last_error=? WHERE aftersale_no=? AND claim_version=?",count>=30?"MANUAL_REVIEW":"READY",count,code,c.no(),c.version());jdbc.update("UPDATE aftersale SET last_error=? WHERE aftersale_no=?",code,c.no());}
 public void done(Claim c){jdbc.update("UPDATE aftersale_task SET status='DONE',lease_until=NULL,last_error=NULL WHERE aftersale_no=? AND claim_version=?",c.no(),c.version());}
}
