package com.tradeflow.user.domain;

/** 仅用户服务内部使用的账号记录，严禁直接序列化到HTTP响应。 */
public record AccountRow(String id, String username, String passwordHash, String displayName,
        String status, long tokenVersion) { }
