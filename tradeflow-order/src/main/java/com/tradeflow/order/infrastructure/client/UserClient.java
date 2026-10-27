package com.tradeflow.order.infrastructure.client;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.AuthContext;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** 只查询用户权威身份，不访问其数据库。 */
@FeignClient("tradeflow-user")
public interface UserClient {
    /** 进入订单事务前验证最新账号版本和角色，依赖不可用时拒绝敏感操作。 */
    @GetMapping("/internal/v1/users/{id}/auth-context") ApiResponse<AuthContext> context(@PathVariable("id") String id,
            @RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token);
    @GetMapping("/internal/v1/merchants/{id}") ApiResponse<java.util.Map<String,Object>> merchant(@PathVariable("id") String id,@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token);
}
