package com.tradeflow.web;

import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import java.util.List;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 商家头仅表达当前工作空间；最新成员关系是授权依据，异步任务必须使用持久资源归属。 */
public final class MerchantSelection {
    private MerchantSelection() { }
    /** 多归属必须显式选择，单归属兼容旧客户端；不能默认选第一家而写错业务。 */
    public static String select(AuthContext context) {
        String selected = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest().getHeader("X-Merchant-Id") : null;
        List<String> ids = context.merchantIds();
        if (selected == null || selected.isBlank()) {
            if (ids != null && ids.size() == 1) return ids.get(0);
            throw new BusinessException(409,"MERCHANT_SELECTION_REQUIRED","请先选择当前商家");
        }
        if (!selected.matches("merchant_[a-z0-9_]{1,48}") || ids == null || !ids.contains(selected))
            throw new BusinessException(403,"MERCHANT_FORBIDDEN","无权操作所选商家");
        return selected;
    }
    /** 缩窄身份用于已有资源归属检查，避免工作空间切换后仍读取另一商家的业务。 */
    public static AuthContext narrow(AuthContext context) {
        String selected=require(context,false);
        return new AuthContext(context.userId(),context.status(),context.roles(),List.of(selected),context.tokenVersion(),
                java.util.Map.of(selected,context.merchantRoles().get(selected)));
    }
    /** 权限按当前商家的权威成员角色判定；旧全局商家角色不再构成任何店铺管理授权。 */
    public static String require(AuthContext context,boolean adminOnly) {
        String selected=select(context),role=context.merchantRoles().get(selected);
        if(!"ADMIN".equals(role)&&(adminOnly||!"OPERATOR".equals(role)))
            throw new BusinessException(403,"MERCHANT_FORBIDDEN",adminOnly?"需要当前商家管理员权限":"需要当前商家有效成员权限");
        return selected;
    }
}
