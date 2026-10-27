package com.tradeflow.aftersale.application;

import com.tradeflow.aftersale.domain.Aftersale;
import com.tradeflow.aftersale.infrastructure.*;
import com.tradeflow.aftersale.infrastructure.client.OrderClient;
import com.tradeflow.contracts.RefundContracts.*;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static com.tradeflow.aftersale.application.AftersaleService.*;

/** 退货用户与商家用例；主单锁仲裁物流、超时和一次验收，实物事实由持久任务交接订单。 */
@Service
public class ReturnService {
 private final AftersaleStore db;private final Authority authority;private final TransactionTemplate tx;private final OrderClient orders;private final EvidenceService evidence;private final AftersaleService sales;private final String token;
 public ReturnService(AftersaleStore db,Authority authority,TransactionTemplate tx,OrderClient orders,EvidenceService evidence,AftersaleService sales,Environment env){this.db=db;this.authority=authority;this.tx=tx;this.orders=orders;this.evidence=evidence;this.sales=sales;token=env.getRequiredProperty("tradeflow.internal.aftersale-token");}
 public record PreviewInput(String orderNo,String type,List<Allocation> allocations){}
 public record Shipment(String carrier,String trackingNo){}
 public record Inspection(Boolean accepted,String reason,List<String> evidenceKeys,List<ReturnDisposition> dispositions){
  public Inspection(Boolean accepted,String reason,List<String> evidenceKeys){this(accepted,reason,evidenceKeys,List.of());}
 }
 private record ReturnMeta(Instant deadline,String receipt){}
 public record Materials(Instant returnDeadline,String receiptNo,List<Allocation> allocations,List<Map<String,Object>> evidence,Map<String,Object> shipment,Map<String,Object> inspection){}
 /** 本人只读预览逐件权益；RPC在事务外，预览不预占，实际创建仍由订单重新仲裁。 */
 public Preview preview(Jwt jwt,PreviewInput input){var user=authority.check(jwt,"user");if(input==null||input.orderNo()==null||!input.orderNo().matches("ord_[a-f0-9]{32}")||input.type()==null||!Set.of("UNSHIPPED_FULL","REFUND_ONLY","RETURN_REFUND").contains(input.type()))throw error(400,"INVALID_PREVIEW","订单或类型无效");var allocations=normalize(input.allocations());var r=orders.preview("aftersale",token,new PreviewRequest(input.orderNo(),user.userId(),input.type(),allocations));if(r==null||!"OK".equals(r.code())||r.data()==null||!input.orderNo().equals(r.data().orderNo())||!user.userId().equals(r.data().userId())||!input.type().equals(r.data().type()))throw error(503,"ORDER_UNAVAILABLE","无法确认订单预览");return r.data();}
 /** 保留原详情兼容性，额外提供分配、证据和物流验收资料，读取前核验最新身份和归属。 */
 public Materials materials(Jwt jwt,String no,boolean merchant){sales.detail(jwt,no,merchant);var meta=db.jdbc().queryForObject("SELECT return_deadline,return_receipt_no FROM aftersale WHERE aftersale_no=?",(rs,n)->new ReturnMeta(rs.getTimestamp("return_deadline")==null?null:rs.getTimestamp("return_deadline").toInstant(),rs.getString("return_receipt_no")),no);var shipment=db.jdbc().queryForList("SELECT carrier,tracking_no AS trackingNo,created_at AS createdAt FROM aftersale_return_shipment WHERE aftersale_no=?",no);var inspection=db.jdbc().queryForList("SELECT accepted,reason,created_at AS createdAt FROM aftersale_return_inspection WHERE aftersale_no=?",no);if(!inspection.isEmpty())inspection.get(0).put("dispositions",db.dispositions(no));return new Materials(meta.deadline(),meta.receipt(),db.allocations(no),db.jdbc().queryForList("SELECT object_key AS objectKey,purpose FROM aftersale_evidence WHERE aftersale_no=? ORDER BY purpose,object_key",no),shipment.isEmpty()?null:shipment.get(0),inspection.isEmpty()?null:inspection.get(0));}
 /** 退货物流只登记一次；与超时在同主单锁竞争，截止后即使扫描尚未执行也拒绝寄出。 */
 public Aftersale ship(Jwt jwt,String no,String key,Shipment input){var user=authority.check(jwt,"user");if(input==null||input.carrier()==null||input.carrier().isBlank()||input.carrier().length()>80||input.trackingNo()==null||!input.trackingNo().matches("[A-Za-z0-9_-]{1,80}"))throw error(400,"INVALID_RETURN_SHIPMENT","承运商或退货单号无效");var body=new Shipment(input.carrier().strip(),input.trackingNo());return tx.execute(s->{var a=owned(no,user.userId(),false);if(sales.replay(user.userId(),"RETURN_SHIP:"+no,key,body,no)!=null)return a;if(!"RETURN_REFUND".equals(a.type())||!"WAITING_RETURN".equals(a.status()))throw error(409,"RETURN_STATE_CONFLICT","当前不可填写退货物流");if(db.jdbc().update("UPDATE aftersale SET status='RETURN_IN_TRANSIT' WHERE aftersale_no=? AND status='WAITING_RETURN' AND return_deadline>CURRENT_TIMESTAMP(6)",no)!=1)throw error(409,"RETURN_DEADLINE_EXPIRED","填写退货物流已超时");db.jdbc().update("INSERT INTO aftersale_return_shipment(aftersale_no,carrier,tracking_no,actor_id) VALUES(?,?,?,?)",no,body.carrier(),body.trackingNo(),user.userId());audit(user.userId(),no,"RETURN_SHIP","用户登记退货物流");return db.get(no,false);});}
 /** 认可退款与库存可售分离；逐件去向、验收及恢复任务同事务冻结，拒收仍需证据。 */
 public Aftersale inspect(Jwt jwt,String no,String key,Inspection input) {
  var user=authority.check(jwt,"merchant");
  if(input==null||input.accepted()==null) throw error(400,"INVALID_INSPECTION","accepted必填");
  reason(input.reason());var keys=normalizeEvidence(input.evidenceKeys());
  if(!input.accepted()&&keys.isEmpty()) throw error(400,"INSPECTION_EVIDENCE_REQUIRED","拒收必须提供图片证据并记录人工处置原因");
  if(!input.accepted()&&input.dispositions()!=null&&!input.dispositions().isEmpty())
   throw error(400,"INVALID_RETURN_DISPOSITION","拒收不能登记已接收商品的库存去向");
  var dispositions=input.accepted()?com.tradeflow.contracts.ReturnDispositionPolicy.canonical(input.dispositions()):List.<ReturnDisposition>of();
  var body=new Inspection(input.accepted(),input.reason().strip(),keys,dispositions);
  return tx.execute(status->{
   var aftersale=owned(no,user.merchantIds().get(0),true);
   if(sales.replay(user.userId(),"INSPECT:"+no,key,body,no)!=null) return aftersale;
   if(!"RETURN_REFUND".equals(aftersale.type())||!"RETURN_IN_TRANSIT".equals(aftersale.status()))
    throw error(409,"RETURN_STATE_CONFLICT","当前不在待验收阶段");
   if(body.accepted()) com.tradeflow.contracts.ReturnDispositionPolicy.match(dispositions,db.allocations(no));
   evidence.bind(no,user.userId(),"INSPECTION",keys);
   db.jdbc().update("INSERT INTO aftersale_return_inspection(aftersale_no,accepted,reason,actor_id) VALUES(?,?,?,?)",
    no,body.accepted(),body.reason(),user.userId());
   for(var disposition:dispositions)
    db.jdbc().update("INSERT INTO aftersale_return_disposition(aftersale_no,order_item_id,unit_no,disposition) VALUES(?,?,?,?)",
     no,disposition.orderItemId(),disposition.unitNo(),disposition.disposition());
   db.jdbc().update("UPDATE aftersale SET status=?,return_receipt_no=? WHERE aftersale_no=?",
    body.accepted()?"RETURN_RECEIVED":"RETURN_REJECTING",body.accepted()?"RET:"+no:null,no);
   audit(user.userId(),no,body.accepted()?"RETURN_ACCEPT":"RETURN_REJECT",body.reason());
   db.task(no,body.accepted()?"RECEIVE":"TERMINATE");
   return db.get(no,false);
  });
 }
 /** 一轮有限扫描；只取消尚未寄出的超时退货，已提交物流永不通过超时推断事实。 */
 @Scheduled(fixedDelay=10000,initialDelay=10000)
 public void expire(){var due=db.jdbc().queryForList("SELECT aftersale_no FROM aftersale WHERE status='WAITING_RETURN' AND return_deadline<=CURRENT_TIMESTAMP(6) ORDER BY return_deadline LIMIT 20",String.class);for(String no:due)tx.executeWithoutResult(s->{var a=db.get(no,true);if(a==null||!"WAITING_RETURN".equals(a.status()))return;if(db.jdbc().update("UPDATE aftersale SET status='CANCELLING',last_error='RETURN_SHIPMENT_TIMEOUT' WHERE aftersale_no=? AND status='WAITING_RETURN' AND return_deadline<=CURRENT_TIMESTAMP(6)",no)==1){audit("SYSTEM",no,"RETURN_TIMEOUT","审核后七天未提交退货单，终止原授权");db.task(no,"TERMINATE");}});}
 /** 所有写入口先按主单锁校验资源归属，隐藏其他用户或商家资源。 */
 private Aftersale owned(String no,String owner,boolean merchant){if(no==null||!no.matches("as_[a-f0-9]{32}"))throw error(404,"AFTERSALE_NOT_FOUND","售后不存在");var a=db.get(no,true);if(a==null||!owner.equals(merchant?a.merchantId():a.userId()))throw error(404,"AFTERSALE_NOT_FOUND","售后不存在");return a;}
 private void audit(String actor,String no,String operation,String why){db.jdbc().update("INSERT INTO aftersale_audit(actor_id,aftersale_no,operation,reason) VALUES(?,?,?,?)",actor,no,operation,why);}
}
