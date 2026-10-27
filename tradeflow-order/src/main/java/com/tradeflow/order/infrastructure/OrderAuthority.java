package com.tradeflow.order.infrastructure;

import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.order.infrastructure.client.UserClient;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 原JWT验签后查询最新用户事实；不在本地事务内做授权RPC。 */
@Component
public class OrderAuthority {
    private final UserClient user;private final String token;
    public OrderAuthority(UserClient user,Environment env) { this.user=user;token=env.getRequiredProperty("tradeflow.internal.order-token"); }
    /** 买家和平台操作均查账号状态/版本，角色以最新响应为准。 */
    public AuthContext authenticate(Jwt jwt,boolean platform) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("禁止在事务中查询用户身份");
        AuthContext context;
        try {
            var response=user.context(jwt.getSubject(),"order",token);
            if(response==null || !"OK".equals(response.code()) || response.data()==null)throw new IllegalStateException("身份响应无效");
            context=response.data();
        } catch(Exception failure) { throw new BusinessException(503,"AUTHORITY_UNAVAILABLE","暂时无法确认账号权限"); }
        Object version=jwt.getClaims().get("token_version");
        if(!jwt.getSubject().equals(context.userId()) || !"ACTIVE".equals(context.status()) || !(version instanceof Number n) || n.longValue()!=context.tokenVersion())
            throw new BusinessException(401,"TOKEN_REVOKED","登录状态已失效");
        if(!context.roles().contains(platform?"PLATFORM_ADMIN":"USER"))throw new BusinessException(403,"FORBIDDEN","缺少所需权限");return context;
    }
    /** 商家操作查最新角色、撤销版本与成员归属；RPC在本地事务之前执行。 */
    public AuthContext merchant(Jwt jwt){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("禁止事务内身份RPC");
        AuthContext c;try{var r=user.context(jwt.getSubject(),"order",token);if(r==null||!"OK".equals(r.code())||r.data()==null)throw new IllegalStateException();c=r.data();}catch(Exception e){throw new BusinessException(503,"AUTHORITY_UNAVAILABLE","暂时无法确认商家身份");}
        Object v=jwt.getClaims().get("token_version");if(!jwt.getSubject().equals(c.userId())||!"ACTIVE".equals(c.status())||!(v instanceof Number n)||n.longValue()!=c.tokenVersion())throw new BusinessException(401,"TOKEN_REVOKED","登录状态失效");
        return com.tradeflow.web.MerchantSelection.narrow(c);
    }
    /** 新经营先确认商家仍营业；历史退款、查询和补偿无需该检查以保留义务。 */
    public void requireTrading(String merchant){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("禁止事务内商家RPC");
        try{var r=user.merchant(merchant,"order",token);if(r==null||!"OK".equals(r.code())||r.data()==null)throw new IllegalStateException();if(!"ACTIVE".equals(r.data().get("status")))throw new BusinessException(409,"MERCHANT_DISABLED","商家已停止接收新经营操作");}
        catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(503,"AUTHORITY_UNAVAILABLE","暂时无法核验商家经营状态");}
    }
}
