package com.tradeflow.contracts;

import java.util.List;

/** 当前服务的公开开发信息；禁止添加数据源地址、密码或令牌等配置。 */
public record ServiceDescriptor(String serviceName, String serviceCode, String displayName,
        String stage, List<String> profiles, List<String> capabilities,
        String healthPath, String apiDocsPath) {}
