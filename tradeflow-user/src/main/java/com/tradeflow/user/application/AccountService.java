package com.tradeflow.user.application;

import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.user.api.UserModels.*;
import com.tradeflow.user.domain.AccountRow;
import com.tradeflow.user.infrastructure.AccountMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 账号用例：哈希计算在事务外，账号/授权/撤销/审计在本地短事务内。 */
@Service
public class AccountService {
    private final AccountMapper accounts;
    private final LoginProtection protection;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;
    private final String dummyHash;
    private final String issuer;

    public AccountService(AccountMapper accounts, LoginProtection protection, PasswordEncoder passwords,
            JwtEncoder encoder, PlatformTransactionManager manager,
            org.springframework.core.env.Environment environment) {
        this.accounts=accounts; this.protection=protection; this.passwords=passwords; this.encoder=encoder;
        tx=new TransactionTemplate(manager); read=new TransactionTemplate(manager);
        read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        dummyHash=passwords.encode(UUID.randomUUID().toString());
        issuer=environment.getRequiredProperty("tradeflow.security.issuer");
    }

    /** 登录前账号及socket来源双限额独立提交；未知账号与错误密码同义，成功仅释放自身账号预留。 */
    public LoginResult login(Login input,String source) {
        String username=normalize(input.username());
        var reservation=protection.before(username,source);
        AccountRow original=accounts.byUsername(username);
        boolean matches=false;
        // BCrypt最多处理72字节；超长输入不截断，避免不同密码视为同一密码。
        if(input.password().getBytes(StandardCharsets.UTF_8).length<=72)
            matches=passwords.matches(input.password(),original==null?dummyHash:original.passwordHash());
        if(!matches || original==null || !"ACTIVE".equals(original.status())) throw badLogin();
        LoginResult result=tx.execute(status -> {
            AccountRow current=accounts.lock(original.id());
            if(!"ACTIVE".equals(current.status()) || !current.passwordHash().equals(original.passwordHash())) throw badLogin();
            accounts.loggedIn(current.id()); List<String> roles=accounts.roles(current.id());
            Instant now=Instant.now();
            JwtClaimsSet claims=JwtClaimsSet.builder().issuer(issuer).subject(current.id())
                    .audience(List.of("tradeflow-user-api")).issuedAt(now).expiresAt(now.plusSeconds(900))
                    .id(UUID.randomUUID().toString()).claim("token_use","access")
                    .claim("token_version",current.tokenVersion()).claim("roles",roles).build();
            String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                    .keyId("tradeflow-access-v1").build(),claims)).getTokenValue();
            return new LoginResult(token,900,current.id(),roles);
        });
        protection.success(reservation);
        return result;
    }

    /** 一致快照返回当前状态、角色与归属；内部调用无跨库访问，也不缓存已撤销权限。 */
    public AuthContext context(String id) {
        return read.execute(status -> {
            AccountRow row=require(accounts.byId(id));
            return new AuthContext(row.id(),row.status(),accounts.roles(id),accounts.merchants(id),row.tokenVersion(),merchantRoles(id));
        });
    }

    /** 原始JWT验签后再检查当前账号版本；令牌内角色不作为最新授权来源。 */
    public AuthContext authenticate(Jwt jwt) {
        AuthContext context=context(jwt.getSubject());
        Object version=jwt.getClaims().get("token_version");
        if(!"ACTIVE".equals(context.status()) || !(version instanceof Number n) || n.longValue()!=context.tokenVersion())
            throw new BusinessException(401,"TOKEN_REVOKED","登录状态已失效，请重新登录");
        return context;
    }

    /** 同一只读一致快照先认证当前账号版本，再按有效角色和成员关系生成导航；JWT旧角色不参与授权。 */
    public Capabilities capabilities(Jwt jwt) {
        return read.execute(s -> {
            AuthContext current=authenticate(jwt); List<String> actions=new ArrayList<>();
            if(current.roles().contains("USER")) actions.addAll(List.of("SHOP","ORDERS","AFTERSALES"));
            boolean merchant=!current.merchantRoles().isEmpty();
            if(merchant) actions.addAll(List.of("MERCHANT_ORDERS","MERCHANT_AFTERSALES","MERCHANT_PRODUCTS"));
            if(current.merchantRoles().containsValue("ADMIN")) actions.addAll(List.of("MANAGE_PRODUCTS","MANAGE_INVENTORY"));
            if(current.roles().contains("PLATFORM_ADMIN")) actions.addAll(List.of("PLATFORM_USERS","PLATFORM_FUNDS","PLATFORM_OPERATIONS","PLATFORM_RECONCILIATION"));
            return new Capabilities(current.userId(),current.roles(),current.merchantIds(),List.copyOf(actions),current.merchantRoles());
        });
    }

    /** 当前用户资料查询，任何账号撤销均在查询前重新验证。 */
    public UserView me(Jwt jwt) { return read.execute(s -> { authenticate(jwt); return view(accounts.byId(jwt.getSubject())); }); }

    /** 平台分页查询；不暴露密码哈希或令牌版本等认证材料。 */
    public Page<UserView> list(Jwt jwt,int page,int size) {
        pagination(page,size);
        return read.execute(s -> {
            platform(authenticate(jwt));
            return new Page<>(accounts.list(size,(long)(page-1)*size).stream().map(this::view).toList(),page,size,accounts.count());
        });
    }

    /** 平台变更账号状态：锁平台角色锚点后复核操作者，撤销Token与审计同事务提交。 */
    public UserView update(Jwt jwt,String id,UserUpdate input,String request,String trace) {
        return tx.execute(s -> {
            AccountRow target=adminTarget(jwt,id);
            if(!"ACTIVE".equals(input.status().name())) {
                protectLastAdmin(target,false);
                if("ACTIVE".equals(target.status())) merchantRoles(id).forEach((merchant,role) -> {
                    if("ADMIN".equals(role) && accounts.activeMerchantAdmins(merchant)<=1) lastMerchantAdmin();
                });
            }
            accounts.revoke(id,input.status().name());
            audit(jwt,"USER_STATUS",id,input.reason(),"{\"beforeStatus\":\""+target.status()+"\",\"afterStatus\":\""+input.status()+"\"}",request,trace);
            return view(accounts.byId(id));
        });
    }

    /** 全量替换受控角色，拒绝重复项；撤销旧Token，客户端必须重新登录获取新身份。 */
    public UserView roles(Jwt jwt,String id,RoleAssignment input,String request,String trace) {
        if(new HashSet<>(input.roles()).size()!=input.roles().size()) throw new BusinessException(400,"DUPLICATE_ROLE","角色不能重复");
        return tx.execute(s -> {
            AccountRow target=adminTarget(jwt,id);
            protectLastAdmin(target,input.roles().contains(Role.PLATFORM_ADMIN));
            List<String> before=accounts.roles(id);
            accounts.disableRoles(id); input.roles().forEach(role -> accounts.grantRole(id,role.name()));
            accounts.revoke(id,target.status());
            audit(jwt,"USER_ROLES",id,input.reason(),"{\"beforeRoles\":\""+before+"\",\"afterRoles\":\""+input.roles()+"\"}",request,trace);
            return view(accounts.byId(id));
        });
    }

    /** 平台专用成员入口：授权锚点内校验最后管理员，职级/撤销/审计同事务提交；可为历史无管理员商家补授管理员。 */
    public AuthContext member(Jwt jwt,MerchantMember input,String request,String trace) {
        return tx.execute(s -> {
            AccountRow target=adminTarget(jwt,input.userId());
            if(accounts.merchantExists(input.merchantId())==0) throw new BusinessException(404,"MERCHANT_NOT_FOUND","商家不存在");
            changeMember(target,input);
            audit(jwt,"MERCHANT_MEMBER",target.id(),input.reason(),memberSummary(input),request,trace);
            return context(target.id());
        });
    }

    /** 调用方已持有全局授权锚点并完成平台/本店ADMIN认证；不能撤销最后一个可登录的本店管理员，任何失败整体回滚。 */
    public void changeMember(AccountRow target,MerchantMember input) {
        Map<String,Object> before=accounts.merchantMember(target.id(),input.merchantId());
        if(before!=null && "ACTIVE".equals(target.status()) && "ACTIVE".equals(before.get("status"))
                && "ADMIN".equals(before.get("role")) && (input.status()!=Status.ACTIVE || input.role()!=MerchantRole.ADMIN)
                && accounts.activeMerchantAdmins(input.merchantId())<=1) lastMerchantAdmin();
        accounts.memberWithRole(target.id(),input.merchantId(),input.status().name(),input.role().name());
        accounts.revoke(target.id(),target.status());
    }
    /** 审计包含商家和目标职级状态，不含认证材料；目标用户编号由审计业务编号或调用方摘要关联。 */
    public static String memberSummary(MerchantMember input) {
        return "{\"merchantId\":\""+input.merchantId()+"\",\"userId\":\""+input.userId()+"\",\"role\":\""+input.role()+"\",\"status\":\""+input.status()+"\"}";
    }
    /** 权威成员结果不使用全局商家角色推断，停用成员不进入身份快照。 */
    private Map<String,String> merchantRoles(String id) {
        Map<String,String> result=new LinkedHashMap<>();
        accounts.merchantRoles(id).forEach(row -> result.put(row.get("merchantId").toString(),row.get("role").toString()));
        return result;
    }
    private void lastMerchantAdmin() {throw new BusinessException(409,"LAST_MERCHANT_ADMIN","不能移除最后一个有效商家管理员，请先授予另一有效账号管理员");}

    /** 旧密码验证和新BCrypt均在事务外；事务内原哈希/撤销版本CAS，防并发改密覆盖并一次撤销所有会话。 */
    public void password(Jwt jwt,PasswordChange input,String request,String trace) {
        authenticate(jwt); AccountRow original=require(accounts.byId(jwt.getSubject()));
        if(input.oldPassword().getBytes(StandardCharsets.UTF_8).length>72 || !passwords.matches(input.oldPassword(),original.passwordHash()))
            throw new BusinessException(401,"INVALID_CREDENTIALS","旧密码不正确");
        validatePassword(input.newPassword()); String hash=passwords.encode(input.newPassword());
        tx.executeWithoutResult(s -> {
            authenticate(jwt);
            if(accounts.password(original.id(),original.passwordHash(),original.tokenVersion(),hash)!=1)
                throw new BusinessException(409,"VERSION_CONFLICT","账号已变更，请重新登录");
            accounts.actorAudit("USER",jwt.getSubject(),"PASSWORD_CHANGED",original.id(),request,trace,"用户主动修改密码","{}");
        });
    }

    /** 主动退出全部设备以账号行锁串行；撤销版本与审计一起提交，当前请求之后也须重新登录。 */
    public void revokeAll(Jwt jwt,String request,String trace) {
        tx.executeWithoutResult(s -> {
            AccountRow row=require(accounts.lock(jwt.getSubject())); authenticate(jwt);
            accounts.revoke(row.id(),row.status()); accounts.actorAudit("USER",jwt.getSubject(),"SESSIONS_REVOKED",row.id(),request,trace,"用户主动撤销全部会话","{}");
        });
    }

    /** 所有授权修改共享角色锚点；再按用户ID顺序锁账号，避免操作者/目标互锁。 */
    private AccountRow adminTarget(Jwt jwt,String id) {
        accounts.lockAdminRole();
        java.util.stream.Stream.of(jwt.getSubject(),id).distinct().sorted(Comparator.comparing(java.math.BigInteger::new))
                .forEach(value -> require(accounts.lock(value)));
        platform(authenticate(jwt)); return require(accounts.byId(id));
    }

    /** 串行授权事务内保护最后一个可用管理员，保留平台恢复入口。 */
    private void protectLastAdmin(AccountRow target,boolean remainsAdmin) {
        if(!remainsAdmin && "ACTIVE".equals(target.status()) && accounts.roles(target.id()).contains("PLATFORM_ADMIN")
                && accounts.activeAdmins()<=1) throw new BusinessException(409,"LAST_PLATFORM_ADMIN","不能撤销最后一个有效平台管理员");
    }
    /** 平台权限以最新角色为准，不复用令牌内旧角色。 */
    private void platform(AuthContext context) {
        if(!context.roles().contains("PLATFORM_ADMIN")) throw new BusinessException(403,"FORBIDDEN","需要平台管理员权限");
    }
    /** 不存在账号明确404，不构造默认身份。 */
    private AccountRow require(AccountRow row) {
        if(row==null) throw new BusinessException(404,"USER_NOT_FOUND","用户不存在"); return row;
    }
    private UserView view(AccountRow row) { return new UserView(row.id(),row.username(),row.displayName(),row.status(),accounts.roles(row.id())); }
    /** 审计参数来自已验证操作者与入口关联号，不记录Token或密码。 */
    private void audit(Jwt jwt,String action,String id,String reason,String summary,String request,String trace) {
        accounts.audit(jwt.getSubject(),action,id,request,trace,reason,summary);
    }
    /** 新账号以规范化邮箱登录；历史ASCII用户名仍兼容，不能借注册接口创建新的未验证账号。 */
    public static String normalize(String value) {
        if(value==null)throw new BusinessException(400,"INVALID_USERNAME","请输入邮箱或历史用户名");
        String normalized=value.strip().toLowerCase(Locale.ROOT);
        if(normalized.contains("@"))return EmailRegistrationService.normalizeEmail(normalized);
        if(!normalized.matches("[a-z0-9_]{3,64}")) throw new BusinessException(400,"INVALID_USERNAME","用户名为3至64位字母、数字或下划线");
        return normalized;
    }
    /** BCrypt按字节限制；强度检查不把原密码放入异常。 */
    public static void validatePassword(String value) {
        int bytes=value.getBytes(StandardCharsets.UTF_8).length;
        if(bytes<12 || bytes>72 || !value.matches("(?s).*[a-z].*") || !value.matches("(?s).*[A-Z].*")
                || !value.matches("(?s).*[0-9].*") || value.chars().allMatch(Character::isLetterOrDigit))
            throw new BusinessException(400,"WEAK_PASSWORD","密码需12至72个UTF-8字节，包含大小写字母、数字和符号");
    }
    private BusinessException badLogin() { return new BusinessException(401,"INVALID_CREDENTIALS","用户名或密码错误"); }
    private void pagination(int page,int size) {
        if(page<1 || size<1 || size>100) throw new BusinessException(400,"VALIDATION_ERROR","分页参数不合法");
    }
}
