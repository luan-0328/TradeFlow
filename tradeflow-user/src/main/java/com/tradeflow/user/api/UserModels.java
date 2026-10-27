package com.tradeflow.user.api;

import jakarta.validation.constraints.*;
import java.util.List;

/** 用户API输入与安全响应；不向客户端返回密码哈希或内部服务密钥。 */
public final class UserModels {
    private UserModels() { }
    public enum Status { ACTIVE, DISABLED }
    public enum MerchantRole { ADMIN, OPERATOR }
    public enum Role { USER, MERCHANT_OPERATOR, MERCHANT_ADMIN, PLATFORM_ADMIN }
    public record Register(@NotBlank @Email @Size(max=254) String email,
            @NotBlank @Size(min=12,max=72) String password, @NotBlank @Size(max=64) String displayName,
            @NotBlank @Pattern(regexp="[0-9]{6}") String code) {
        @Override public String toString() { return "Register[REDACTED]"; }
    }
    public record EmailCodeRequest(@NotBlank @Email @Size(max=254) String email) { }
    public record EmailCodeResult(long retryAfterSeconds,long expiresInSeconds) { }
    public record Login(@NotBlank @Size(max=254) String username, @NotBlank @Size(max=128) String password) {
        @Override public String toString() { return "Login[REDACTED]"; }
    }
    public record Capabilities(String userId,List<String> roles,List<String> merchantIds,List<String> allowedActions,java.util.Map<String,String> merchantRoles) { }
    public record UserView(String userId, String username, String displayName, String status, List<String> roles) { }
    public record LoginResult(String accessToken, long expiresInSeconds, String userId, List<String> roles) {
        @Override public String toString() { return "LoginResult[REDACTED]"; }
    }
    public record UserUpdate(@NotNull Status status, @NotBlank @Size(max=500) String reason) { }
    public record RoleAssignment(@NotEmpty @Size(max=4) List<@NotNull Role> roles,
            @NotBlank @Size(max=500) String reason) { }
    public record MerchantMember(@NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String userId,
            @NotBlank @Pattern(regexp="merchant_[a-z0-9_]{1,48}") String merchantId,
            @NotNull Status status, @NotBlank @Size(max=500) String reason, MerchantRole role) {
        public MerchantMember { role=role==null?MerchantRole.OPERATOR:role; }
        /** 旧请求缺少职级时仅授予本商家操作员，不继承任何全局商家角色。 */
        public MerchantMember(String userId,String merchantId,Status status,String reason) {this(userId,merchantId,status,reason,MerchantRole.OPERATOR);}
    }
    public record Page<T>(List<T> items, int page, int pageSize, long total) { }
    public record PasswordChange(@NotBlank @Size(max=128) String oldPassword,
            @NotBlank @Size(max=72) String newPassword) {
        @Override public String toString() { return "PasswordChange[REDACTED]"; }
    }
    public record AddressInput(@NotBlank @Size(max=64) String recipient,
            @NotBlank @Size(max=32) String phone,@NotBlank @Size(max=500) String address,
            boolean defaultAddress,@PositiveOrZero long version,@Size(max=255) String region) {
        /** 兼容旧客户端和既有Java调用；新页面必须收集省市区，历史缺省仍可由用户补填。 */
        public AddressInput(String recipient,String phone,String address,boolean defaultAddress,long version) {this(recipient,phone,address,defaultAddress,version,"");}
        public AddressInput {region=region==null?"":region.strip();}
    }
    public record MerchantApply(@NotBlank @Pattern(regexp="merchant_[a-z0-9_]{1,48}") String merchantId,
            @NotBlank @Size(max=120) String name,@NotBlank @Size(max=500) String description) { }
    public record MerchantProfile(@NotBlank @Size(max=120) String name,
            @NotBlank @Size(max=500) String description,@PositiveOrZero long version) { }
    public record MerchantReview(boolean approved,@NotBlank @Size(max=500) String reason,@PositiveOrZero long version) { }
    public record MerchantState(@NotNull Status status,@NotBlank @Size(max=500) String reason,@PositiveOrZero long version) { }
    public record NotificationInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_:.-]{1,120}") String businessKey,
            @NotBlank @Size(max=120) String title,@NotBlank @Size(max=1000) String content) { }
}
