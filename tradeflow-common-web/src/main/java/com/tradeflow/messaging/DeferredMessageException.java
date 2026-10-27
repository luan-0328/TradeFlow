package com.tradeflow.messaging;

/** 合法前置条件暂缺：业务事务回滚，原事件由数据库延期任务可靠接管。 */
public class DeferredMessageException extends RuntimeException {
    private final String code;
    public DeferredMessageException(String code) { this(code, code); }
    public DeferredMessageException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("非法消息原因码");
        this.code = code;
    }
    public String code() { return code; }
}
