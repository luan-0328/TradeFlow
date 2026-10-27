package com.tradeflow.aftersale.infrastructure;
import com.tradeflow.aftersale.infrastructure.client.UserClient;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 最新身份与成员权限在事务前核验，远端不可用时拒绝敏感操作。 */
@Component
public class Authority {
 private final UserClient users;private final String token;
 public Authority(UserClient users,Environment env){this.users=users;token=env.getRequiredProperty("tradeflow.internal.aftersale-token");}
 /** 不相信JWT中旧角色；账号ACTIVE、撤销版本及最新商家归属同时成立。 */
 public AuthContext check(Jwt jwt,String kind){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("禁止事务内身份RPC");AuthContext c;try{var r=users.context(jwt.getSubject(),"aftersale",token);if(r==null||!"OK".equals(r.code())||r.data()==null)throw new IllegalStateException();c=r.data();}catch(Exception e){throw new BusinessException(503,"AUTHORITY_UNAVAILABLE","暂时无法核验权限");}Object v=jwt.getClaims().get("token_version");if(!jwt.getSubject().equals(c.userId())||!"ACTIVE".equals(c.status())||!(v instanceof Number n)||n.longValue()!=c.tokenVersion())throw new BusinessException(401,"TOKEN_REVOKED","登录状态已失效");boolean allowed=c.roles()!=null&&switch(kind){case "merchant"->!c.merchantRoles().isEmpty();case "admin"->c.roles().contains("PLATFORM_ADMIN");case "evidence"->(c.roles().contains("USER")||c.roles().contains("PLATFORM_ADMIN")||!c.merchantRoles().isEmpty());default->c.roles().contains("USER");};if(!allowed)throw new BusinessException(403,"FORBIDDEN","缺少有效权限");return "merchant".equals(kind)?com.tradeflow.web.MerchantSelection.narrow(c):c;}
}
