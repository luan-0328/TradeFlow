package com.tradeflow.contracts;

import java.util.List;
import java.util.Map;

/** 用户服务权威身份快照；外部身份头不构成该上下文。 */
public record AuthContext(String userId, String status, List<String> roles,
        List<String> merchantIds, long tokenVersion, Map<String,String> merchantRoles) {
    public AuthContext { merchantRoles=merchantRoles==null?Map.of():Map.copyOf(merchantRoles); }
    /** 兼容旧Java调用；旧快照不含每店权威角色，不能由全局角色推断授权。 */
    public AuthContext(String userId,String status,List<String> roles,List<String> merchantIds,long tokenVersion) {
        this(userId,status,roles,merchantIds,tokenVersion,Map.of());
    }
}
