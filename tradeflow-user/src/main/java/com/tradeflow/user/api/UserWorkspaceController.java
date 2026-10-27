package com.tradeflow.user.api;

import com.tradeflow.common.*;
import com.tradeflow.user.api.UserModels.*;
import com.tradeflow.user.application.*;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** U1/U2入口：安全响应沿用统一信封；业务归属、状态及本地事务由应用层完成。 */
@RestController @Validated
public class UserWorkspaceController {
    private final AccountService accounts;
    private final UserWorkspaceService service;
    private final Environment env;
    public UserWorkspaceController(AccountService accounts,UserWorkspaceService service,Environment env) {this.accounts=accounts;this.service=service;this.env=env;}
    /** 改密成功后包括本设备的全部旧访问令牌失效，需重新登录。 */
    @PostMapping("/api/v1/users/me/password")
    public ApiResponse<Void> password(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PasswordChange body,HttpServletRequest r) {accounts.password(jwt,body,request(r),trace(r));return WebResponses.ok(r,null);}
    /** 主动撤销全部设备会话，不保存客户端会话密钥。 */
    @PostMapping("/api/v1/users/me/sessions/revoke-all")
    public ApiResponse<Void> revoke(@AuthenticationPrincipal Jwt jwt,HttpServletRequest r) {accounts.revokeAll(jwt,request(r),trace(r));return WebResponses.ok(r,null);}
    /** 本人地址簿列表。 */
    @GetMapping("/api/v1/users/me/addresses")
    public ApiResponse<List<Map<String,Object>>> addresses(@AuthenticationPrincipal Jwt jwt,HttpServletRequest r) {return WebResponses.ok(r,service.addresses(jwt));}
    /** 地址单条读取也重新核验归属。 */
    @GetMapping("/api/v1/users/me/addresses/{id}")
    public ApiResponse<Map<String,Object>> address(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,HttpServletRequest r) {return WebResponses.ok(r,service.address(jwt,id));}
    /** 新增地址，version输入固定从0开始；默认地址按账号串行切换。 */
    @PostMapping("/api/v1/users/me/addresses") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<Map<String,Object>> address(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody AddressInput body,HttpServletRequest r) {return WebResponses.ok(r,service.address(jwt,null,body));}
    /** 全量更新本人地址，版本冲突不覆盖其他编辑。 */
    @PutMapping("/api/v1/users/me/addresses/{id}")
    public ApiResponse<Map<String,Object>> address(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,@Valid @RequestBody AddressInput body,HttpServletRequest r) {return WebResponses.ok(r,service.address(jwt,id,body));}
    /** 删除仅对本人当前版本生效。 */
    @DeleteMapping("/api/v1/users/me/addresses/{id}")
    public ApiResponse<Void> deleteAddress(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,@RequestParam @PositiveOrZero long version,HttpServletRequest r) {service.deleteAddress(jwt,id,version);return WebResponses.ok(r,null);}
    /** 分页查询本人通知。 */
    @GetMapping("/api/v1/users/me/notifications")
    public ApiResponse<Page<Map<String,Object>>> notifications(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r) {return WebResponses.ok(r,service.notifications(jwt,page,pageSize));}
    /** 已读幂等且归属受限。 */
    @PostMapping("/api/v1/users/me/notifications/{id}/read")
    public ApiResponse<Void> read(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,HttpServletRequest r) {service.read(jwt,id);return WebResponses.ok(r,null);}
    /** 提交商家申请，审批前没有经营身份。 */
    @PostMapping("/api/v1/merchant-applications") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<Map<String,Object>> apply(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody MerchantApply body,HttpServletRequest r) {return WebResponses.ok(r,service.apply(jwt,body));}
    /** 查询本人入驻记录。 */
    @GetMapping("/api/v1/merchant-applications")
    public ApiResponse<Page<Map<String,Object>>> applications(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r) {return WebResponses.ok(r,service.applications(jwt,false,page,pageSize));}
    /** 平台申请池分页。 */
    @GetMapping("/api/v1/admin/merchant-applications")
    public ApiResponse<Page<Map<String,Object>>> adminApplications(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r) {return WebResponses.ok(r,service.applications(jwt,true,page,pageSize));}
    /** 平台审核同事务建立商家和申请人身份，重复审核按版本拒绝。 */
    @PostMapping("/api/v1/admin/merchant-applications/{id}/review")
    public ApiResponse<Map<String,Object>> review(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,@Valid @RequestBody MerchantReview body,HttpServletRequest r) {return WebResponses.ok(r,service.review(jwt,id,body,request(r),trace(r)));}
    /** 可切换商家列表，包括停用后的历史处置身份。 */
    @GetMapping("/api/v1/merchants")
    public ApiResponse<List<Map<String,Object>>> merchants(@AuthenticationPrincipal Jwt jwt,HttpServletRequest r) {return WebResponses.ok(r,service.merchants(jwt));}
    /** 平台商家分页支持审核后继续停启和维护。 */
    @GetMapping("/api/v1/admin/merchants")
    public ApiResponse<Page<Map<String,Object>>> allMerchants(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r) {return WebResponses.ok(r,service.allMerchants(jwt,page,pageSize));}
    /** 平台敏感操作审计分页。 */
    @GetMapping("/api/v1/admin/audits")
    public ApiResponse<Page<Map<String,Object>>> audits(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) @Size(max=64) String actorId,@RequestParam(required=false) @Size(max=64) String businessNo,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,HttpServletRequest r) {return WebResponses.ok(r,service.audits(jwt,actorId,businessNo,page,pageSize));}
    /** 商家资料按服务端验证的所属关系返回。 */
    @GetMapping("/api/v1/merchants/{id}")
    public ApiResponse<Map<String,Object>> merchant(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="merchant_[a-z0-9_]{1,48}") String id,@RequestHeader(value="X-Merchant-Id",required=false) String selected,HttpServletRequest r) {return WebResponses.ok(r,service.merchant(jwt,id,selected));}
    /** 商家管理员维护经营资料。 */
    @PatchMapping("/api/v1/merchants/{id}")
    public ApiResponse<Map<String,Object>> profile(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="merchant_[a-z0-9_]{1,48}") String id,@RequestHeader(value="X-Merchant-Id",required=false) String selected,@Valid @RequestBody MerchantProfile body,HttpServletRequest r) {return WebResponses.ok(r,service.profile(jwt,id,selected,body,request(r),trace(r)));}
    /** 平台启停不移除历史成员。 */
    @PatchMapping("/api/v1/admin/merchants/{id}/status")
    public ApiResponse<Map<String,Object>> state(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="merchant_[a-z0-9_]{1,48}") String id,@Valid @RequestBody MerchantState body,HttpServletRequest r) {return WebResponses.ok(r,service.state(jwt,id,body,request(r),trace(r)));}
    /** 商家管理员查询所属成员，平台跨商家授权使用专用管理入口。 */
    @GetMapping("/api/v1/merchants/{id}/members")
    public ApiResponse<List<Map<String,Object>>> members(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="merchant_[a-z0-9_]{1,48}") String id,@RequestHeader(value="X-Merchant-Id",required=false) String selected,HttpServletRequest r) {return WebResponses.ok(r,service.members(jwt,id,selected));}
    /** 仅本店ADMIN维护本商家成员职级和状态；全局角色不参与商家职级判断。 */
    @PutMapping("/api/v1/merchants/{id}/members")
    public ApiResponse<List<Map<String,Object>>> member(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="merchant_[a-z0-9_]{1,48}") String id,@RequestHeader(value="X-Merchant-Id",required=false) String selected,@Valid @RequestBody MerchantMember body,HttpServletRequest r) {return WebResponses.ok(r,service.member(jwt,id,selected,body,request(r),trace(r)));}
    /** 经营状态内部RPC复用独立服务凭据；平台、经营和历史处置权限由调用服务分别判断。 */
    @GetMapping("/internal/v1/merchants/{id}")
    public ApiResponse<Map<String,Object>> internal(@PathVariable @Pattern(regexp="merchant_[a-z0-9_]{1,48}") String id,@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,HttpServletRequest r) {
        credential(caller,token);
        return WebResponses.ok(r,service.merchant(id));
    }
    /** 内部关键通知稳定键投递；允许重复消息，不接受用户JWT替代服务凭据。 */
    @PostMapping("/internal/v1/users/{id}/notifications")
    public ApiResponse<Void> notification(@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,@Valid @RequestBody NotificationInput input,HttpServletRequest r) {
        credential(caller,token);service.notify(caller,id,input);return WebResponses.ok(r,null);
    }
    /** 服务白名单与独立凭据共同验证；失败封闭，禁止任意caller读取环境配置。 */
    private void credential(String caller,String token) {
        if(!Set.of("product","order","payment","aftersale","settlement").contains(caller))throw new BusinessException(403,"SERVICE_FORBIDDEN","服务无权访问");
        String expected=env.getRequiredProperty("tradeflow.internal."+caller+"-token");
        if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(401,"INVALID_SERVICE_CREDENTIAL","内部服务凭证无效");
    }
    private String request(HttpServletRequest r) {return (String)r.getAttribute("requestId");}
    private String trace(HttpServletRequest r) {return (String)r.getAttribute("traceId");}
}
