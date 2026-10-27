package com.tradeflow.aftersale.infrastructure.client;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.RefundContracts.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
/** 原退款号查询和提交；只有权威404才允许首次提交。 */
@FeignClient(name="tradeflow-payment",contextId="aftersale-refund")
public interface RefundClient {
 /** 确认同退款号事实，响应丢失继续原号查询，不以超时代替FAILED。 */
 @GetMapping("/internal/v1/refunds/{no}") ApiResponse<View> query(@PathVariable("no")String no,@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token);
 /** 仅GET明确REFUND_NOT_FOUND后提交固定号；本接口没有隐藏写重试。 */
 @PostMapping("/internal/v1/refunds") ApiResponse<View> submit(@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token,@RequestBody Submit body);
}
