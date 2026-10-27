package com.tradeflow.order.application;
import com.tradeflow.common.*;
import com.tradeflow.contracts.*;
import com.tradeflow.order.infrastructure.*;
import com.tradeflow.order.infrastructure.client.RefundClient;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
/** 平台受控恢复保留来源与审计，不通过直接改资金状态假造恢复。 */
@Service public class RefundRecoveryService {
 private final FulfillmentMapper db;private final OrderMapper orders;private final OrderAuthority authority;private final OrderTransactions tx;private final RefundClient payment;private final String token;
 public RefundRecoveryService(FulfillmentMapper db,OrderMapper orders,OrderAuthority authority,OrderTransactions tx,RefundClient payment,Environment env){this.db=db;this.orders=orders;this.authority=authority;this.tx=tx;this.payment=payment;token=env.getRequiredProperty("tradeflow.internal.order-token");}
 /** 平台身份每次查最新权限，分页只读任务信息。 */
 public List<Map<String,Object>> list(Jwt jwt,int page,int size){authority.authenticate(jwt,true);if(page<1||size<1||size>100)throw FulfillmentService.fail("INVALID_PAGE");return db.refundTasks(size,(long)(page-1)*size);}
 /** 原号重启网络恢复；只有确认旧执行FAILED才允许新号，UNKNOWN禁止换号。 */
 public Map<String,String> retry(Jwt jwt,String source,String reason,boolean newAttempt){authority.authenticate(jwt,true);FulfillmentService.safeSource(source);if(reason==null||reason.isBlank()||reason.length()>500)throw FulfillmentService.fail("REASON_REQUIRED");var snapshot=db.refundTask(source);if(snapshot==null||!"MANUAL_REVIEW".equals(snapshot.get("state")))throw FulfillmentService.fail("TASK_NOT_MANUAL");
  String previous=snapshot.get("refund_no")==null?"rfd_"+TradingContracts.sha256(source).substring(0,32):(String)snapshot.get("refund_no");
  if(newAttempt){if(!source.startsWith("CLOSE:"))throw FulfillmentService.fail("SOURCE_FORBIDDEN");var response=payment.query(previous,"order",token);if(response==null||!"OK".equals(response.code())||response.data()==null||!previous.equals(response.data().refundNo())||!source.equals(response.data().sourceNo())||!"FAILED".equals(response.data().status()))throw FulfillmentService.fail("REFUND_NOT_TERMINAL_FAILED");}
  String next=newAttempt?"rfd_"+UUID.randomUUID().toString().replace("-",""):null;
  return tx.write(()->{db.lockRefundTask(source);var current=db.refundTask(source);if(current==null||!"MANUAL_REVIEW".equals(current.get("state"))||!Objects.equals(snapshot.get("claim_version"),current.get("claim_version"))||!Objects.equals(snapshot.get("refund_no"),current.get("refund_no")))throw FulfillmentService.fail("TASK_CHANGED");var a=db.auth(source);orders.lockOrder(a.orderNo());a=db.lockAuth(source);if(newAttempt&&!"EXECUTING".equals(a.state()))throw FulfillmentService.fail("AUTHORIZATION_NOT_EXECUTING");if(db.restartRefund(source,next)!=1)throw FulfillmentService.fail("TASK_CHANGED");orders.audit("PLATFORM",jwt.getSubject(),"RESTART_REFUND_FLOW",a.orderNo(),source,source,reason,newAttempt?"{\"newAttempt\":true}":"{\"newAttempt\":false}");return Map.of("sourceNo",source,"status","READY");});
 }
}
