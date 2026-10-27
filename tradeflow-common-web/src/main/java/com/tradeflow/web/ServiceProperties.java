package com.tradeflow.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 服务展示标识，仅用于公开的骨架说明，不承载业务授权信息。 */
@ConfigurationProperties(prefix = "tradeflow.service")
public record ServiceProperties(String code, String displayName) {}
