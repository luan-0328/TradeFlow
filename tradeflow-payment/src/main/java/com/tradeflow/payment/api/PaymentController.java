package com.tradeflow.payment.api;

import com.tradeflow.common.*;
import com.tradeflow.contracts.PaymentContracts.*;
import com.tradeflow.payment.application.PaymentService;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 支付传输边界；金额由订单提供，公共JWT、内部凭据与通道HMAC分别验证。 */
@RestController
public class PaymentController {
    private final PaymentService service;public PaymentController(PaymentService service){this.service=service;}
    public record Submit(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String orderNo) { }
    /** 原支付键仅放请求头，查询当前买家已受理记录；null不是失败证明，响应不得缓存。 */
    @GetMapping("/api/v1/payments/requests/by-key")
    public ApiResponse<View> lookup(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@RequestParam String orderNo,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");return WebResponses.ok(request,service.lookupRequest(jwt,key,orderNo));}
    /** 仅返回已持久化受理状态，不以HTTP202宣称扣款成功。 */
    @PostMapping("/api/v1/payments") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<View> submit(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Submit body,HttpServletRequest request){return WebResponses.ok(request,service.submit(jwt,key,body.orderNo()));}
    @GetMapping("/api/v1/payments/{no}")
    public ApiResponse<View> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,HttpServletRequest request){return WebResponses.ok(request,service.detail(jwt,no));}
    /** order关闭与公共创建使用同一支付意图锁，先到关闭同样保留通道任务。 */
    @PostMapping("/internal/v1/payments/{no}/close")
    public ApiResponse<View> close(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,@PathVariable String no,@RequestBody CloseCommand body,HttpServletRequest request){return WebResponses.ok(request,service.close(caller,token,no,body));}
    @GetMapping("/internal/v1/payments/{no}")
    public ApiResponse<View> internalDetail(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,@PathVariable String no,HttpServletRequest request){return WebResponses.ok(request,service.internalDetail(caller,token,no));}
    @PostMapping("/internal/v1/payments/{no}/query-channel")
    public ApiResponse<View> query(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,@PathVariable String no,HttpServletRequest request){return WebResponses.ok(request,service.queryChannel(caller,token,no));}
    /** 流式限制原始正文，HMAC覆盖原始字节而非反序列化后重组的JSON。 */
    @PostMapping("/callbacks/v1/payments")
    public ApiResponse<Map<String,Boolean>> callback(@RequestHeader("X-Notification-Timestamp") String timestamp,@RequestHeader("X-Notification-Nonce") String nonce,@RequestHeader("X-Notification-Signature") String signature,HttpServletRequest request)throws IOException{
        if(request.getContentLengthLong()>65536)throw new BusinessException(413,"CALLBACK_TOO_LARGE","回调正文过大");
        byte[] body=request.getInputStream().readNBytes(65537);return WebResponses.ok(request,service.callback(body,timestamp,nonce,signature));
    }
    @GetMapping("/api/v1/admin/payment-ledger")
    public ApiResponse<Map<String,Object>> ledger(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest request){return WebResponses.ok(request,service.ledger(jwt,page,pageSize));}
}
