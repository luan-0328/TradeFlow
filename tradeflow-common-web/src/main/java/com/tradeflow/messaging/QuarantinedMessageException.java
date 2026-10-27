package com.tradeflow.messaging;

/** 金额/关联冲突或不支持的事实：业务事务回滚，原文隔离，不能当作已应用。 */
public class QuarantinedMessageException extends RuntimeException {
    private final String code;
    public QuarantinedMessageException(String code) { this(code, code); }
    public QuarantinedMessageException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("非法消息原因码");
        this.code = code;
    }
    public String code() { return code; }
}
