package com.tradeflow.order.api;

import com.tradeflow.common.*;
import com.tradeflow.order.application.*;
import com.tradeflow.order.application.TradeGroupService.*;
import com.tradeflow.order.infrastructure.*;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 交易组及运费公开入口；结算事实仅受控内部凭据访问，不对外路由。 */
@RestController
public class OrderUpgradeController {
    private final TradeGroupService groups;private final OrderUpgradeMapper db;private final OrderAuthority authority;
    private final OrderMapper orders;private final OrderTransactions tx;private final Environment env;
    public OrderUpgradeController(TradeGroupService groups,OrderUpgradeMapper db,OrderAuthority authority,OrderMapper orders,OrderTransactions tx,Environment env){this.groups=groups;this.db=db;this.authority=authority;this.orders=orders;this.tx=tx;this.env=env;}
    @PostMapping("/api/v1/trade-groups") public ApiResponse<GroupView> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create body,HttpServletRequest r){return WebResponses.ok(r,groups.create(jwt,key,body,id(r),id(r)));}
    @GetMapping("/api/v1/trade-groups/requests/by-key") public ApiResponse<GroupView> lookup(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return WebResponses.ok(r,groups.lookup(jwt,key));}
    @GetMapping("/api/v1/trade-groups/{no}") public ApiResponse<GroupView> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,HttpServletRequest r){return WebResponses.ok(r,groups.detail(jwt,no));}
    @GetMapping("/api/v1/trade-groups") public ApiResponse<List<GroupView>> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r){return WebResponses.ok(r,groups.list(jwt,page,pageSize));}
    @GetMapping("/api/v1/shipping-quotes") public ApiResponse<Map<String,Object>> shipping(@RequestParam String merchantId,HttpServletRequest r){return WebResponses.ok(r,groups.shipping(merchantId));}
    @PutMapping("/api/v1/merchant/shipping-rule") public ApiResponse<Map<String,Object>> shippingWrite(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ShippingRule body,HttpServletRequest r){return WebResponses.ok(r,groups.updateShipping(jwt,body,key,id(r),id(r)));}
    /** 一致读事件时间线不含地址或内部请求材料，归属必须再次核验。 */
    @GetMapping("/api/v1/orders/{no}/timeline") public ApiResponse<List<Map<String,Object>>> timeline(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,HttpServletRequest r){authority.authenticate(jwt,false);return WebResponses.ok(r,tx.read(()->{var o=orders.order(no);if(o==null||!jwt.getSubject().equals(o.userId()))throw new BusinessException(404,"ORDER_NOT_FOUND","订单不存在");return db.timeline(no);}));}
    /** 结算资格与商家归属是订单事实；远端使用者不可指定资源归属。 */
    @GetMapping("/internal/v1/settlement/orders/{no}") public ApiResponse<Map<String,Object>> settlement(@PathVariable String no,@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,HttpServletRequest r){
        String expected=env.getProperty("tradeflow.internal.settlement-token","");
        if(!"settlement".equals(caller)||expected.isBlank()||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(401,"INVALID_SERVICE_CREDENTIAL","服务凭据无效");
        return WebResponses.ok(r,tx.read(()->{var fact=db.settlement(no);if(fact==null)throw new BusinessException(404,"ORDER_NOT_FOUND","订单不存在");Object received=fact.get("receivedAt");
            if(received instanceof java.sql.Timestamp t)fact.put("receivedAt",t.toInstant());
            else if(received instanceof java.time.LocalDateTime t)fact.put("receivedAt",t.toInstant(java.time.ZoneOffset.UTC));
            fact.put("userId",String.valueOf(fact.get("userId")));
            Object active=fact.get("hasActiveAftersale");fact.put("hasActiveAftersale",active instanceof Boolean b?b:active instanceof Number n&&n.intValue()!=0);return fact;}));
    }
    private String id(HttpServletRequest r){return String.valueOf(r.getAttribute("requestId"));}
}
