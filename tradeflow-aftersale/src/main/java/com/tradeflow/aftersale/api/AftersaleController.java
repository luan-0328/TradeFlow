package com.tradeflow.aftersale.api;
import com.tradeflow.aftersale.application.AftersaleService;
import com.tradeflow.aftersale.application.AftersaleService.*;
import com.tradeflow.aftersale.domain.Aftersale;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/** 售后外部入口；逐件金额和证据由用例校验，响应无买家地址。 */
@RestController
public class AftersaleController {
 private final AftersaleService service;private final com.tradeflow.aftersale.application.GovernanceService governance;public AftersaleController(AftersaleService service,com.tradeflow.aftersale.application.GovernanceService governance){this.service=service;this.governance=governance;}
 /** 原申请键不进入URL或正文；只读已提交申请，不把当前查不到解释为申请失败。 */
 @GetMapping("/api/v1/aftersales/requests/by-key") public ApiResponse<Aftersale> lookup(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestParam String orderNo,HttpServletRequest r,jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");return WebResponses.ok(r,service.lookupCreate(jwt,key,orderNo));}
 @PostMapping("/api/v1/aftersales") public ApiResponse<Aftersale> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody Create body,HttpServletRequest r){return WebResponses.ok(r,service.create(jwt,key,body));}
 @GetMapping("/api/v1/aftersales") public ApiResponse<List<Aftersale>> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String before,HttpServletRequest r){return WebResponses.ok(r,service.list(jwt,false,limit,before));}
 @GetMapping("/api/v1/aftersales/{no}") public ApiResponse<Aftersale> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,HttpServletRequest r){return WebResponses.ok(r,service.detail(jwt,no,false));}
 @PostMapping("/api/v1/aftersales/{no}/cancel") public ApiResponse<Aftersale> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,HttpServletRequest r){return WebResponses.ok(r,service.cancel(jwt,no));}
 @GetMapping("/api/v1/merchant/aftersales") public ApiResponse<List<Aftersale>> merchantList(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String before,HttpServletRequest r){return WebResponses.ok(r,service.list(jwt,true,limit,before));}
 @GetMapping("/api/v1/merchant/aftersales/{no}") public ApiResponse<Aftersale> merchantDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,HttpServletRequest r){return WebResponses.ok(r,service.detail(jwt,no,true));}
 @PostMapping("/api/v1/merchant/aftersales/{no}/review") public ApiResponse<Aftersale> review(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody Review body,HttpServletRequest r){return WebResponses.ok(r,service.review(jwt,no,key,body));}
 @PostMapping("/api/v1/merchant/aftersales/{no}/retry-refund") public ApiResponse<Aftersale> retryRefund(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody Reason body,HttpServletRequest r){return WebResponses.ok(r,service.retryRefund(jwt,no,key,body));}
 @GetMapping("/api/v1/admin/aftersale-tasks") public ApiResponse<Object> tasks(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest r){return WebResponses.ok(r,governance.tasks(jwt,page,pageSize));}
 @PostMapping("/api/v1/admin/aftersale-tasks/{no}/retry") public ApiResponse<Aftersale> retryTask(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,@RequestBody Reason body,HttpServletRequest r){return WebResponses.ok(r,service.retryTask(jwt,no,body));}
}
