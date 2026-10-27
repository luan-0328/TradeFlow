package com.tradeflow.settlement;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 商家结算只读与平台受控任务入口；独立验权，命令先落操作审计。 */
@RestController public class SettlementController {
 private final SettlementService service;public SettlementController(SettlementService service){this.service=service;}
 @GetMapping("/api/v1/merchant/settlement/{kind}") public ApiResponse<Object> merchant(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Merchant-Id")String merchant,@PathVariable String kind,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest r){service.authorize(jwt,false,merchant);return WebResponses.ok(r,service.query(kind,merchant,page,pageSize));}
 @GetMapping("/api/v1/admin/settlement/{kind}") public ApiResponse<Object> admin(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@RequestParam(required=false)String merchantId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest r){service.authorize(jwt,true,null);if(merchantId!=null)SettlementRemote.safe(merchantId);return WebResponses.ok(r,service.query(kind,merchantId,page,pageSize));}
 /** 每次只执行一个有界扫描页；重复调用只会重放事实或处理后续页，不重复模拟完成。 */
 @PostMapping("/api/v1/admin/settlement/run") public ApiResponse<Object> run(@AuthenticationPrincipal Jwt jwt,HttpServletRequest r){service.audit(service.authorize(jwt,true,null),"RUN");return WebResponses.ok(r,service.run());}
 @PostMapping("/api/v1/admin/settlement/reconcile") public ApiResponse<Object> reconcile(@AuthenticationPrincipal Jwt jwt,HttpServletRequest r){service.audit(service.authorize(jwt,true,null),"RECONCILE");return WebResponses.ok(r,service.reconcile());}
}
