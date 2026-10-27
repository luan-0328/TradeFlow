package com.tradeflow.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;

/** Swagger安全声明反映真实入口，不能替代运行时授权。 */
public final class ApiDocumentation {
    private ApiDocumentation() { }
    /** 只为已实现且受保护的业务入口声明Bearer，内部凭证不提供公网JWT入口。 */
    public static OpenApiCustomizer security() {
        return api->{
            if(api.getComponents()==null) api.setComponents(new Components());
            api.getComponents().addSecuritySchemes("bearerAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
            if(api.getPaths()!=null) api.getPaths().forEach((path,item)->{
                if(path.startsWith("/api/v1/admin/")||path.equals("/api/v1/admin/users")
                        ||path.startsWith("/api/v1/merchant/")||path.equals("/api/v1/users/me")||path.equals("/api/v1/orders")||path.startsWith("/api/v1/orders/"))
                    item.readOperations().forEach(op->op.addSecurityItem(new SecurityRequirement().addList("bearerAuth")));
            });
        };
    }
}
