package com.tradeflow.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 结算应用边界：成功事实唯一入账、退款累计冲费、最终资格重核、任务围栏；仅模拟完成。 */
@Service public class SettlementService {
 private final SettlementMapper db;private final SettlementRemote remote;private final TransactionTemplate tx;private final ObjectMapper json;private final int bps,days;private final boolean enabled;
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SettlementService.class);
 public SettlementService(SettlementMapper db,SettlementRemote remote,org.springframework.transaction.PlatformTransactionManager tm,ObjectMapper json,Environment env){this.db=db;this.remote=remote;this.tx=new TransactionTemplate(tm);this.json=json;bps=env.getProperty("tradeflow.settlement.fee-bps",Integer.class,0);days=env.getProperty("tradeflow.settlement.freeze-days",Integer.class,7);enabled=env.getProperty("tradeflow.settlement.worker-enabled",Boolean.class,true);if(bps<0||bps>10000||days<0||days>3650)throw new IllegalArgumentException("结算费率或冻结天数无效");}
 /** 后台宕机可在原持久游标继续；故障只记日志和原任务，不吞异常伪造完成。 */
 @Scheduled(fixedDelayString="${tradeflow.settlement.scan-delay-ms:5000}") public void scheduled(){if(enabled)try{run();}catch(Exception e){log.warn("模拟结算恢复任务未完成，保留原事实等待恢复：{}",e.getMessage());}}
 record Claim(String owner,long version,long cursor,long candidateCursor){}
 /** 领取和业务分离；所有资金变化先锁有效任务围栏，再锁商家、单据。 */
 public Map<String,Object> run(){
  String owner=UUID.randomUUID().toString();Claim claim=tx.execute(s->{db.ensureTask();db.lockTask();if(db.claim(owner)!=1)return null;var r=db.lockTask();return new Claim(owner,n(r,"claim_version"),n(r,"scan_cursor"),n(r,"candidate_cursor"));});
  if(claim==null)return Map.of("state","LEASED_BY_OTHER_INSTANCE");
  long cursor=claim.cursor();
  try{
   var page=remote.facts(cursor);validatePage(page,cursor);
   for(var f:page.items()){
    var prior=db.fact(f.kind(),f.businessNo());if(prior!=null){verifyFact(prior,f);continue;}
    SettlementRemote.Order order=remote.order(f.orderNo());validateOrder(order,f.orderNo());
    tx.executeWithoutResult(s->{fence(claim);apply(f,order);fence(claim);});
   }
   cursor=page.hasMore()?page.nextAfter():0;
   var candidates=db.candidates(claim.candidateCursor());
   for(var candidate:candidates){
    // 资格在每个最终短事务前重新查询；不把本轮早期查询当成最终授权。
    var order=remote.order(str(candidate,"order_no"));validateOrder(order,str(candidate,"order_no"));
    tx.executeWithoutResult(s->{fence(claim);complete(candidate,order);fence(claim);});
   }
   long nextCandidate=candidates.size()<20?0:n(candidates.get(candidates.size()-1),"id");long next=cursor;
   tx.executeWithoutResult(s->{fence(claim);db.candidateCursor(nextCandidate);require(db.finish(claim.owner(),claim.version(),next,null));});
   return Map.of("state","PAGE_COMPLETED","importedOrVerified",page.items().size(),"nextAfter",cursor,"candidateCount",candidates.size(),"simulationOnly",true);
  }catch(RuntimeException e){long original=claim.cursor();String detail=e instanceof BusinessException?e.getMessage():"依赖或事实核验失败，保留原游标重放";try{tx.executeWithoutResult(s->{if(db.fence(claim.owner(),claim.version())!=null)require(db.finish(claim.owner(),claim.version(),original,detail));});}catch(RuntimeException ignored){/* 已失去租约时不能覆盖新执行者，下一领取从持久游标恢复。 */}throw e;}
 }
 /** 每页必须严格递增且枚举、金额和原业务号匹配；不能信任异常依赖响应。 */
 private void validatePage(SettlementRemote.Page p,long after){if(p==null||p.items()==null||p.items().size()>100)throw conflict("资金分页无效");long prev=after;for(var f:p.items()){if(f.factId()<=prev||f.amountCents()<=0||f.occurredAt()==null||!"CNY".equals(f.currency())||!Set.of("PAYMENT_SUCCEEDED","REFUND_SUCCEEDED").contains(f.kind()))throw conflict("资金事实字段无效");SettlementRemote.safe(f.businessNo());SettlementRemote.safe(f.paymentNo());SettlementRemote.safe(f.orderNo());if("PAYMENT_SUCCEEDED".equals(f.kind())&&!f.businessNo().equals(f.paymentNo()))throw conflict("原支付号不匹配");prev=f.factId();}if(p.nextAfter()!=prev||p.hasMore()&&p.items().isEmpty())throw conflict("资金游标无效");}
 /** 原事实重复必须全参一致；同号异参立即隔离该轮，不修改既有账本。 */
 private void verifyFact(Map<String,Object> prior,SettlementRemote.Fact f){if(!str(prior,"payment_no").equals(f.paymentNo())||!str(prior,"order_no").equals(f.orderNo())||n(prior,"amount_cents")!=f.amountCents()||!str(prior,"currency").equals(f.currency())||!instant(prior.get("occurred_at")).equals(f.occurredAt()))throw conflict("同号资金事实冲突");}
 /** 退款先到且尚无本金时不记账，下次从零重扫恢复；不猜测费率或商家。 */
 private void apply(SettlementRemote.Fact f,SettlementRemote.Order order){
  var duplicate=db.fact(f.kind(),f.businessNo());if(duplicate!=null){verifyFact(duplicate,f);return;}
  String merchant=order.merchantId();db.account(merchant);db.lockAccount(merchant);var item=db.lockItem(f.paymentNo());
  if("PAYMENT_SUCCEEDED".equals(f.kind())){
   if(item!=null)throw conflict("支付单据与原事实不一致");long fee=SettlementRules.fee(f.amountCents(),bps);db.insertFact(f,merchant);db.insertItem(f,merchant,bps,days,fee);db.ledger("P:"+f.businessNo(),f.paymentNo(),merchant,"PAYMENT",f.amountCents(),fee,f.amountCents()-fee,0,null);
  }else{
   if(item==null)return;
   if(!merchant.equals(str(item,"merchant_id"))||!f.orderNo().equals(str(item,"order_no")))throw conflict("退款归属不匹配");
   long remaining=Math.subtractExact(n(item,"paid_cents"),Math.addExact(n(item,"refund_cents"),f.amountCents()));if(remaining<0)throw conflict("退款超过支付本金");
   long fee=SettlementRules.fee(remaining,(int)n(item,"fee_bps"));long feeDelta=fee-n(item,"fee_cents");long net=Math.addExact(f.amountCents(),feeDelta);boolean completed="SIMULATED_COMPLETED".equals(str(item,"status"));
   db.insertFact(f,merchant);require(db.refund(f.paymentNo(),f.amountCents(),fee));if(completed)require(db.debt(merchant,net));
   db.ledger("R:"+f.businessNo(),f.paymentNo(),merchant,completed?"POST_SETTLEMENT_REFUND":"REFUND",-f.amountCents(),feeDelta,completed?0:-net,completed?-net:0,null);
  }
 }
 /** 最新订单归属与冻结快照同时满足才模拟结算；本地退款与结算共用商家/支付锁，远端并发退款后续追加冲正。 */
 private void complete(Map<String,Object> candidate,SettlementRemote.Order order){
  String merchant=str(candidate,"merchant_id");if(!merchant.equals(order.merchantId()))throw conflict("订单商家归属变化");
  var account=db.lockAccount(merchant);var item=db.lockItem(str(candidate,"payment_no"));if(item==null||!"FROZEN".equals(str(item,"status")))return;
  if(!SettlementRules.eligible(order.status(),order.receivedAt(),order.hasActiveAftersale(),(int)n(item,"freeze_days"),Instant.now()))return;
  long net=n(item,"paid_cents")-n(item,"refund_cents")-n(item,"fee_cents");var a=SettlementRules.allocate(net,n(account,"debt_cents"));String batch="st_"+UUID.randomUUID().toString().replace("-","");
  require(db.settle(str(item,"payment_no"),batch));db.batch(batch,merchant,str(item,"payment_no"),net,a.payout(),a.offset());require(db.debt(merchant,-a.offset()));db.ledger("S:"+str(item,"payment_no"),str(item,"payment_no"),merchant,"SIMULATED_COMPLETED",0,0,-net,net,batch);
 }
 private void validateOrder(SettlementRemote.Order order,String no){if(order==null||!no.equals(order.orderNo()))throw conflict("订单事实不匹配");SettlementRemote.safe(order.merchantId());}
 private void fence(Claim c){if(db.fence(c.owner(),c.version())==null)throw new BusinessException(409,"LEASE_LOST","结算任务租约已失效");}
 /**
  * 本地单据、导入事实及追加账本逐笔核对，结果同事务留痕；不代表渠道或全局快照对账。
  * 控制器通过Spring代理调用本public事务方法，先锁结算扫描任务，避免本库导入与核对交错。
  * 仅查询/写入结算库，不调用remote；核对记录写入失败则回滚，不留下假成功。
  */
 @Transactional(rollbackFor = Exception.class)
 public Map<String,Object> reconcile(){
  db.ensureTask();
  db.lockTask();
  var diffs=db.differences();
  String run="sr_"+UUID.randomUUID().toString().replace("-","");
  try{db.reconciliation(run,diffs.isEmpty()?"PASS":"DIFFERENCE",json.writeValueAsString(diffs));}
  catch(java.io.IOException e){throw new IllegalStateException(e);}
  return Map.of("runNo",run,"result",diffs.isEmpty()?"PASS":"DIFFERENCE","differences",diffs,"scope","LOCAL_IMPORTED_FACTS");
 }
 /** 验签后查询最新账户/角色/商家归属；平台跨商家查询明确走平台入口。 */
 public String authorize(Jwt jwt,boolean admin,String merchant){if(jwt==null)throw new BusinessException(401,"UNAUTHORIZED","需要有效登录");AuthContext a=remote.context(jwt.getSubject());Object v=jwt.getClaims().get("token_version");if(!jwt.getSubject().equals(a.userId())||!"ACTIVE".equals(a.status())||!(v instanceof Number n)||n.longValue()!=a.tokenVersion())throw new BusinessException(401,"TOKEN_REVOKED","登录状态已失效");if(a.roles()==null||admin&&!a.roles().contains("PLATFORM_ADMIN"))throw new BusinessException(403,"FORBIDDEN","缺少平台权限");if(!admin){SettlementRemote.safe(merchant);if(a.merchantIds()==null||!a.merchantIds().contains(merchant)||!Set.of("ADMIN","OPERATOR").contains(a.merchantRoles().getOrDefault(merchant,"")))throw new BusinessException(403,"FORBIDDEN","无商家结算权限");}return a.userId();}
 /** 平台操作审计独立提交；控制器先在事务外完成远程授权，再经本Bean代理调用，不包住后续结算RPC。 */
 @Transactional(rollbackFor = Exception.class)
 public void audit(String actor,String action){db.audit(actor,action);}
 /** 表名仅接受固定页面映射，商家查询始终绑定所属商家，不接受任意资源ID过滤。 */
 public Object query(String kind,String merchant,int page,int size){if(page<1||size<1||size>100)throw new BusinessException(400,"INVALID_PAGE","分页无效");if("summary".equals(kind))return db.summary(merchant);if("tasks".equals(kind)){if(merchant!=null)throw new BusinessException(403,"FORBIDDEN","平台任务不可商家访问");return Map.of("items",db.tasks(),"page",1,"pageSize",size,"hasMore",false);}String table=switch(kind){case "ledger"->"settlement_ledger";case "items"->"settlement_item";case "batches","statements"->"settlement_batch";case "reconciliations"->"settlement_reconciliation";case "audit"->"settlement_audit";default->throw new BusinessException(404,"NOT_FOUND","结算视图不存在");};if(merchant!=null&&(kind.equals("reconciliations")||kind.equals("audit")))throw new BusinessException(403,"FORBIDDEN","平台视图不可商家访问");var rows="statements".equals(kind)?db.statements(merchant,size,Math.multiplyExact((long)page-1,size)):db.page(table,merchant,size,Math.multiplyExact((long)page-1,size));return Map.of("items",rows,"page",page,"pageSize",size,"hasMore",rows.size()==size);}
 private static long n(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}private static String str(Map<String,Object> r,String k){return String.valueOf(r.get(k));}
 private static Instant instant(Object o){if(o instanceof java.sql.Timestamp t)return t.toInstant();if(o instanceof java.time.LocalDateTime t)return t.toInstant(ZoneOffset.UTC);if(o instanceof Instant i)return i;throw conflict("资金时间格式无效");}
 private static void require(int rows){if(rows!=1)throw conflict("结算条件更新冲突");}private static BusinessException conflict(String m){return new BusinessException(409,"SETTLEMENT_FACT_CONFLICT",m);}
}
