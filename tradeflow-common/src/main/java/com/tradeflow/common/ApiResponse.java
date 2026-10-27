package com.tradeflow.common;

/** 统一接口响应；业务数据与请求追踪分离，不使用 HTTP 成功表示异步业务已完成。 */
public record ApiResponse<T>(String code, String message, T data, String requestId, String traceId) {
    /** 包装已完成的本地查询结果；不触发事务或远程业务。 */
    public static <T> ApiResponse<T> success(T data, String requestId, String traceId) {
        return new ApiResponse<>("OK", "success", data, requestId, traceId);
    }

    /** 返回稳定错误码，不向客户端暴露堆栈、SQL 或密钥。 */
    public static ApiResponse<Void> failure(String code, String message, String requestId, String traceId) {
        return new ApiResponse<>(code, message, null, requestId, traceId);
    }
}
