package com.tradeflow.user.infrastructure;

import com.tradeflow.user.domain.AccountRow;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 仅访问trade_user；写方法由应用短事务调用，禁止跨库SQL。 */
@Mapper
public interface AccountMapper {
    /** 普通用户及商家敏感操作保存准确操作者类型，不冒记为平台操作。 */
    int actorAudit(@Param("actorType") String actorType,@Param("actorId") String actorId,@Param("action") String action,@Param("businessNo") String businessNo,@Param("requestId") String requestId,@Param("traceId") String traceId,@Param("reason") String reason,@Param("changeSummary") String changeSummary);
    /** 原哈希与令牌版本双围栏，成功改密同步撤销全部旧会话。 */
    int password(@Param("id") String id,@Param("originalHash") String originalHash,@Param("tokenVersion") long tokenVersion,@Param("hash") String hash);
    /** 授权成员关系前确认商家存在，不将任意编号写成有效身份。 */
    long merchantExists(@Param("id") String id);

    /** 查询认证材料，不加锁；BCrypt计算期间不持有事务锁。 */
    AccountRow byUsername(@Param("username") String username);
    /** 权威账号查询；敏感写进入事务后须重新加锁检查版本。 */
    AccountRow byId(@Param("id") String id);
    /** 串行化账号状态与授权修改；所有管理员修改先锁平台角色锚点。 */
    AccountRow lock(@Param("id") String id);
    /** 唯一用户名由数据库仲裁；账号和普通角色必须在同一事务提交。 */
    int insert(Map<String,Object> account);
    /** 已消费邮箱验证码后调用；邮箱作为唯一登录名，验证时间、账号和USER角色同事务保存。 */
    int insertVerifiedEmail(Map<String,Object> account);
    /** 只返回有效角色，停用关联不能继续授权。 */
    List<String> roles(@Param("userId") String userId);
    /** 成员状态与账号状态分别检查，不把角色等同商家归属。 */
    List<String> merchants(@Param("userId") String userId);
    /** 以专用授权锚点串行化平台写，不授予运行账号修改角色字典的权限。 */
    long lockAdminRole();
    /** 角色变动前先停用现有关联，保留历史行；同事务再激活目标集合。 */
    int disableRoles(@Param("userId") String userId);
    /** 角色唯一键防重复，不接收客户端自定义角色编码。 */
    int grantRole(@Param("userId") String userId, @Param("roleCode") String roleCode);
    /** 统计仍可登录的平台管理员，禁止将最后一个停用或去除角色。 */
    long activeAdmins();
    /** 停用、改角色、改归属统一撤销已签发Token，重新启用也不能复活旧Token。 */
    int revoke(@Param("id") String id, @Param("status") String status);
    /** 单商家成员关系幂等激活/停用，由平台权限事务负责审计及Token撤销。 */
    int member(@Param("userId") String userId, @Param("merchantId") String merchantId, @Param("status") String status);
    /** 有效成员的本商家职级，包含停用商家以便继续履行历史义务。 */
    List<Map<String,Object>> merchantRoles(@Param("userId") String userId);
    /** 授权锚点内查询目标成员，判断撤销是否移除本店最后一个可登录管理员。 */
    Map<String,Object> merchantMember(@Param("userId") String userId,@Param("merchantId") String merchantId);
    /** 统计有效成员且账号有效的本店管理员，经营停用不减少管理员保护范围。 */
    long activeMerchantAdmins(@Param("merchantId") String merchantId);
    /** 成员职级、状态同一语句提交；调用方必须持有授权锚点并同事务撤销令牌、审计。 */
    int memberWithRole(@Param("userId") String userId,@Param("merchantId") String merchantId,@Param("status") String status,@Param("role") String role);
    /** 登录验密后短事务记录时间；不因认证查询产生长锁。 */
    int loggedIn(@Param("id") String id);
    /** 分页上限由应用验证，只向平台返回非敏感账号信息。 */
    List<AccountRow> list(@Param("size") int size, @Param("offset") long offset);
    /** 平台分页总量；调用者权限由应用层在同一读取快照校验。 */
    long count();
    /** 不可变审计与授权变更同事务；摘要由应用生成，不含凭据。 */
    int audit(@Param("actorId") String actorId, @Param("action") String action, @Param("businessNo") String businessNo, @Param("requestId") String requestId, @Param("traceId") String traceId, @Param("reason") String reason, @Param("changeSummary") String changeSummary);
}
