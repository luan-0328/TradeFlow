package com.tradeflow.payment.api;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.payment.application.reconciliation.ReconciliationService;
import com.tradeflow.payment.application.reconciliation.ReconciliationService.*;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 平台对账传输边界；应用层在事务外验证有效平台账号，写命令必须带幂等键和原因。 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
public class ReconciliationController {
 private final ReconciliationService service;
 public ReconciliationController(ReconciliationService service){this.service=service;}
 @PostMapping("/runs") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 public ApiResponse<Map<String,Object>> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@RequestBody CreateRun body,HttpServletRequest request){return WebResponses.ok(request,service.create(jwt,key,body));}
 @GetMapping("/runs")
 public ApiResponse<List<Map<String,Object>>> runs(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest request){return WebResponses.ok(request,service.runs(jwt,page,pageSize));}
 @GetMapping("/runs/{runNo}")
 public ApiResponse<Map<String,Object>> run(@AuthenticationPrincipal Jwt jwt,@PathVariable String runNo,HttpServletRequest request){return WebResponses.ok(request,service.run(jwt,runNo));}
 @PostMapping("/runs/{runNo}/retry") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 public ApiResponse<Map<String,Object>> retryRun(@AuthenticationPrincipal Jwt jwt,@PathVariable String runNo,@RequestHeader("Idempotency-Key") String key,@RequestBody Reason body,HttpServletRequest request){return WebResponses.ok(request,service.retryRun(jwt,runNo,key,body));}
 @GetMapping("/issues")
 public ApiResponse<List<Map<String,Object>>> issues(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest request){return WebResponses.ok(request,service.issues(jwt,page,pageSize));}
 @GetMapping("/issues/{issueNo}")
 public ApiResponse<Map<String,Object>> issue(@AuthenticationPrincipal Jwt jwt,@PathVariable String issueNo,HttpServletRequest request){return WebResponses.ok(request,service.issue(jwt,issueNo));}
 @PostMapping("/issues/{issueNo}/repair") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 public ApiResponse<Map<String,Object>> repair(@AuthenticationPrincipal Jwt jwt,@PathVariable String issueNo,@RequestHeader("Idempotency-Key") String key,@RequestBody Reason body,HttpServletRequest request){return WebResponses.ok(request,service.repair(jwt,issueNo,key,body));}
 @GetMapping("/actions/{actionNo}")
 public ApiResponse<Map<String,Object>> action(@AuthenticationPrincipal Jwt jwt,@PathVariable String actionNo,HttpServletRequest request){return WebResponses.ok(request,service.action(jwt,actionNo));}
 @PostMapping("/checks") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 public ApiResponse<Map<String,Object>> check(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@RequestBody Check body,HttpServletRequest request){return WebResponses.ok(request,service.check(jwt,key,body));}
 @PostMapping("/actions/{actionNo}/retry") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
 public ApiResponse<Map<String,Object>> retryAction(@AuthenticationPrincipal Jwt jwt,@PathVariable String actionNo,@RequestHeader("Idempotency-Key") String key,@RequestBody Reason body,HttpServletRequest request){return WebResponses.ok(request,service.retryAction(jwt,actionNo,key,body));}
 /** 每轮观察独立留存，分页范围由应用层统一限制。 */
 @GetMapping("/runs/{runNo}/observations")
 public ApiResponse<List<Map<String,Object>>> runObservations(@AuthenticationPrincipal Jwt jwt,@PathVariable String runNo,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest request){return WebResponses.ok(request,service.runObservations(jwt,runNo,page,pageSize));}
 /** 差异已解决后再次出现时仍能回看历史原始证据。 */
 @GetMapping("/issues/{issueNo}/observations")
 public ApiResponse<List<Map<String,Object>>> issueObservations(@AuthenticationPrincipal Jwt jwt,@PathVariable String issueNo,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest request){return WebResponses.ok(request,service.issueObservations(jwt,issueNo,page,pageSize));}
}
