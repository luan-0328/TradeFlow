package com.tradeflow.payment.api;
import com.tradeflow.common.*;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.payment.application.RefundService;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
/** 退款内部与回调传输边界：调用方作用域与签名均由应用层先验证。 */
@RestController
public class RefundController {
 private final RefundService service;public RefundController(RefundService service){this.service=service;}
 /** 受理只持久占用额度，结果通过原号查询与恢复任务确认。 */
 @PostMapping("/internal/v1/refunds") public ApiResponse<View> submit(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,@RequestBody Submit c,HttpServletRequest r){return WebResponses.ok(r,service.submit(caller,token,c));}
 /** 查询按order关闭来源或aftersale售后来源隔离。 */
 @GetMapping("/internal/v1/refunds/{no}") public ApiResponse<View> detail(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,@PathVariable String no,HttpServletRequest r){return WebResponses.ok(r,service.detail(caller,token,no));}
 /** 限制原始字节后验HMAC，不能用解析重组JSON参与签名。 */
 @PostMapping("/callbacks/v1/refunds") public ApiResponse<Map<String,Boolean>> callback(@RequestHeader("X-Notification-Timestamp") String at,@RequestHeader("X-Notification-Nonce") String nonce,@RequestHeader("X-Notification-Signature") String signature,HttpServletRequest r)throws IOException{if(r.getContentLengthLong()>65536)throw new BusinessException(413,"CALLBACK_TOO_LARGE","退款正文过大");return WebResponses.ok(r,service.callback(r.getInputStream().readNBytes(65537),at,nonce,signature));}
}
