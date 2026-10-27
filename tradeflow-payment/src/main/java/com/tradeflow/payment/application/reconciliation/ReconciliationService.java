package com.tradeflow.payment.application.reconciliation;

import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.ReconciliationContracts.BillRequest;
import com.tradeflow.payment.application.PaymentService;
import com.tradeflow.payment.infrastructure.reconciliation.ReconciliationStore;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** 平台受控对账用例；每个公共入口先在事务外验证最新平台身份，所有命令保存原因及原号。 */
@Service
public class ReconciliationService {
 public record CreateRun(Instant start,Instant end,Instant cutoff,String reason){}
 public record Reason(String reason){}
 public record Check(String type,String businessNo,String reason){}
 private final PaymentService payments;private final ReconciliationStore db;
 public ReconciliationService(PaymentService payments,ReconciliationStore db){this.payments=payments;this.db=db;}
 /** UTC半开窗口最多31天且留足五分钟；只创建恢复任务，不在HTTP线程下载或修复资金。 */
 public Map<String,Object> create(Jwt jwt,String key,CreateRun body){payments.authorizePlatform(jwt);if(body==null||body.start()==null||body.end()==null||body.cutoff()==null||body.start().getNano()%1000000!=0||body.end().getNano()%1000000!=0||body.cutoff().getNano()%1000000!=0||body.start().isBefore(Instant.parse("1000-01-01T00:00:00Z"))||body.cutoff().isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))||!body.start().isBefore(body.end())||Duration.between(body.start(),body.end()).compareTo(Duration.ofDays(31))>0||Duration.between(body.end(),body.cutoff()).compareTo(Duration.ofSeconds(300))<0||body.cutoff().isAfter(Instant.now()))throw error("账期或宽限窗口不合法");reason(body.reason());String actor=actor(jwt);String no=db.command(actor,"CREATE_RUN",key,ReconciliationEngine.hash(db.encode(body)),db.encode(body),()->{String n=ReconciliationEngine.no("rr_");db.insert("run",n,"READY",new ReconciliationEngine.Run(new BillRequest(n,body.start(),body.end(),body.cutoff(),"CNY"),null,"FREEZE",0,0,"",0,actor,body.reason()));return n;});return db.find("run",no);}
 /** 查询仍验证实时账号；分页有上限，避免返回无限账单证据。 */
 public List<Map<String,Object>> runs(Jwt jwt,int page,int size){payments.authorizePlatform(jwt);return db.list("run",page,size);}
 /** 只读原批次快照和持久检查点，不触发通道查询。 */
 public Map<String,Object> run(Jwt jwt,String no){payments.authorizePlatform(jwt);Map<String,Object> result=new LinkedHashMap<>(db.find("run",safe(no)));result.put("statistics",db.runStatistics(no));return result;}
 /** 人工恢复保留原运行号和原快照；不完整快照必须另建新运行。 */
 public Map<String,Object> retryRun(Jwt jwt,String no,String key,Reason body){payments.authorizePlatform(jwt);return retry(jwt,"run",no,key,body);}
 /** 平台查看稳定指纹差异，已解决重现会回到OPEN并保留每轮观察。 */
 public List<Map<String,Object>> issues(Jwt jwt,int page,int size){payments.authorizePlatform(jwt);return db.list("issue",page,size);}
 /** 读取差异详情及最近动作关联。 */
 public Map<String,Object> issue(Jwt jwt,String no){payments.authorizePlatform(jwt);return db.find("issue",safe(no));}
 /** 受控修复仅保存动作，后台实时核验后应用；同一主体同键永远返回原动作。 */
 public Map<String,Object> repair(Jwt jwt,String no,String key,Reason body){payments.authorizePlatform(jwt);requireReason(body);safe(no);String actor=actor(jwt);String action=db.command(actor,"REPAIR:"+no,key,ReconciliationEngine.hash(db.encode(body)),db.encode(body),()->{var issue=db.lockIssue(no);String existing=db.activeActionNo(no);if(existing!=null)return existing;String n=ReconciliationEngine.no("ra_");db.insert("action",n,"READY",new ReconciliationEngine.Action(issue.get("transaction_type").toString(),issue.get("business_no").toString(),no,"QUERY",actor,body.reason(),null,null));db.linkAction(n,no);db.attachAction(no,n);return n;});return db.find("action",action);}
 /** 定向核验与受控恢复复用原业务号，实时查询后仅安全应用既有事实，不重新提交支付退款；结果及复查证据均有持久动作。 */
 public Map<String,Object> check(Jwt jwt,String key,Check body){payments.authorizePlatform(jwt);if(body==null||!Set.of("PAYMENT","REFUND").contains(body.type()==null?"":body.type()))throw error("交易类型不合法");if(body.businessNo()==null||!body.businessNo().matches("[A-Za-z0-9_-]{1,64}"))throw error("业务编号不合法");reason(body.reason());String actor=actor(jwt);String action=db.command(actor,"CHECK",key,ReconciliationEngine.hash(db.encode(body)),db.encode(body),()->{String n=ReconciliationEngine.no("ra_");db.insert("action",n,"READY",new ReconciliationEngine.Action(body.type(),body.businessNo(),null,"QUERY",actor,body.reason(),null,null));return n;});return db.find("action",action);}
 /** 读取动作原号、阶段、租约和最终复查结论，不执行网络调用。 */
 public Map<String,Object> action(Jwt jwt,String no){payments.authorizePlatform(jwt);return db.find("action",safe(no));}
 /** 人工恢复连续失败或未决动作时保留原动作号，下一步仍强制实时查询。 */
 public Map<String,Object> retryAction(Jwt jwt,String no,String key,Reason body){payments.authorizePlatform(jwt);return retry(jwt,"action",no,key,body);}
 /** 平台读取原运行每轮独立观察，不通过最新差异证据替代历史证据。 */
 public List<Map<String,Object>> runObservations(Jwt jwt,String no,int page,int size){payments.authorizePlatform(jwt);db.find("run",safe(no));return db.observations("run_no",no,page,size);}
 /** 平台读取同一稳定指纹跨运行的不可变观察，支持已解决后重现审计。 */
 public List<Map<String,Object>> issueObservations(Jwt jwt,String no,int page,int size){payments.authorizePlatform(jwt);db.find("issue",safe(no));return db.observations("issue_no",no,page,size);}
 /** 重试命令本身独立幂等并保留审计原因；只允许原任务从人工状态恢复。 */
 private Map<String,Object> retry(Jwt jwt,String kind,String no,String key,Reason body){safe(no);requireReason(body);String result=db.command(actor(jwt),"RETRY_"+kind+":"+no,key,ReconciliationEngine.hash(db.encode(body)),db.encode(body),()->db.retry(kind,no));return db.find(kind,result);}
 private void requireReason(Reason body){if(body==null)throw error("必须填写原因");reason(body.reason());}
 private void reason(String reason){if(reason==null||reason.isBlank()||reason.length()>500)throw error("原因长度须为1至500字符");}
 private String actor(Jwt jwt){return safe(jwt.getSubject());}
 private String safe(String no){if(no==null||!no.matches("[A-Za-z0-9_:-]{1,64}"))throw error("业务编号不合法");return no;}
 private BusinessException error(String message){return new BusinessException(400,"INVALID_ARGUMENT",message);}
}
