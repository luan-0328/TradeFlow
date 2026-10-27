package com.tradeflow.product.api;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.TradingContracts.*;
import com.tradeflow.product.application.ReservationService;
import com.tradeflow.product.infrastructure.AuthorityClient;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 内部库存命令不经公开网关；服务凭证校验后由用例完成参数与幂等控制。 */
@RestController
public class ReservationController {
    private final ReservationService service;private final AuthorityClient authority;
    public ReservationController(ReservationService service,AuthorityClient authority) { this.service=service;this.authority=authority; }
    /** 整单预占，超时后调用方查询相同编号，不把RPC超时当作失败。 */
    @PostMapping("/internal/v1/reservations")
    public ApiResponse<Reservation> reserve(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,
            @RequestBody ReservationCommand body,HttpServletRequest r) {
        authority.orderService(caller,token);return WebResponses.ok(r,service.reserve(body));
    }
    /** 只读权威状态，不因没有记录自动释放或返回虚构终态。 */
    @GetMapping("/internal/v1/reservations/{no}")
    public ApiResponse<Reservation> query(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,
            @PathVariable String no,HttpServletRequest r) {
        authority.orderService(caller,token);return WebResponses.ok(r,service.query(no));
    }
    /** 订单持久RELEASE决策后的幂等释放，允许先到墓碑阻止迟到预占。 */
    @PostMapping("/internal/v1/reservations/{no}/release")
    public ApiResponse<Reservation> release(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,
            @PathVariable String no,@RequestBody ReleaseCommand body,HttpServletRequest r) {
        authority.orderService(caller,token);return WebResponses.ok(r,service.release(no,body));
    }
    /** 支付已由订单接受后确认库存；网关不暴露该命令。 */
    @PostMapping("/internal/v1/reservations/{no}/confirm")
    public ApiResponse<Reservation> confirm(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,
            @PathVariable String no,@RequestBody ReleaseCommand body,HttpServletRequest r) {
        authority.orderService(caller,token);return WebResponses.ok(r,service.confirm(no,body));
    }
}
