package com.tradeflow.common;

/** 可向客户端展示的稳定业务错误；不包含 SQL、密码或底层异常。 */
public final class BusinessException extends RuntimeException {
    private final int status;
    private final String code;
    public BusinessException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
