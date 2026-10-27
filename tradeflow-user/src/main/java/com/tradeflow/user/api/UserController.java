package com.tradeflow.user.api;

import com.tradeflow.common.*;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.user.application.AccountService;
import com.tradeflow.user.api.UserModels.*;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** HTTP仅负责参数、已验签身份和响应；授权及事务在应用层。 */
@RestController @Validated
public class UserController {
    private final AccountService service;
    private final Environment environment;
    private final com.tradeflow.user.application.EmailRegistrationService registration;
    public UserController(AccountService service,Environment environment,com.tradeflow.user.application.EmailRegistrationService registration) {
        this.service=service;this.environment=environment;this.registration=registration;
    }
    /** 公开发送注册邮件；服务端原子限额，响应不返回验证码，不在数据库事务里调用SMTP。 */
    @PostMapping("/api/v1/auth/email-code")
    public ApiResponse<EmailCodeResult> emailCode(@Valid @RequestBody EmailCodeRequest body,HttpServletRequest r) {
        return WebResponses.ok(r,registration.send(body.email()));
    }
    /** 邮箱验证码注册只生成普通用户；未携带验证码的旧注册请求不再接受。 */
    @PostMapping("/api/v1/auth/register")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<UserView> register(@Valid @RequestBody Register body,HttpServletRequest r) { return WebResponses.ok(r,registration.register(body)); }
    /** 验密并签发15分钟JWT；失败次数由独立数据库事务限额。 */
    @PostMapping("/api/v1/auth/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody Login body,HttpServletRequest r) { return WebResponses.ok(r,service.login(body,r.getRemoteAddr())); }
    /** 查询本人时再次检查账号状态和撤销版本。 */
    @GetMapping("/api/v1/users/me")
    public ApiResponse<UserView> me(@AuthenticationPrincipal Jwt jwt,HttpServletRequest r) { return WebResponses.ok(r,service.me(jwt)); }
    /** 当前有效身份生成界面能力，权限仍由各写用例重验。 */
    @GetMapping("/api/v1/users/me/capabilities")
    public ApiResponse<Capabilities> capabilities(@AuthenticationPrincipal Jwt jwt,HttpServletRequest r) { return WebResponses.ok(r,service.capabilities(jwt)); }
    /** 仅平台账号分页查询，明细过滤由应用权限校验完成。 */
    @GetMapping("/api/v1/admin/users")
    public ApiResponse<Page<UserView>> users(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize,HttpServletRequest r) { return WebResponses.ok(r,service.list(jwt,page,pageSize)); }
    /** 停用/启用账号并撤销旧令牌；审计与状态同事务。 */
    @PatchMapping("/api/v1/admin/users/{id}")
    public ApiResponse<UserView> update(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,
            @Valid @RequestBody UserUpdate body,HttpServletRequest r) {
        return WebResponses.ok(r,service.update(jwt,id,body,request(r),trace(r)));
    }
    /** 角色采用全量替换，客户端不得绕过平台授权直接授予商家身份。 */
    @PutMapping("/api/v1/admin/users/{id}/roles")
    public ApiResponse<UserView> roles(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,
            @Valid @RequestBody RoleAssignment body,HttpServletRequest r) { return WebResponses.ok(r,service.roles(jwt,id,body,request(r),trace(r))); }
    /** 建立商家归属，不自动授予角色；两者都有效才能操作商品。 */
    @PostMapping("/api/v1/admin/merchant-members")
    public ApiResponse<AuthContext> member(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody MerchantMember body,HttpServletRequest r) {
        return WebResponses.ok(r,service.member(jwt,body,request(r),trace(r)));
    }
    /** 内部只读身份接口按调用服务校验独立凭证；不接受用户JWT冒充服务。 */
    @GetMapping("/internal/v1/users/{id}/auth-context")
    public ApiResponse<AuthContext> context(@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,
            @RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,HttpServletRequest r) {
        if(!java.util.Set.of("product","order","payment","aftersale","settlement").contains(caller)) throw new BusinessException(403,"SERVICE_FORBIDDEN","服务无权访问");
        String expected=environment.getRequiredProperty("tradeflow.internal."+caller+"-token");
        if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException(401,"INVALID_SERVICE_CREDENTIAL","内部服务凭证无效");
        return WebResponses.ok(r,service.context(id));
    }
    private String request(HttpServletRequest r) { return (String)r.getAttribute("requestId"); }
    private String trace(HttpServletRequest r) { return (String)r.getAttribute("traceId"); }
}
