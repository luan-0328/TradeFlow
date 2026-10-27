package com.tradeflow.payment.application.reconciliation;

import com.tradeflow.contracts.ReconciliationContracts;
import com.tradeflow.contracts.ReconciliationContracts.*;
import com.tradeflow.payment.application.reconciliation.ReconciliationFacts.LocalRecord;
import com.tradeflow.payment.infrastructure.reconciliation.ReconciliationStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 分步可恢复对账；先下载完整校验，再双向扫描。远程读取始终在围栏资金事务外。 */
@Component
public class ReconciliationEngine {
 public record Run(BillRequest request,BillManifest manifest,String phase,long cursor,long scanned,String localCursor,long differences,String actor,String reason){}
 public record Action(String type,String businessNo,String issueNo,String phase,String actor,String reason,BillLine before,String outcome,Object evidence,boolean changed){
  public Action(String type,String businessNo,String issueNo,String phase,String actor,String reason,BillLine before,String outcome,Object evidence){this(type,businessNo,issueNo,phase,actor,reason,before,outcome,evidence,false);}
  public Action(String type,String businessNo,String issueNo,String phase,String actor,String reason,BillLine before,String outcome){this(type,businessNo,issueNo,phase,actor,reason,before,outcome,null,false);}
 }
 private final ReconciliationStore db; private final ReconciliationFacts facts; private final String owner=UUID.randomUUID().toString();
 public ReconciliationEngine(ReconciliationStore db,ReconciliationFacts facts){this.db=db;this.facts=facts;}
 public static String no(String prefix){return prefix+UUID.randomUUID().toString().replace("-","");}
 public static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 /** 每次调度最多一个run和一个action小步；过期领取自动恢复，远程依赖失败有界重试。 */
 @Scheduled(fixedDelayString="${tradeflow.reconciliation.poll-ms:2000}")
 public void tick(){work("run");work("action");}
 /** 日扫最近三个完整UTC账期；稳定每日命令保证多实例不重复建批次，迟到项通过新快照观察。 */
 @Scheduled(cron="${tradeflow.reconciliation.daily-cron:0 10 0 * * *}",zone="UTC")
 public void daily(){LocalDate day=LocalDate.now(ZoneOffset.UTC);for(int i=1;i<=3;i++){Instant start=day.minusDays(i).atStartOfDay().toInstant(ZoneOffset.UTC),end=start.plus(Duration.ofDays(1));String key="daily-"+day+"-"+i;db.command("SYSTEM","DAILY",key,hash(key),db.encode(Map.of("reason","每日最近三天迟到重扫","day",day.toString(),"start",start.toString(),"end",end.toString())),()->{String n=no("rr_");BillRequest request=new BillRequest(n,start,end,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),"CNY");db.insert("run",n,"READY",new Run(request,null,"FREEZE",0,0,"",0,"SYSTEM","每日最近三天迟到重扫"));return n;});}}
 /** 捕获异常只写当前有效围栏；旧执行者不提交任何进度或资金变化。 */
 private void work(String kind){Map<String,Object> claim=db.claim(kind,owner);if(claim==null)return;try{if("run".equals(kind))run(claim);else action(claim);}catch(ReconciliationStore.StaleLease ignored){}catch(MalformedBill e){try{db.fenced(kind,claim,()->{db.save(kind,claim,"INCOMPLETE",Map.of("original",db.decode(claim.get("payload").toString(),Run.class),"error",e.getMessage()));return null;});}catch(ReconciliationStore.StaleLease ignored){}}catch(Exception e){db.failed(kind,claim,e);}}
 /** 冻结请求重试原requestNo；manifest先持久化，下载时绝不创建差异或资金动作。 */
 private void run(Map<String,Object> c){Run r=db.decode(c.get("payload").toString(),Run.class);String run=c.get("task_no").toString();
  if("FREEZE".equals(r.phase())){BillManifest m=facts.freezeBill(r.request());manifest(r.request(),m);saveRun(c,new Run(r.request(),m,"DOWNLOAD",0,0,"",0,r.actor(),r.reason()),"READY");return;}
  if("DOWNLOAD".equals(r.phase())){BillManifest current=facts.manifest(r.manifest().snapshotNo());if(!r.manifest().equals(current))throw bad("冻结清单改变");BillPage page=facts.page(r.manifest().snapshotNo(),r.cursor(),100);validatePage(r,page);db.fenced("run",c,()->{for(BillLine line:page.lines())db.stage(run,line.position(),line.transactionType(),line.businessNo(),line);db.save("run",c,"READY",new Run(r.request(),r.manifest(),page.hasMore()?"DOWNLOAD":"VALIDATE",page.nextCursor(),0,"",0,r.actor(),r.reason()));return null;});return;}
  if("VALIDATE".equals(r.phase())){validateComplete(run,r);saveRun(c,new Run(r.request(),r.manifest(),"CHANNEL",0,0,"",0,r.actor(),r.reason()),"READY");return;}
  if("CHANNEL".equals(r.phase())){var rows=db.stagePage(run,r.cursor());if(rows.isEmpty()){saveRun(c,new Run(r.request(),r.manifest(),"LOCAL",0,r.scanned(),"",r.differences(),r.actor(),r.reason()),"READY");return;}long cursor=r.cursor(),count=0;for(var row:rows){BillLine line=db.decode(row.get("payload").toString(),BillLine.class);LocalRecord local=facts.local(line.transactionType(),line.businessNo());String mismatch=compare(local,line);if(mismatch!=null){BillLine live=facts.live(line.transactionType(),line.businessNo());LocalRecord fresh=facts.local(line.transactionType(),line.businessNo());String actual=compare(fresh,live);if(actual!=null){observe(c,r,line.transactionType(),line.businessNo(),actual,line,live,fresh);count++;}}cursor=line.position();}saveRun(c,new Run(r.request(),r.manifest(),"CHANNEL",cursor,r.scanned()+rows.size(),"",r.differences()+count,r.actor(),r.reason()),"READY");return;}
  if("LOCAL".equals(r.phase())){var locals=facts.localPage(r.request().start(),r.request().end(),r.request().cutoff(),r.localCursor(),1);String cursor=r.localCursor();long count=0;for(LocalRecord local:locals){String key=local.transactionType()+":"+local.businessNo();if(key.compareTo(cursor)<=0)throw new IllegalStateException("本地游标未递增");cursor=key;if(!db.staged(run,local.transactionType(),local.businessNo())){BillLine live=facts.live(local.transactionType(),local.businessNo());LocalRecord fresh=facts.local(local.transactionType(),local.businessNo());String mismatch=compare(fresh,live);if(mismatch!=null){observe(c,r,local.transactionType(),local.businessNo(),mismatch,null,live,fresh);count++;}}}saveRun(c,new Run(r.request(),r.manifest(),locals.isEmpty()?"DONE":"LOCAL",r.cursor(),r.scanned()+locals.size(),cursor,r.differences()+count,r.actor(),r.reason()),locals.isEmpty()?"SUCCEEDED":"READY");}
 }
 /** 每条观察短事务独立核验run围栏；稳定指纹与活动动作检查串行在同一issue行锁下。 */
 private void observe(Map<String,Object> claim,Run r,String type,String business,String issueType,BillLine snapshot,BillLine live,LocalRecord local){db.fenced("run",claim,()->{Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("snapshot",snapshot);evidence.put("live",live);evidence.put("local",local);String issue=db.issue(hash(r.manifest().channel()+"|"+type+"|"+business+"|"+issueType),no("ri_"),claim.get("task_no").toString(),type,business,issueType,evidence);if(!db.activeAction(issue)){String action=no("ra_");db.insert("action",action,"READY",new Action(type,business,issue,"QUERY",r.actor(),"完整账单对账："+issueType,null,null));db.linkAction(action,issue);db.originRun(action,claim.get("task_no").toString());db.attachAction(issue,action);db.issueState(issue,"VERIFYING",action,evidence);}return null;});}
 /** 执行动作必须预先持久；QUERY先查实时通道，再围栏内重查本地与应用；RECHECK在后续独立步骤再次远程查询。 */
 private void action(Map<String,Object> c){Action a=db.decode(c.get("payload").toString(),Action.class);String action=c.get("task_no").toString();BillLine live=facts.live(a.type(),a.businessNo());
  db.fenced("action",c,()->{LocalRecord local=facts.local(a.type(),a.businessNo());String mismatch=compare(local,live);boolean changed=a.changed();Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("actionNo",action);evidence.put("live",live);evidence.put("local",local);evidence.put("reason",a.reason());evidence.put("actor",a.actor());evidence.put("checkedAt",Instant.now().toString());
   if("RECHECK".equals(a.phase())){boolean resolved=mismatch==null&&local!=null&&live!=null&&!local.reviewRequired();String state=resolved?"SUCCEEDED":"MANUAL_REVIEW";if(!resolved&&conflict(mismatch))facts.freeze(a.type(),a.businessNo(),mismatch,action);if(a.issueNo()!=null)db.issueState(a.issueNo(),resolved?"RESOLVED":"MANUAL_REVIEW",action,evidence);db.save("action",c,state,new Action(a.type(),a.businessNo(),a.issueNo(),"DONE",a.actor(),a.reason(),live,resolved?"VERIFIED":mismatch,evidence,changed));return null;}
   if(local==null||live==null||local.reviewRequired()||conflict(mismatch)){if(local!=null&&conflict(mismatch))facts.freeze(a.type(),a.businessNo(),mismatch,action);if(a.issueNo()!=null)db.issueState(a.issueNo(),"MANUAL_REVIEW",action,evidence);db.save("action",c,"MANUAL_REVIEW",new Action(a.type(),a.businessNo(),a.issueNo(),"QUERY",a.actor(),a.reason(),live,mismatch==null?"FROZEN":mismatch,evidence,changed));return null;}
   if(mismatch!=null){boolean ledger="LEDGER_MISSING".equals(mismatch);if("LIVE_STALE".equals(mismatch)||!terminal(status(live))||(!ledger&&terminal(local.status()))){if(a.issueNo()!=null)db.issueState(a.issueNo(),"MANUAL_REVIEW",action,evidence);db.save("action",c,"MANUAL_REVIEW",new Action(a.type(),a.businessNo(),a.issueNo(),"QUERY",a.actor(),a.reason(),live,mismatch,evidence,changed));return null;}if(!facts.apply(live,action,ledger)){if(a.issueNo()!=null)db.issueState(a.issueNo(),"MANUAL_REVIEW",action,evidence);db.save("action",c,"MANUAL_REVIEW",new Action(a.type(),a.businessNo(),a.issueNo(),"QUERY",a.actor(),a.reason(),live,"APPLY_REJECTED",evidence,changed));return null;}changed=true;}
   if(a.issueNo()!=null)db.issueState(a.issueNo(),"REPAIRING",action,evidence);db.save("action",c,"READY",new Action(a.type(),a.businessNo(),a.issueNo(),"RECHECK",a.actor(),a.reason(),live,"APPLIED_OR_MATCHED",evidence,changed));return null;});
 }
 /** 对比同时覆盖绑定、终态、唯一流水及适配器额度完整性；查无记录保持人工未决，绝不解释失败。 */
 private String compare(LocalRecord l,BillLine b){if(l==null)return b==null?"NOT_FOUND":"CHANNEL_ONLY";
  if(l.integrityCode()!=null&&!l.integrityCode().isBlank()&&!"OK".equals(l.integrityCode())&&!"LEDGER_MISSING".equals(l.integrityCode()))return "QUOTA_CONFLICT";
  if(l.reviewRequired())return "FROZEN";if(b==null)return terminal(l.status())?"LOCAL_ONLY":"CHANNEL_NOT_FOUND_PENDING";if(!validLine(b))return "FACT_CONFLICT";
  if(!l.transactionType().equals(b.transactionType())||!l.businessNo().equals(b.businessNo())||!Objects.equals(l.paymentNo(),payment(b))||!Objects.equals(l.orderNo(),order(b))||!Objects.equals(l.sourceNo(),source(b)))return "BINDING_CONFLICT";
  if(l.amountCents()!=amount(b)||!Objects.equals(l.currency(),currency(b)))return "AMOUNT_CURRENCY_CONFLICT";
  // 不可变通道编号、对立终态及双方成功时间矛盾不受版本过滤，必须隔离。
  if(l.channelNo()!=null&&!Objects.equals(l.channelNo(),channelNo(b)))return "FACT_CONFLICT";
  if(terminal(l.status())&&terminal(status(b))&&!Objects.equals(l.status(),status(b)))return "TERMINAL_CONFLICT";
  if("SUCCEEDED".equals(status(b))&&l.succeededAt()!=null&&!Objects.equals(l.succeededAt(),succeeded(b)))return "FACT_CONFLICT";
  // 实时查询返回后回调可能已推进本地：旧非终态的空成功时间不是矛盾，不冻结新成功事实。
  if(l.channelVersion()>version(b))return "LIVE_STALE";
  if(terminal(l.status())&&!Objects.equals(l.status(),status(b)))return "TERMINAL_CONFLICT";
  if(l.channelVersion()>0&&l.channelVersion()==version(b)&&(!Objects.equals(l.status(),status(b))||!Objects.equals(l.channelUpdatedAt(),updated(b))))return "FACT_CONFLICT";
  if(!Objects.equals(l.status(),status(b)))return "STATUS_MISMATCH";
  if(!terminal(l.status()))return "PENDING";
  if(!Objects.equals(l.channelNo(),channelNo(b))||("SUCCEEDED".equals(l.status())&&!Objects.equals(l.succeededAt(),succeeded(b))))return "FACT_CONFLICT";
  if("SUCCEEDED".equals(l.status())){if(l.ledgerCount()==0)return "LEDGER_MISSING";if(l.ledgerCount()!=1||l.ledgerAmount()!=l.amountCents())return "LEDGER_CONFLICT";}
  return null;
 }
 private boolean conflict(String type){return type!=null&&(type.endsWith("CONFLICT"));}
 private void saveRun(Map<String,Object> c,Run r,String state){db.fenced("run",c,()->{Run actual=new Run(r.request(),r.manifest(),r.phase(),r.cursor(),r.scanned(),r.localCursor(),db.observationCount(c.get("task_no").toString()),r.actor(),r.reason());db.save("run",c,state,actual);return null;});}
 /** 清单匹配原请求且水位覆盖cutoff；完整性错误一次性进入INCOMPLETE，不生成任何差异。 */
 private void manifest(BillRequest r,BillManifest m){if(m==null||!"COMPLETE".equals(m.status())||m.snapshotNo()==null||m.snapshotNo().isBlank()||!r.requestNo().equals(m.requestNo())||!r.start().equals(m.start())||!r.end().equals(m.end())||!r.cutoff().equals(m.cutoff())||!"CNY".equals(m.currency())||!"SIMULATOR".equals(m.channel())||m.watermark()==null||m.watermark().isBefore(r.cutoff())||m.lineCount()<0||m.lineCount()>100000||m.paymentCount()<0||m.refundCount()<0||m.paymentCount()>100000||m.refundCount()>100000||m.paymentSucceededCents()<0||m.refundSucceededCents()<0||m.paymentSucceededCents()>9007199254740991L||m.refundSucceededCents()>9007199254740991L||m.lineCount()!=m.paymentCount()+m.refundCount()||m.checksum()==null||!m.checksum().matches("[a-f0-9]{64}"))throw bad("清单范围水位计数不完整");}
 /** 页游标必须连续；空尾页只允许真正空快照，拒绝截断、重复或超量页。 */
 private void validatePage(Run r,BillPage p){if(p==null||!r.manifest().snapshotNo().equals(p.snapshotNo())||p.after()!=r.cursor()||p.lines()==null||p.lines().size()>100)throw bad("分页协议不一致");long cursor=r.cursor();Set<String> seen=new HashSet<>();for(BillLine b:p.lines()){if(b==null||b.position()!=++cursor||!validLine(b)||!seen.add(b.transactionType()+":"+b.businessNo())||db.staged(r.request().requestNo(),b.transactionType(),b.businessNo()))throw bad("账单位置或事实非法");}if(cursor!=p.nextCursor()||cursor>r.manifest().lineCount()||p.hasMore()!=(cursor<r.manifest().lineCount())||(p.hasMore()&&p.lines().isEmpty()))throw bad("缺页或截断");}
 /** 全部暂存行统一验摘要、排序、成功金额、类型计数及账期；通过前无业务差异。 */
 private void validateComplete(String run,Run r){
  // 单行前向扫描累计摘要与精确整数汇总；验证结束之前不生成观察、不持有资金锁、不调用远端。
  MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  long[] sums=new long[5];String[] previous={""};
  db.forEachLine(run,text->{BillLine b=db.decode(text,BillLine.class);String key=b.transactionType()+":"+b.businessNo();Instant changed=updated(b);
   if(b.position()!=++sums[0]||sums[0]>100000||key.compareTo(previous[0])<=0||!validLine(b)||!terminal(status(b))||changed==null||changed.isBefore(r.request().start())||!changed.isBefore(r.request().end()))throw bad("账单排序范围或终态错误");
   previous[0]=key;digest.update(ReconciliationContracts.canonical(b));if("PAYMENT".equals(b.transactionType())){sums[1]++;if("SUCCEEDED".equals(status(b)))sums[3]=Math.addExact(sums[3],amount(b));}else{sums[2]++;if("SUCCEEDED".equals(status(b)))sums[4]=Math.addExact(sums[4],amount(b));}
  });
  BillManifest m=r.manifest();if(sums[0]!=m.lineCount()||sums[1]!=m.paymentCount()||sums[2]!=m.refundCount()||sums[3]!=m.paymentSucceededCents()||sums[4]!=m.refundSucceededCents()||!HexFormat.of().formatHex(digest.digest()).equals(m.checksum()))throw bad("完整账单计数金额或摘要错误");
 }

 /** 可信通道事实至少满足类型、业务号、币种、金额、版本和时间前提，关联仍由适配器锁内重验。 */
 private boolean validLine(BillLine b){
  if(b==null||!validNo(b.businessNo()))return false;
  if("PAYMENT".equals(b.transactionType())){if(b.payment()==null||b.refund()!=null||!b.businessNo().equals(b.payment().paymentNo()))return false;}
  else if("REFUND".equals(b.transactionType())){if(b.refund()==null||b.payment()!=null||!b.businessNo().equals(b.refund().refundNo())||!validNo(b.refund().sourceNo())||"CLOSED".equals(b.refund().status()))return false;}else return false;
  if(!Set.of("PENDING","PROCESSING","UNKNOWN","SUCCEEDED","FAILED","CLOSED").contains(status(b)==null?"":status(b))||!"CNY".equals(currency(b))||amount(b)<=0||amount(b)>100000000||updated(b)==null||version(b)<1||!validNo(payment(b))||!validNo(order(b)))return false;
  if("SUCCEEDED".equals(status(b)))return validNo(channelNo(b))&&succeeded(b)!=null&&!succeeded(b).isAfter(updated(b));
  return succeeded(b)==null&&(channelNo(b)==null||validNo(channelNo(b)));
 }
 /** 关联来源允许CLOSE等业务前缀，所有证据标识限制ASCII长度，防止脏事实进入绑定比较。 */
 private boolean validNo(String value){return value!=null&&value.matches("[A-Za-z0-9_:-]{1,64}");}
 public static boolean terminal(String s){return Set.of("SUCCEEDED","FAILED","CLOSED").contains(s==null?"":s);}
 private static String status(BillLine b){return b.payment()!=null?b.payment().status():b.refund().status();}
 private static long amount(BillLine b){return b.payment()!=null?b.payment().amountCents():b.refund().amountCents();}
 private static String currency(BillLine b){return b.payment()!=null?b.payment().currency():b.refund().currency();}
 private static String payment(BillLine b){return b.payment()!=null?b.payment().paymentNo():b.refund().paymentNo();}
 private static String order(BillLine b){return b.payment()!=null?b.payment().orderNo():b.refund().orderNo();}
 private static String source(BillLine b){return b.payment()!=null?null:b.refund().sourceNo();}
 private static String channelNo(BillLine b){return b.payment()!=null?b.payment().channelTransactionNo():b.refund().channelRefundNo();}
 private static long version(BillLine b){return b.payment()!=null?b.payment().channelVersion():b.refund().channelVersion();}
 private static Instant succeeded(BillLine b){return b.payment()!=null?b.payment().succeededAt():b.refund().succeededAt();}
 private static Instant updated(BillLine b){return b.payment()!=null?b.payment().updatedAt():b.refund().updatedAt();}
 private MalformedBill bad(String message){return new MalformedBill(message);}
 private static class MalformedBill extends RuntimeException{MalformedBill(String message){super(message);}}
}
