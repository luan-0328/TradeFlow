package com.tradeflow.order.application;
import com.tradeflow.common.BusinessException;
import com.tradeflow.order.infrastructure.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
/** 平台恢复用例只重启原收据义务，身份查询在本地事务外，任务与审计同事务提交。 */
@Service public class ReturnStockRecoveryService {
 private final EntitlementMapper db;private final FulfillmentMapper auth;private final OrderMapper orders;private final OrderAuthority authority;private final OrderTransactions tx;
 public ReturnStockRecoveryService(EntitlementMapper db,FulfillmentMapper auth,OrderMapper orders,OrderAuthority authority,OrderTransactions tx){this.db=db;this.auth=auth;this.orders=orders;this.authority=authority;this.tx=tx;}
 /** 最新平台身份验证后只读分页，不携带敏感地址。 */
 public List<Map<String,Object>> list(Jwt jwt,int page,int size){authority.authenticate(jwt,true);if(page<1||size<1||size>100)throw new BusinessException(400,"INVALID_PAGE","分页参数无效");return db.list(size,(long)(page-1)*size);}
 /** 重启先锁任务再锁订单，与工作者一致；递增围栏拒绝旧执行者回写，原因记入审计。 */
 public Map<String,String> retry(Jwt jwt,String source,String reason){authority.authenticate(jwt,true);FulfillmentService.safeSource(source);if(reason==null||reason.isBlank()||reason.length()>500)throw new BusinessException(400,"REASON_REQUIRED","必须填写恢复原因");return tx.write(()->{if(db.restart(source)!=1)throw new BusinessException(409,"TASK_NOT_MANUAL","任务不处于人工恢复状态");var a=auth.auth(source);orders.lockOrder(a.orderNo());orders.audit("PLATFORM",jwt.getSubject(),"RESTART_RETURN_STOCK",a.orderNo(),source,source,reason,"{\"originalReceipt\":true}");return Map.of("sourceNo",source,"status","READY");});}
}
