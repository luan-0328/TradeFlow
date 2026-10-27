package com.tradeflow.order.api;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.order.api.OrderModels.*;
import com.tradeflow.order.application.OrderService;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 订单入口仅解析身份与参数，权限/持久意图/事务均由应用用例负责。 */
@RestController @Validated
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service=service; }
    /** 原键不放URL；本人只读查询，未找到不能直接换键重发，响应禁止缓存。 */
    @GetMapping("/api/v1/orders/requests/by-key")
    public ApiResponse<RequestLookup> lookup(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return WebResponses.ok(r,service.lookupRequest(jwt,key));
    }
    /** 用户显式阻止尚未受理的原请求；已受理只返回原订单，不取消既有交易。 */
    @PostMapping("/api/v1/orders/requests/seal")
    public ApiResponse<Progress> seal(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Reason reason,HttpServletRequest r,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return WebResponses.ok(r,service.sealRequest(jwt,key,reason,request(r),trace(r)));
    }
    /** 202只表示已持久受理，通过queryUrl查询报价、预占及明确拒绝结果。 */
    @PostMapping("/api/v1/orders") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<Accepted> place(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PlaceOrder body,HttpServletRequest r) { return WebResponses.ok(r,service.place(jwt,key,body,request(r),trace(r))); }
    /** 报价前即可按稳定订单号查询本人进度，不暴露临时地址材料。 */
    @GetMapping("/api/v1/orders/requests/{no}")
    public ApiResponse<Progress> progress(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="ord_[a-f0-9]{32}") String no,HttpServletRequest r) {
        return WebResponses.ok(r,service.progress(jwt,no));
    }
    /** 本人订单分页，列表省略件级展开，单单详情提供完整权益编号。 */
    @GetMapping("/api/v1/orders")
    public ApiResponse<Page<OrderView>> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String status,@RequestParam(required=false) String orderNo,HttpServletRequest r) { return WebResponses.ok(r,service.search(jwt,page,pageSize,status,orderNo)); }
    /** 已落地订单详情，当前Token必须仍有效且订单属于本人。 */
    @GetMapping("/api/v1/orders/{no}")
    public ApiResponse<OrderView> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="ord_[a-f0-9]{32}") String no,HttpServletRequest r) {
        return WebResponses.ok(r,service.detail(jwt,no));
    }
    /** 仅QUOTING/CREATING取消，待支付取消明确409等待支付关闭屏障，不伪造已关闭。 */
    @PostMapping("/api/v1/orders/{no}/cancel") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<Accepted> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="ord_[a-f0-9]{32}") String no,
            @Valid @RequestBody Reason reason,HttpServletRequest r) { return WebResponses.ok(r,service.cancel(jwt,no,reason,request(r),trace(r))); }
    /** 平台查询本批创建恢复任务，不支持读取其他业务服务任务。 */
    @GetMapping("/api/v1/admin/order-tasks")
    public ApiResponse<Page<TaskView>> tasks(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize,HttpServletRequest r) { return WebResponses.ok(r,service.tasks(jwt,page,pageSize)); }
    /** 平台带原因重启人工任务，保留原业务编号和审计证据。 */
    @PostMapping("/api/v1/admin/order-tasks/{no}/retry") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<Accepted> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="tsk_[a-f0-9]{32}") String no,
            @Valid @RequestBody Reason reason,HttpServletRequest r) { return WebResponses.ok(r,service.retryTask(jwt,no,reason,request(r),trace(r))); }
    private String request(HttpServletRequest r) { return (String)r.getAttribute("requestId"); }
    private String trace(HttpServletRequest r) { return (String)r.getAttribute("traceId"); }
}
