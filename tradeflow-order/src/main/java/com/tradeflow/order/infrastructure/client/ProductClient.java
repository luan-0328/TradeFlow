package com.tradeflow.order.infrastructure.client;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.TradingContracts.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** 订单到商品的真实RPC契约；传稳定业务编号，绝不在订单事务内调用。 */
@FeignClient("tradeflow-product")
public interface ProductClient {
    /** 报价同orderNo重试返回同一事实；当前价格变化明确拒绝。 */
    @PostMapping("/internal/v1/quotes") ApiResponse<Quote> quote(@RequestHeader("X-Caller-Service") String caller,
            @RequestHeader("X-Service-Token") String token,@RequestBody QuoteCommand command);
    /** 多SKU预占，网络超时不能推导未执行。 */
    @PostMapping("/internal/v1/reservations") ApiResponse<Reservation> reserve(@RequestHeader("X-Caller-Service") String caller,
            @RequestHeader("X-Service-Token") String token,@RequestBody ReservationCommand command);
    /** 查询原预占号，不创建第二笔库存操作。 */
    @GetMapping("/internal/v1/reservations/{no}") ApiResponse<Reservation> query(@RequestHeader("X-Caller-Service") String caller,
            @RequestHeader("X-Service-Token") String token,@PathVariable("no") String no);
    /** 持久关闭意图后的释放，先到也会创建墓碑。 */
    @PostMapping("/internal/v1/reservations/{no}/release") ApiResponse<Reservation> release(@RequestHeader("X-Caller-Service") String caller,
            @RequestHeader("X-Service-Token") String token,@PathVariable("no") String no,@RequestBody ReleaseCommand command);
    /** COMMIT仅确认预占，不释放为可售库存；重复按原预占号返回终态。 */
    @PostMapping("/internal/v1/reservations/{no}/confirm") ApiResponse<Reservation> confirm(@RequestHeader("X-Caller-Service") String caller,
            @RequestHeader("X-Service-Token") String token,@PathVariable("no") String no,@RequestBody ReleaseCommand command);
    /** 已确认销售的取消通过独立来源回补，原预占不反写RELEASE。 */
    @org.springframework.web.bind.annotation.PostMapping("/internal/v1/inventory/unshipped-restocks")
    com.tradeflow.common.ApiResponse<com.tradeflow.contracts.RefundContracts.RestockView> restock(
      @org.springframework.web.bind.annotation.RequestHeader("X-Caller-Service")String caller,
      @org.springframework.web.bind.annotation.RequestHeader("X-Service-Token")String token,
      @org.springframework.web.bind.annotation.RequestBody com.tradeflow.contracts.RefundContracts.Restock body);
    /** 退货入库只传权威验收收据，RPC在订单事务外执行。 */
    @PostMapping("/internal/v1/inventory/return-restocks")
    com.tradeflow.common.ApiResponse<com.tradeflow.contracts.RefundContracts.RestockView> returnRestock(
      @RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token,
      @RequestBody com.tradeflow.contracts.RefundContracts.ReturnRestock body);
}
