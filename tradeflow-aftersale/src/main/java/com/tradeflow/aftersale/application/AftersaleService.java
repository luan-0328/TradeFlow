package com.tradeflow.aftersale.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.aftersale.domain.Aftersale;
import com.tradeflow.aftersale.infrastructure.*;
import com.tradeflow.aftersale.infrastructure.client.*;
import com.tradeflow.common.*;
import com.tradeflow.contracts.RefundContracts.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 用户意图、审核与恢复入口；RPC在事务外，所有状态竞争由售后行锁仲裁。 */
@Service
public class AftersaleService {
 private final AftersaleStore db;private final Authority authority;private final OrderClient orders;private final RefundClient refunds;private final TransactionTemplate tx;private final ObjectMapper json;private final String token;private final EvidenceService evidence;
 public AftersaleService(AftersaleStore db,Authority authority,OrderClient orders,RefundClient refunds,TransactionTemplate tx,ObjectMapper json,Environment env,EvidenceService evidence){this.evidence=evidence;this.db=db;this.authority=authority;this.orders=orders;this.refunds=refunds;this.tx=tx;this.json=json;token=env.getRequiredProperty("tradeflow.internal.aftersale-token");}
 /** 已授权售后视图仅按实际访问入口提供动作；退货期限使用数据库UTC，写用例仍复核并发状态。 */
 public List<String> allowedActions(Aftersale a,String audience){
  var result=new ArrayList<String>();if(!"user".equals(audience)&&!"merchant".equals(audience))return List.of();result.add("VIEW_MATERIALS");
  if("user".equals(audience)){
   if(Set.of("CREATING","PENDING_REVIEW").contains(a.status()))result.add("CANCEL");
   if("RETURN_REFUND".equals(a.type())&&"WAITING_RETURN".equals(a.status())&&Integer.valueOf(1).equals(db.jdbc().queryForObject("SELECT COUNT(*) FROM aftersale WHERE aftersale_no=? AND status='WAITING_RETURN' AND return_deadline>CURRENT_TIMESTAMP(6)",Integer.class,a.aftersaleNo())))result.add("RETURN_SHIPMENT");
  }else if("merchant".equals(audience)){
   if("PENDING_REVIEW".equals(a.status()))result.addAll(List.of("APPROVE","REJECT"));
   if("RETURN_REFUND".equals(a.type())&&"RETURN_IN_TRANSIT".equals(a.status()))result.add("RETURN_INSPECTION");
   if("REFUND_PROCESSING".equals(a.status())&&"FAILED".equals(a.refundStatus()))result.add("RETRY_REFUND");
  }
  return List.copyOf(result);
 }
 public record Create(String orderNo,String type,String reason,List<Allocation> allocations,List<String> evidenceKeys){}
 public record Review(Boolean approved,String reason){}
 public record Reason(String reason){}
 /** 最新本人身份在查询前复核；按原创建键恢复申请，不接受客户端指定他人账号，未找到仍保留未知态。 */
 public Aftersale lookupCreate(Jwt jwt,String key,String orderNo){var user=authority.check(jwt,"user");key(key);if(orderNo==null||!orderNo.matches("ord_[a-f0-9]{32}"))throw error(400,"INVALID_ORDER_NO","订单编号无效");return db.acceptedCreate(user.userId(),key,orderNo);}
 public static BusinessException error(int status,String code,String msg){return new BusinessException(status,code,msg);}
 public static String number(String prefix){return prefix+UUID.randomUUID().toString().replace("-","");}
 public static void reason(String value){if(value==null||value.isBlank()||value.length()>500)throw error(400,"INVALID_REASON","原因必填且不超过500字符");}
 private void key(String key){if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,64}"))throw error(400,"INVALID_IDEMPOTENCY_KEY","必须提供不超过64字符的ASCII Idempotency-Key");}
 private String digest(Object body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(digestBody(body))));}catch(Exception e){throw new IllegalStateException(e);}}
 /** B4空分配整单请求沿用原摘要结构，升级后原键可重放，不将新增可选字段误判为参数变化。 */
 private Object digestBody(Object body){if(body instanceof Create c&&"UNSHIPPED_FULL".equals(c.type())&&(c.allocations()==null||c.allocations().isEmpty())&&(c.evidenceKeys()==null||c.evidenceKeys().isEmpty())){var original=new LinkedHashMap<String,Object>();original.put("orderNo",c.orderNo());original.put("type",c.type());original.put("reason",c.reason());return original;}return body;}
 /** 幂等范围为操作者+操作+键；不同参数409，同参数返回原资源。调用者已持业务锁或在创建事务中。 */
 String replay(String uid,String operation,String key,Object body,String no){key(key);String hash=digest(body);var rows=db.jdbc().queryForList("SELECT request_hash,aftersale_no FROM aftersale_idempotency WHERE actor_id=? AND operation=? AND idempotency_key=?",uid,operation,key);if(!rows.isEmpty()){var r=rows.get(0);if(!hash.equals(r.get("request_hash")))throw error(409,"IDEMPOTENCY_CONFLICT","同幂等键请求参数不同");return (String)r.get("aftersale_no");}db.jdbc().update("INSERT INTO aftersale_idempotency(actor_id,operation,idempotency_key,request_hash,aftersale_no) VALUES(?,?,?,?,?)",uid,operation,key,hash,no);return null;}
 /** 创建并发先由唯一键串行，再锁定原幂等绑定；同键同参只返回首次售后号，不重新生成退款。 */
 private String bindCreate(String uid,String key,Create body,String no){String hash=digest(body);db.jdbc().update("INSERT INTO aftersale_idempotency(actor_id,operation,idempotency_key,request_hash,aftersale_no) VALUES(?,'CREATE',?,?,?) ON DUPLICATE KEY UPDATE idempotency_key=idempotency_key",uid,key,hash,no);var row=db.jdbc().queryForMap("SELECT request_hash,aftersale_no FROM aftersale_idempotency WHERE actor_id=? AND operation='CREATE' AND idempotency_key=? FOR UPDATE",uid,key);if(!hash.equals(row.get("request_hash")))throw error(409,"IDEMPOTENCY_CONFLICT","同幂等键请求参数不同");String bound=(String)row.get("aftersale_no");return no.equals(bound)?null:bound;}
 /** 重放先读持久结果，避免新尝试尚未提交时原请求被退款404阻断；最终写仍在主单锁内复核。 */
 private Aftersale replayed(String uid,String operation,String key,Object body){key(key);var rows=db.jdbc().queryForList("SELECT request_hash,aftersale_no FROM aftersale_idempotency WHERE actor_id=? AND operation=? AND idempotency_key=?",uid,operation,key);if(rows.isEmpty())return null;var r=rows.get(0);if(!digest(body).equals(r.get("request_hash")))throw error(409,"IDEMPOTENCY_CONFLICT","同幂等键请求参数不同");return require((String)r.get("aftersale_no"),false);}
 private Aftersale require(String no,boolean lock){if(no==null||!no.matches("as_[a-f0-9]{32}"))throw error(404,"AFTERSALE_NOT_FOUND","售后不存在");var a=db.get(no,lock);if(a==null)throw error(404,"AFTERSALE_NOT_FOUND","售后不存在");return a;}
 private Aftersale owned(String no,String owner,boolean merchant,boolean lock){var a=require(no,lock);if(!(merchant?a.merchantId():a.userId()).equals(owner))throw error(404,"AFTERSALE_NOT_FOUND","售后不存在");return a;}
 /** 先核验本人订单，持久逐件申请和原号恢复意图；权益由订单任务重新校验，未知不换号。 */
 public Aftersale create(Jwt jwt,String key,Create input){
  var user=authority.check(jwt,"user");key(key);
  if(input==null||input.orderNo()==null||!input.orderNo().matches("ord_[a-f0-9]{32}")||!Set.of("UNSHIPPED_FULL","REFUND_ONLY","RETURN_REFUND").contains(input.type()==null?"":input.type()))throw error(400,"UNSUPPORTED_AFTERSALE_TYPE","售后类型无效");
  reason(input.reason());List<Allocation> allocations=normalize(input.allocations());
  if(!"UNSHIPPED_FULL".equals(input.type())&&allocations.isEmpty())throw error(400,"ALLOCATION_REQUIRED","必须确认逐件申请金额");
  List<String> keys=normalizeEvidence(input.evidenceKeys());if("RETURN_REFUND".equals(input.type())&&keys.size()>5)throw error(400,"EVIDENCE_LIMIT_EXCEEDED","退货申请最多5张，预留至少1张验收证据位置");Create body=new Create(input.orderNo(),input.type(),input.reason(),allocations,keys);
  var prior=replayed(user.userId(),"CREATE",key,body);if(prior!=null)return prior;
  var intent=orders.intent(body.orderNo(),"aftersale",token);if(intent==null||!"OK".equals(intent.code())||intent.data()==null)throw error(503,"ORDER_UNAVAILABLE","无法查询订单");if(intent.data().merchantId()==null)throw error(503,"ORDER_CONTRACT_INVALID","订单未返回商家归属，请协调升级");if(!user.userId().equals(intent.data().userId()))throw error(404,"ORDER_NOT_FOUND","订单不存在");
  try{return tx.execute(s->{String no=number("as_");String old=bindCreate(user.userId(),key,body,no);if(old!=null)return require(old,false);String refund=number("rfd_");
   db.jdbc().update("INSERT INTO aftersale(aftersale_no,order_no,user_id,merchant_id,type,reason,status,refund_no,refund_status) VALUES(?,?,?,?,?,?,'CREATING',?,'NOT_SUBMITTED')",no,body.orderNo(),user.userId(),intent.data().merchantId(),body.type(),body.reason(),refund);
   for(var allocation:allocations)db.jdbc().update("INSERT INTO aftersale_allocation(aftersale_no,order_item_id,unit_no,amount_cents) VALUES(?,?,?,?)",no,Long.parseLong(allocation.orderItemId()),allocation.unitNo(),allocation.amountCents());
   evidence.bind(no,user.userId(),"APPLICATION",keys);
   db.jdbc().update("INSERT INTO aftersale_refund_attempt(refund_no,aftersale_no,status) VALUES(?,?,'NOT_SUBMITTED')",refund,no);db.task(no,"AUTHORIZATION");return require(no,false);
  });}catch(org.springframework.dao.DuplicateKeyException duplicate){var existing=replayed(user.userId(),"CREATE",key,body);if(existing!=null)return existing;throw duplicate;}
 }
 /** 按购买件规范排序并验证整数分，固定幂等摘要；最终可退额度以订单事务重验为准。 */
 static List<Allocation> normalize(List<Allocation> input){if(input==null)return List.of();if(input.size()>1980)throw error(400,"INVALID_ALLOCATION","申请件数超过上限");var result=new ArrayList<Allocation>();var identities=new HashSet<String>();long total=0;for(var a:input){if(a==null||a.orderItemId()==null||!a.orderItemId().matches("[1-9][0-9]{0,18}")||a.unitNo()<1||a.unitNo()>99||a.amountCents()<1||a.amountCents()>100000000)throw error(400,"INVALID_ALLOCATION","购买件及金额无效");try{Long.parseLong(a.orderItemId());total=Math.addExact(total,a.amountCents());}catch(ArithmeticException|NumberFormatException ex){throw error(400,"INVALID_ALLOCATION","购买件或金额超过范围");}if(total>100000000||!identities.add(a.orderItemId()+":"+a.unitNo()))throw error(400,"INVALID_ALLOCATION","购买件重复或总金额超限");result.add(a);}result.sort(Comparator.comparingLong((Allocation a)->Long.parseLong(a.orderItemId())).thenComparingInt(Allocation::unitNo));return List.copyOf(result);}
 /** 证据按固定键排序，防止同参乱序生成不同摘要；归属和绑定在事务内检查。 */
 static List<String> normalizeEvidence(List<String> keys){if(keys==null)return List.of();if(keys.size()>6||keys.stream().anyMatch(k->k==null||!k.matches("ev_[a-f0-9]{32}"))||new HashSet<>(keys).size()!=keys.size())throw error(400,"INVALID_EVIDENCE","证据最多6张且不可重复");return keys.stream().sorted().toList();}
 /** 本人及商家查询均使用最新身份；售后快照没有地址字段。 */
 public List<Aftersale> list(Jwt jwt,boolean merchant,int limit,String before){var u=authority.check(jwt,merchant?"merchant":"user");return db.list(merchant?"merchant_id":"user_id",merchant?u.merchantIds().get(0):u.userId(),Math.max(1,Math.min(100,limit)),before==null?"~":before);}
 public Aftersale detail(Jwt jwt,String no,boolean merchant){var u=authority.check(jwt,merchant?"merchant":"user");return owned(no,merchant?u.merchantIds().get(0):u.userId(),merchant,false);}
 /** 撤销先持久CANCELLING，与审核批准同锁竞争；授权可先建终止墓碑，未知保留终止任务。 */
 public Aftersale cancel(Jwt jwt,String no){var u=authority.check(jwt,"user");return tx.execute(s->{var a=owned(no,u.userId(),false,true);if(Set.of("CANCELLED","CANCELLING").contains(a.status()))return a;if(!Set.of("CREATING","PENDING_REVIEW").contains(a.status()))throw error(409,"AFTERSALE_STATE_CONFLICT","当前售后不可撤销");db.jdbc().update("UPDATE aftersale SET status='CANCELLING' WHERE aftersale_no=?",no);db.task(no,"TERMINATE");return require(no,false);});}
 /** 商家只批准原金额；退货批准后等待物流，其余保存退款意图；拒绝先可靠终止授权。 */
 public Aftersale review(Jwt jwt,String no,String key,Review body){var u=authority.check(jwt,"merchant");if(body==null||body.approved()==null)throw error(400,"INVALID_REVIEW","approved必填");reason(body.reason());return tx.execute(s->{var a=owned(no,u.merchantIds().get(0),true,true);if(replay(u.userId(),"REVIEW:"+no,key,body,no)!=null)return a;if(!"PENDING_REVIEW".equals(a.status()))throw error(409,"AFTERSALE_STATE_CONFLICT","售后不在待审核状态");db.jdbc().update("UPDATE aftersale SET status=? WHERE aftersale_no=?",body.approved()?("RETURN_REFUND".equals(a.type())?"WAITING_RETURN":"REFUND_PROCESSING"):"REJECTING",no);audit(u.userId(),no,"REVIEW",body.reason());if(body.approved()&&"RETURN_REFUND".equals(a.type())){db.jdbc().update("UPDATE aftersale SET return_deadline=CURRENT_TIMESTAMP(6)+INTERVAL 7 DAY WHERE aftersale_no=?",no);}else db.task(no,body.approved()?"REFUND":"TERMINATE");return require(no,false);});}
 /** 原键重放前仍复核最新身份和当前选店归属，撤权后不得读取历史店铺售后；仅原执行确认为FAILED且授权仍EXECUTING才换号，并发写在主单锁内复核。 */
 public Aftersale retryRefund(Jwt jwt,String no,String key,Reason body){var u=authority.check(jwt,"merchant");reason(body==null?null:body.reason());key(key);var snapshot=owned(no,u.merchantIds().get(0),true,false);var replayed=replayed(u.userId(),"RETRY:"+no,key,body);if(replayed!=null)return replayed;var authorization=orders.query(no,"aftersale",token);var old=refunds.query(snapshot.refundNo(),"aftersale",token);if(authorization==null||!"OK".equals(authorization.code())||authorization.data()==null||old==null||!"OK".equals(old.code())||old.data()==null)throw error(503,"REFUND_UNCONFIRMED","暂时无法确认原退款");return tx.execute(s->{var a=owned(no,u.merchantIds().get(0),true,true);if(replay(u.userId(),"RETRY:"+no,key,body,no)!=null)return a;var fact=old.data();if(!"REFUND_PROCESSING".equals(a.status())||!"FAILED".equals(a.refundStatus())||!a.refundNo().equals(snapshot.refundNo())||!a.refundNo().equals(fact.refundNo())||!no.equals(fact.sourceNo())||!a.orderNo().equals(fact.orderNo())||!"FAILED".equals(fact.status())||!"EXECUTING".equals(authorization.data().state())||!no.equals(authorization.data().sourceNo())||!a.orderNo().equals(authorization.data().orderNo())||!a.userId().equals(authorization.data().userId())||!a.merchantId().equals(authorization.data().merchantId())||authorization.data().amountCents()!=fact.amountCents()||!java.util.Objects.equals(authorization.data().currency(),fact.currency()))throw error(409,"REFUND_RETRY_NOT_ALLOWED","仅确认旧退款失败且授权执行中可重试");String fresh=number("rfd_");db.jdbc().update("UPDATE aftersale SET refund_no=?,refund_status='NOT_SUBMITTED',last_error=NULL WHERE aftersale_no=?",fresh,no);db.jdbc().update("INSERT INTO aftersale_refund_attempt(refund_no,aftersale_no,status) VALUES(?,?,'NOT_SUBMITTED')",fresh,no);audit(u.userId(),no,"RETRY_REFUND",body.reason());db.task(no,"REFUND");return require(no,false);});}
 /** 平台只恢复原任务与原退款号，原因审计同事务，不能人工写成功或换号。 */
 public Object tasks(Jwt jwt){authority.check(jwt,"admin");return db.jdbc().queryForList("SELECT aftersale_no,phase,status,attempts,claim_version,last_error FROM aftersale_task WHERE status='MANUAL_REVIEW' ORDER BY updated_at LIMIT 100");}
 public Aftersale retryTask(Jwt jwt,String no,Reason body){var u=authority.check(jwt,"admin");reason(body==null?null:body.reason());return tx.execute(s->{var a=require(no,true);var tasks=db.jdbc().queryForList("SELECT phase,status FROM aftersale_task WHERE aftersale_no=? FOR UPDATE",no);if(tasks.isEmpty()||!"MANUAL_REVIEW".equals(tasks.get(0).get("status")))throw error(409,"TASK_STATE_CONFLICT","任务不在人工恢复状态");audit(u.userId(),no,"RETRY_TASK",body.reason());db.task(no,(String)tasks.get(0).get("phase"));return a;});}
 private void audit(String actor,String no,String operation,String reason){db.jdbc().update("INSERT INTO aftersale_audit(actor_id,aftersale_no,operation,reason) VALUES(?,?,?,?)",actor,no,operation,reason);}
}
