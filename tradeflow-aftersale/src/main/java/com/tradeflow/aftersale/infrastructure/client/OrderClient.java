package com.tradeflow.aftersale.infrastructure.client;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.PaymentContracts.Intent;
import com.tradeflow.contracts.RefundContracts.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
/** 所有授权调用发生于售后本地事务外，同源号重放解决响应丢失。 */
@FeignClient("tradeflow-order")
public interface OrderClient {
 /** 预览逐件剩余实付；只读且不保证随后提交仍有权益。 */
 @PostMapping("/internal/v1/aftersale-authorizations/preview") ApiResponse<Preview> preview(@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token,@RequestBody PreviewRequest body);
 /** 验收事实按固定收据号登记，订单独立持久入库义务，响应丢失复用同号。 */
 @PostMapping("/internal/v1/refund-authorizations/{no}/return-receipt") ApiResponse<Authorization> receive(@PathVariable("no")String no,@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token,@RequestBody ReturnAccept body);
 /** 创建售后前获取最新本人订单意图；响应不持有本地事务锁。 */
 @GetMapping("/internal/v1/orders/{no}/aftersale-intent") ApiResponse<Intent> intent(@PathVariable("no")String no,@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token);
 /** 原售后号申请整单权益，订单权威金额，不传客户端金额。 */
 @PostMapping("/internal/v1/aftersale-authorizations") ApiResponse<Authorization> reserve(@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token,@RequestBody Reserve body);
 /** 未知先查同源授权；只有明确不存在才重放reserve。 */
 @GetMapping("/internal/v1/refund-authorizations/{no}") ApiResponse<Authorization> query(@PathVariable("no")String no,@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token);
 /** 本地批准意图已提交后激活；激活后禁止普通撤销。 */
 @PostMapping("/internal/v1/refund-authorizations/{no}/activate") ApiResponse<Authorization> activate(@PathVariable("no")String no,@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token,@RequestBody Change body);
 /** 原售后号终止可先创建墓碑；迟到reserve不能重新占用权益。 */
 @PostMapping("/internal/v1/refund-authorizations/{no}/terminate") ApiResponse<Authorization> terminate(@PathVariable("no")String no,@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token,@RequestBody Change body);
}
