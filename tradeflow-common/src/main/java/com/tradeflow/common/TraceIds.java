package com.tradeflow.common;

import java.util.UUID;
import java.util.regex.Pattern;

/** 只处理日志关联标识，不将任何追踪头当作身份或权限依据。 */
public final class TraceIds {
    public static final String REQUEST_HEADER = "X-Request-Id";
    public static final String TRACE_HEADER = "X-Trace-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private TraceIds() {}

    /** 复用格式安全的关联号，拒绝超长或控制字符，避免日志注入。 */
    public static String validOrNew(String candidate) {
        return candidate != null && SAFE_ID.matcher(candidate).matches() ? candidate : newId();
    }

    /** 创建独立关联号；不依赖机器时钟，也不承诺替代业务唯一约束。 */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
