package com.tradeflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Security过滤链的失败响应，与Controller业务错误保持同一格式。 */
public final class SecurityErrors {
    private SecurityErrors() { }
    public static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
            int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiResponse.failure(code, message,
                (String) request.getAttribute("requestId"), (String) request.getAttribute("traceId")));
    }
}
