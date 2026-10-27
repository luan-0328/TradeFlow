package com.tradeflow.order.api;

import com.tradeflow.common.*;
import com.tradeflow.contracts.PaymentContracts.*;
import com.tradeflow.order.application.OrderPaymentService;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 只供payment调用的内部意图/结果接口，绝不经公开网关路由。 */
@RestController @Validated
public class OrderPaymentController {
    private final OrderPaymentService service;private final String token;
    public OrderPaymentController(OrderPaymentService service,Environment env){this.service=service;token=env.getRequiredProperty("tradeflow.internal.payment-token");}
    /** 权威支付号、金额和买家来自订单，而不是支付前端。 */
    @GetMapping("/internal/v1/orders/{no}/payment-intent")
    public ApiResponse<Intent> intent(@PathVariable @Pattern(regexp="ord_[a-f0-9]{32}") String no,@RequestHeader("X-Caller-Service") String caller,
            @RequestHeader("X-Service-Token") String secret,HttpServletRequest r){verify(caller,secret);return WebResponses.ok(r,service.intent(no));}
    /** ACK表示事实与后续任务已持久接收，不等同库存已确认或迟到支付已退款。 */
    @PostMapping("/internal/v1/orders/{no}/payment-results")
    public ApiResponse<Map<String,Boolean>> result(@PathVariable @Pattern(regexp="ord_[a-f0-9]{32}") String no,@RequestHeader("X-Caller-Service") String caller,
            @RequestHeader("X-Service-Token") String secret,@RequestBody Event body,HttpServletRequest r){verify(caller,secret);return WebResponses.ok(r,service.apply(no,body));}
    private void verify(String caller,String secret){if(!"payment".equals(caller)||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),secret.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(401,"INVALID_SERVICE_CREDENTIAL","支付服务凭据无效");}
}
