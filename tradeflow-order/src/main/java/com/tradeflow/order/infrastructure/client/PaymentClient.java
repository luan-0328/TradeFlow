package com.tradeflow.order.infrastructure.client;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.PaymentContracts.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** 订单只请求关闭/查原支付，不能向支付服务直接修改资金流水。 */
@FeignClient("tradeflow-payment")
public interface PaymentClient {
    /** 支付意图不存在也必须建立关闭屏障，返回处理中不代表关闭完成。 */
    @PostMapping("/internal/v1/payments/{no}/close") ApiResponse<View> close(@PathVariable("no") String no,
            @RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,@RequestBody CloseCommand body);
}
