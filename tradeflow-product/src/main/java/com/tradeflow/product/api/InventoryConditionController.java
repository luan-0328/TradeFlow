package com.tradeflow.product.api;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.product.application.InventoryConditionService;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 不可售库存受控入口；实际归属、幂等和短事务由用例校验，不接受用户自行增加可售余额。 */
@RestController
public class InventoryConditionController {
    private final InventoryConditionService service;
    public InventoryConditionController(InventoryConditionService service) { this.service=service; }
    @GetMapping("/api/v1/merchant/inventory/conditions")
    public ApiResponse<InventoryConditionService.Page> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest r) {
        return WebResponses.ok(r,service.list(jwt,page,pageSize));
    }
    @PostMapping("/api/v1/merchant/inventory/condition-adjustments")
    public ApiResponse<InventoryConditionService.Result> change(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestBody InventoryConditionService.Change body,HttpServletRequest r) {
        return WebResponses.ok(r,service.change(jwt,key,body));
    }
    /** 查询原键不创建新处置，响应禁止缓存，null不代表原请求永远不会提交。 */
    @GetMapping("/api/v1/merchant/inventory/condition-adjustments/requests/by-key")
    public ApiResponse<InventoryConditionService.Result> lookup(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestParam String skuId,HttpServletRequest r,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return WebResponses.ok(r,service.lookup(jwt,key,skuId));
    }
}
