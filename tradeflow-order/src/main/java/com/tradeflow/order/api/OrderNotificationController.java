package com.tradeflow.order.api;

import com.tradeflow.common.*;
import com.tradeflow.order.api.OrderModels.*;
import com.tradeflow.order.infrastructure.*;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 平台通知恢复入口：最新权限RPC在事务外，人工重启同任务与审计在同一短事务，禁止重发新业务号。 */
@RestController @Validated
public class OrderNotificationController {
 private final OrderAuthority authority;private final OrderNotificationMapper notifications;private final OrderMapper orders;private final TransactionTemplate tx;
 public OrderNotificationController(OrderAuthority authority,OrderNotificationMapper notifications,OrderMapper orders,PlatformTransactionManager manager){this.authority=authority;this.notifications=notifications;this.orders=orders;tx=new TransactionTemplate(manager);}
 /** 完整后端分页，不截断为固定100条；只依据真实人工状态提示RETRY，不替代写入重核。 */
 @GetMapping("/api/v1/admin/order-notifications")
 public ApiResponse<Page<Map<String,Object>>> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest request){
  requirePlatform(jwt);if(page<1||pageSize<1||pageSize>100)throw new BusinessException(400,"INVALID_PAGE","通知任务分页无效");
  var rows=notifications.page(pageSize,Math.multiplyExact((long)page-1,pageSize)).stream().map(original->{var copy=new LinkedHashMap<String,Object>(original);copy.put("allowedActions","MANUAL_REVIEW".equals(copy.get("state"))?List.of("RETRY"):List.of());return (Map<String,Object>)copy;}).toList();
  return WebResponses.ok(request,new Page<>(rows,page,pageSize,notifications.count()));
 }
 /** 未知HTTP结果只能查原任务；再次调用若已READY返回409，不会生成另一条通知或重复审计。 */
 @PostMapping("/api/v1/admin/order-notifications/{id}/retry")
 @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 public ApiResponse<Map<String,Object>> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}")String id,@Valid @RequestBody Reason reason,HttpServletRequest request){
  requirePlatform(jwt);long key;try{key=Long.parseLong(id);}catch(NumberFormatException e){throw new BusinessException(400,"INVALID_TASK_ID","通知任务编号无效");}
  if(reason==null||reason.reason()==null||reason.reason().isBlank()||reason.reason().length()>255)throw new BusinessException(400,"REASON_REQUIRED","重试原因必填且不超过255字");
  Map<String,Object> result=tx.execute(s->{var task=notifications.lock(key);if(task==null)throw new BusinessException(404,"NOTIFICATION_TASK_NOT_FOUND","通知任务不存在");if(!"MANUAL_REVIEW".equals(task.get("state")))throw new BusinessException(409,"INVALID_STATE","仅人工处理状态可重启，请刷新原任务");
   if(notifications.restart(key)!=1)throw new BusinessException(409,"VERSION_CONFLICT","通知任务已变化");
   // 审计失败必须回滚重启；仅记录任务和原订单，不记录通知正文、服务凭据或隐私资料。
   orders.audit("PLATFORM",jwt.getSubject(),"RESTART_ORDER_NOTIFICATION",String.valueOf(task.get("order_no")),(String)request.getAttribute("requestId"),(String)request.getAttribute("traceId"),reason.reason().trim(),"{\"notificationTaskId\":\""+id+"\"}");
   return Map.of("id",id,"businessKey",task.get("business_key"),"state","READY","allowedActions",List.of());});
  return WebResponses.ok(request,result);
 }
 private void requirePlatform(Jwt jwt){if(jwt==null)throw new BusinessException(401,"UNAUTHORIZED","需要有效登录");authority.authenticate(jwt,true);}
}
