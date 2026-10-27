package com.tradeflow.product.api;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.product.application.RestockService;
import com.tradeflow.product.application.ReturnRestockService;
import com.tradeflow.product.infrastructure.AuthorityClient;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
/** 回补入口仅order凭据，不接受用户或普通商家直接声明退款成功。 */
@RestController public class RestockController {
 private final RestockService service;private final AuthorityClient authority;private final ReturnRestockService returns;
 public RestockController(RestockService service,AuthorityClient authority,ReturnRestockService returns){this.service=service;this.authority=authority;this.returns=returns;}
 /** 不公开网关，原收据号与原预占号双层幂等。 */
 @PostMapping("/internal/v1/inventory/unshipped-restocks") public ApiResponse<RestockView> restock(@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token,@RequestBody Restock body,HttpServletRequest r){authority.orderService(caller,token);return WebResponses.ok(r,service.restock(body));}
 /** 订单恢复任务使用原收据重试；只接受服务凭据，实际购买件始终查询订单确认。 */
 @PostMapping("/internal/v1/inventory/return-restocks") public ApiResponse<RestockView> returnRestock(@RequestHeader("X-Caller-Service")String caller,@RequestHeader("X-Service-Token")String token,@RequestBody ReturnRestock body,HttpServletRequest r){authority.orderService(caller,token);return WebResponses.ok(r,returns.restock(body));}
}
