package com.tradeflow.web;

import com.tradeflow.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;

/** 仅包装响应与关联号，不改变业务事务或状态。 */
public final class WebResponses {
    private WebResponses() { }
    public static <T> ApiResponse<T> ok(HttpServletRequest request, T data) {
        return ApiResponse.success(data, (String) request.getAttribute("requestId"),
                (String) request.getAttribute("traceId"));
    }
}
