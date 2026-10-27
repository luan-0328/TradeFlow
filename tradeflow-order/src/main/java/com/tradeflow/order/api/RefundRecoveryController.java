package com.tradeflow.order.api;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.order.application.RefundRecoveryService;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 退款任务受控恢复只提供重新执行，不提供随意改账入口。 */
@RestController public class RefundRecoveryController {
 private final RefundRecoveryService service;public RefundRecoveryController(RefundRecoveryService service){this.service=service;}
 public record Retry(String reason,boolean newAttempt) { }
 /** 平台查看本库恢复义务。 */
 @GetMapping("/api/v1/admin/refund-tasks") public ApiResponse<List<Map<String,Object>>> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest r){return WebResponses.ok(r,service.list(jwt,page,pageSize));}
 /** newAttempt仅用于已确认失败的迟到支付退款，原因与审计必填。 */
 @PostMapping("/api/v1/admin/refund-tasks/{source}/retry") public ApiResponse<Map<String,String>> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable String source,@RequestBody Retry body,HttpServletRequest r){return WebResponses.ok(r,service.retry(jwt,source,body.reason(),body.newAttempt()));}
}
