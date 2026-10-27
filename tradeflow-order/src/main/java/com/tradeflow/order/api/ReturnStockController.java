package com.tradeflow.order.api;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.order.application.*;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 平台只能重启原收据的独立入库义务，不能篡改数量或直接声明成功。 */
@RestController public class ReturnStockController {
 private final ReturnStockRecoveryService service;
 public ReturnStockController(ReturnStockRecoveryService service){this.service=service;}
 public record Retry(String reason) { }
 /** 最新平台身份验证后只读分页，不携带敏感地址。 */
 @GetMapping("/api/v1/admin/return-stock-tasks") public ApiResponse<List<Map<String,Object>>> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest r){return WebResponses.ok(r,service.list(jwt,page,pageSize));}
 /** 重启事务先更新任务再锁订单，与工作者一致；递增围栏拒绝旧执行者回写，原因记入审计。 */
 @PostMapping("/api/v1/admin/return-stock-tasks/{source}/retry") public ApiResponse<Map<String,String>> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable String source,@RequestBody Retry body,HttpServletRequest r){return WebResponses.ok(r,service.retry(jwt,source,body==null?null:body.reason()));}
}
