package com.tradeflow.order.infrastructure.client;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.RefundContracts.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
/** order只能提交CLOSE迟到退款，支付端会查询原订单授权。 */
@FeignClient(name="tradeflow-payment",contextId="order-refund")
public interface RefundClient {
 /** 稳定退款号重放，未知不得自动换号。 */
 @PostMapping("/internal/v1/refunds") ApiResponse<View> submit(@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token,@RequestBody Submit body);
 /** 查询原执行，明确FAILED与UNKNOWN区别由支付权威决定。 */
 @GetMapping("/internal/v1/refunds/{no}") ApiResponse<View> query(@PathVariable("no")String no,@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token);
}
