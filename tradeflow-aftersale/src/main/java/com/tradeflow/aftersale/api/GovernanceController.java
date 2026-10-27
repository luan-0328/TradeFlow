package com.tradeflow.aftersale.api;
import com.tradeflow.aftersale.application.GovernanceService;
import com.tradeflow.aftersale.application.GovernanceService.*;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 平台介入入口，资金与库存处理仍复用原单据恢复协议。 */
@RestController
public class GovernanceController {
 private final GovernanceService service;public GovernanceController(GovernanceService service){this.service=service;}
 @PostMapping("/api/v1/disputes") public ApiResponse<Map<String,Object>> apply(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody Apply body,HttpServletRequest r){return WebResponses.ok(r,service.apply(jwt,key,body));}
 @GetMapping("/api/v1/disputes") public ApiResponse<Map<String,Object>> own(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest r){return WebResponses.ok(r,service.list(jwt,false,page,pageSize));}
 @GetMapping("/api/v1/admin/disputes") public ApiResponse<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest r){return WebResponses.ok(r,service.list(jwt,true,page,pageSize));}
 @PostMapping("/api/v1/admin/disputes/{no}/decision") public ApiResponse<Map<String,Object>> decide(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,@Valid @RequestBody Decision body,HttpServletRequest r){return WebResponses.ok(r,service.decide(jwt,no,body));}
}
