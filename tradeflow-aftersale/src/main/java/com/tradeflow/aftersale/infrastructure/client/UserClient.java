package com.tradeflow.aftersale.infrastructure.client;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.AuthContext;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
/** 查询最新身份，不缓存撤销版本。 */
@FeignClient("tradeflow-user")
public interface UserClient {
 /** 每次敏感用例查询ACTIVE、token_version和最新角色/成员，远端不可用则拒绝。 */
 @GetMapping("/internal/v1/users/{id}/auth-context") ApiResponse<AuthContext> context(@PathVariable("id")String id,@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token);
}
