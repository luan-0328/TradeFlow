package com.tradeflow.order.application;
import com.tradeflow.common.*;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.order.infrastructure.*;
import com.tradeflow.order.infrastructure.client.ProductClient;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** 验收入库独立恢复：不等待资金成功，领取提交后RPC，回写必须通过租约围栏。 */
@Component public class ReturnStockWorker {
 private final EntitlementMapper db;private final EntitlementService service;private final FulfillmentMapper auth;private final OrderMapper orders;private final OrderTransactions tx;private final ProductClient product;private final String token;
 public ReturnStockWorker(EntitlementMapper db,EntitlementService service,FulfillmentMapper auth,OrderMapper orders,OrderTransactions tx,ProductClient product,Environment env){this.db=db;this.service=service;this.auth=auth;this.orders=orders;this.tx=tx;this.product=product;token=env.getRequiredProperty("tradeflow.internal.order-token");}
 /** 每轮最多一笔、最多30次自动尝试；RPC成功但本地失败时用原收据重试，不重复库存。 */
 @Scheduled(fixedDelay=1000,initialDelay=7000) public void recover(){var task=tx.write(()->{var t=db.due();if(t==null)return null;db.claim((String)t.get("source_no"));t.put("claim_version",n(t,"claim_version")+1);return t;});if(task==null)return;String source=(String)task.get("source_no");long claim=n(task,"claim_version");if(n(task,"attempt_count")>=30){finish(source,claim,"MANUAL_REVIEW","RECOVERY_BUDGET_EXHAUSTED",false);return;}try{var receipt=service.receipt("RET:"+source);var response=product.returnRestock("order",token,new ReturnRestock(receipt.receiptNo(),source,receipt.orderNo(),receipt.reservationNo()));if(response==null||!"OK".equals(response.code())||response.data()==null)throw FulfillmentService.fail("INVALID_REMOTE_RESPONSE");var v=response.data();if(!receipt.receiptNo().equals(v.receiptNo())||!source.equals(v.sourceNo())||!receipt.orderNo().equals(v.orderNo())||!receipt.reservationNo().equals(v.reservationNo())||!"APPLIED".equals(v.status()))throw FulfillmentService.fail("RESTOCK_BINDING_CONFLICT");finish(source,claim,"SUCCEEDED",null,true);}catch(Exception e){String code=e instanceof FeignPolicy.RemoteFailure f?f.code():e instanceof BusinessException b?b.code():"RECOVERY_DEPENDENCY_ERROR";finish(source,claim,"RESTOCK_BINDING_CONFLICT".equals(code)?"MANUAL_REVIEW":"READY",code,false);}}
 /** 任务→订单→授权固定锁序；资金结果不取库存任务锁，避免任务互锁；同订单锁保证完成合取可见。 */
 private void finish(String source,long claim,String state,String error,boolean applied){tx.write(()->{if(db.fence(source,claim)==null)return null;if(applied){var a=auth.auth(source);orders.lockOrder(a.orderNo());auth.lockAuth(source);db.applied(source);db.complete(source);}if(db.finish(source,claim,state,error)!=1)throw new IllegalStateException("退货入库围栏失效");return null;});}
 private long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
}
