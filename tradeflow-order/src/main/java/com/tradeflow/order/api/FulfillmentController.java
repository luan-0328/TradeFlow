package com.tradeflow.order.api;
import com.tradeflow.common.*;
import com.tradeflow.contracts.*;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.order.application.FulfillmentService;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** B4传输和服务身份边界，所有业务状态与短事务交由用例处理。 */
@RestController
public class FulfillmentController {
 private final com.tradeflow.order.application.EntitlementService entitlement;private final FulfillmentService service;private final Environment env;
 public FulfillmentController(FulfillmentService service,Environment env,com.tradeflow.order.application.EntitlementService entitlement){this.entitlement=entitlement;this.service=service;this.env=env;}
 public record Ship(String carrier,String trackingNo) { }
 /** 收货资料按需读取且同事务追加审计，普通详情不携带；明确禁止浏览器/代理缓存。 */
 @GetMapping("/api/v1/merchant/orders/{no}/shipping-address")
 public ApiResponse<com.tradeflow.order.api.OrderModels.ShippingAddress> address(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,HttpServletRequest r,jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");return WebResponses.ok(r,service.shippingAddress(jwt,no,(String)r.getAttribute("requestId"),(String)r.getAttribute("traceId")));}
 /** 商家仅查看所属订单列表。 */
 @GetMapping("/api/v1/merchant/orders") public ApiResponse<List<Map<String,Object>>> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest r){return WebResponses.ok(r,service.merchantOrders(jwt,page,pageSize));}
 /** 所属商家订单安全详情，不返回买家地址电话。 */
 @GetMapping("/api/v1/merchant/orders/{no}") public ApiResponse<com.tradeflow.order.api.OrderModels.OrderView> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,HttpServletRequest r){return WebResponses.ok(r,service.merchantDetail(jwt,no));}
 /** 由原订单号保证一次整单发货，物流异参拒绝。 */
 @PostMapping("/api/v1/merchant/orders/{no}/ship") public ApiResponse<Map<String,Object>> ship(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,@RequestHeader("Idempotency-Key")String key,@RequestBody Ship body,HttpServletRequest r){return WebResponses.ok(r,service.ship(jwt,no,key,body.carrier(),body.trackingNo()));}
 /** 本人确认收货，重复不重复迁移。 */
 @PostMapping("/api/v1/orders/{no}/receive") public ApiResponse<Map<String,String>> receive(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){return WebResponses.ok(r,service.receive(jwt,no,key));}
 /** 售后服务读取权威订单意图，不能由客户端自报买家或金额。 */
 @GetMapping("/internal/v1/orders/{no}/aftersale-intent") public ApiResponse<PaymentContracts.Intent> intent(@PathVariable String no,HttpServletRequest r){verify(r,Set.of("aftersale"));return WebResponses.ok(r,service.intent(no));}
 /** 按类型预留逐件金额权益；只有未发货整单类型建立履约锁。 */
 @PostMapping("/internal/v1/aftersale-authorizations") public ApiResponse<Authorization> reserve(@RequestBody Reserve body,HttpServletRequest r){verify(r,Set.of("aftersale"));return WebResponses.ok(r,service.reserve(body));}
 /** 只向限定调用方暴露已存在授权，迟到支付来源不能被售后服务借用。 */
 @GetMapping("/internal/v1/refund-authorizations/{source}") public ApiResponse<Authorization> auth(@PathVariable String source,HttpServletRequest r){String caller=verify(r,Set.of("aftersale","payment","order"));if("aftersale".equals(caller)&&source.startsWith("CLOSE:"))throw new BusinessException(403,"SOURCE_FORBIDDEN","无权访问该来源");return WebResponses.ok(r,service.authorization(source));}
 /** 售后审核通过后的不可普通撤销激活。 */
 @PostMapping("/internal/v1/refund-authorizations/{source}/activate") public ApiResponse<Authorization> activate(@PathVariable String source,@RequestBody Change body,HttpServletRequest r){verify(r,Set.of("aftersale"));return WebResponses.ok(r,service.change(source,body,true));}
 /** 审核前撤销或拒绝，终止先到留下墓碑。 */
 @PostMapping("/internal/v1/refund-authorizations/{source}/terminate") public ApiResponse<Authorization> terminate(@PathVariable String source,@RequestBody Change body,HttpServletRequest r){verify(r,Set.of("aftersale"));return WebResponses.ok(r,service.change(source,body,false));}
 /** 支付可信结果持久ACK，库存后续恢复不持有网络事务。 */
 @PostMapping("/internal/v1/orders/{no}/refund-results") public ApiResponse<Map<String,Boolean>> result(@PathVariable String no,@RequestBody Event event,HttpServletRequest r){verify(r,Set.of("payment"));return WebResponses.ok(r,service.result(no,event));}
 /** 订单权威逐件预览，仅售后可调用，不占用权益。 */
 @PostMapping("/internal/v1/aftersale-authorizations/preview") public ApiResponse<Preview> preview(@RequestBody PreviewRequest body,HttpServletRequest r){verify(r,Set.of("aftersale"));return WebResponses.ok(r,entitlement.preview(body));}
 /** 验收意图持久化并建立独立入库义务，随后才能激活退款。 */
 @PostMapping("/internal/v1/refund-authorizations/{source}/return-receipt") public ApiResponse<Authorization> accept(@PathVariable String source,@RequestBody ReturnAccept body,HttpServletRequest r){verify(r,Set.of("aftersale"));return WebResponses.ok(r,entitlement.accept(source,body));}
 /** 商品依据权威收据验证购买件绑定，不能自报数量入库。 */
 @GetMapping("/internal/v1/return-receipts/{receipt}") public ApiResponse<ReturnReceipt> receipt(@PathVariable String receipt,HttpServletRequest r){verify(r,Set.of("product","order"));return WebResponses.ok(r,entitlement.receipt(receipt));}
 /** 每个调用方使用独立用途凭据，不允许用户JWT冒充服务。 */
 private String verify(HttpServletRequest r,Set<String> allowed){String caller=r.getHeader("X-Caller-Service"),token=r.getHeader("X-Service-Token");if(caller==null||!allowed.contains(caller)||token==null)throw forbidden();String expected=env.getRequiredProperty("tradeflow.internal."+caller+"-token");if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw forbidden();return caller;}
 private BusinessException forbidden(){return new BusinessException(401,"INVALID_SERVICE_CREDENTIAL","内部服务凭据无效");}
}
