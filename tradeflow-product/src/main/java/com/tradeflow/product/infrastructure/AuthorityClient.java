package com.tradeflow.product.infrastructure;

import com.tradeflow.discovery.ServiceEndpoints;
import com.tradeflow.common.*;
import com.tradeflow.contracts.AuthContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/** 进入商品事务前经Nacos查询用户权威状态；无实例或超时拒绝敏感操作，不缓存旧权限放行。 */
@Component
public class AuthorityClient {
    private final RestClient client;
    private final ServiceEndpoints endpoints;
    private final String productToken;
    private final String orderToken;
    public AuthorityClient(Environment env,ServiceEndpoints endpoints) {
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.endpoints=endpoints;client=RestClient.builder().requestFactory(factory).build();
        productToken=env.getRequiredProperty("tradeflow.internal.product-token");
        orderToken=env.getRequiredProperty("tradeflow.internal.order-token");
    }
    /** 验签后的用户仍需最新版本、有效角色及商家成员关系；不得相信X-Merchant-Id。 */
    public AuthContext merchant(Jwt jwt,boolean adminOnly) {
        AuthContext context=load(jwt.getSubject());
        Object version=jwt.getClaims().get("token_version");
        if(!(version instanceof Number n) || n.longValue()!=context.tokenVersion() || !"ACTIVE".equals(context.status()))
            throw new BusinessException(401,"TOKEN_REVOKED","登录状态已失效，请重新登录");
        com.tradeflow.web.MerchantSelection.require(context,adminOnly);
        return context;
    }
    /** 请求头仅为业务选择；每次使用最新成员关系校验，多归属无选择返回冲突，拒绝隐式串店。 */
    public String selectedMerchant(AuthContext context) {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        String selected=attributes instanceof org.springframework.web.context.request.ServletRequestAttributes request
                ?request.getRequest().getHeader("X-Merchant-Id"):null;
        if(selected==null || selected.isBlank()) {
            if(context.merchantIds().size()!=1) throw new BusinessException(409,"MERCHANT_SELECTION_REQUIRED","请选择当前商家");
            selected=context.merchantIds().iterator().next();
        }
        if(!selected.matches("merchant_[a-z0-9_]{1,48}") || !context.merchantIds().contains(selected))
            throw new BusinessException(403,"MERCHANT_FORBIDDEN","所选商家不属于当前账号");
        return selected;
    }
    /** 新经营事务之前查商家权威状态；历史库存补偿和退货不调用此入口，停用不豁免历史义务。 */
    public void requireActive(String merchantId) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("禁止在商品事务内查询商家");
        try {
            var response=client.get().uri(endpoints.resolve("http://tradeflow-user/internal/v1/merchants/"+merchantId))
                    .headers(headers->com.tradeflow.observability.TracePropagation.headers().forEach(headers::set))
                    .header("X-Caller-Service","product").header("X-Service-Token",productToken).retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<MerchantState>>(){});
            if(response==null || !"OK".equals(response.code()) || response.data()==null || !merchantId.equals(response.data().merchantId()))
                throw new IllegalStateException("无效商家响应");
            if(!"ACTIVE".equals(response.data().status())) throw new BusinessException(409,"MERCHANT_DISABLED","商家已停用，不能开展新经营");
        } catch(BusinessException e) { throw e; }
        catch(Exception e) { throw new BusinessException(503,"AUTHORITY_UNAVAILABLE","暂时无法确认商家状态"); }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public record MerchantState(String merchantId,String status) { }
    /** 报价只授予订单服务；独立凭证与用户JWT区分，不对外路由内部接口。 */
    public void order(String caller,String token,String user) {
        orderService(caller,token);
        AuthContext context=load(user);
        if(!"ACTIVE".equals(context.status()) || !context.roles().contains("USER")) throw new BusinessException(403,"BUYER_FORBIDDEN","买家身份无效");
    }
    /** 库存恢复仅验证受控服务身份，不依赖可能已停用的买家会话，确保补偿仍可执行。 */
    public void orderService(String caller,String token) {
        if(!"order".equals(caller) || !MessageDigest.isEqual(orderToken.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException(401,"INVALID_SERVICE_CREDENTIAL","内部服务凭证无效");
    }
    /** RPC绝不在本地事务内执行；失败结果保守拒绝，不能误当已有权限。 */
    private AuthContext load(String user) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("禁止在商品事务内发起身份RPC");
        try {
            var result=client.get().uri(endpoints.resolve("http://tradeflow-user/internal/v1/users/"+org.springframework.web.util.UriUtils.encodePathSegment(user,StandardCharsets.UTF_8)+"/auth-context"))
                    .headers(headers->com.tradeflow.observability.TracePropagation.headers().forEach(headers::set)).header("X-Caller-Service","product").header("X-Service-Token",productToken)
                    .retrieve().body(new ParameterizedTypeReference<ApiResponse<AuthContext>>(){});
            if(result==null || !"OK".equals(result.code()) || result.data()==null || !user.equals(result.data().userId()))
                throw new IllegalStateException("无效身份响应");
            return result.data();
        } catch(Exception failure) {
            throw new BusinessException(503,"AUTHORITY_UNAVAILABLE","暂时无法确认账号权限，请稍后重试");
        }
    }
}
