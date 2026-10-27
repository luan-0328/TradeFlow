package com.tradeflow.user.application;

import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.user.api.UserModels.*;
import com.tradeflow.user.infrastructure.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;

/**
 * 地址、站内通知与商家用例，只访问用户库。
 * 写入口由控制器通过Spring代理调用public注解事务，账号/授权锚点后才锁商家，通知及审计与业务同事务。
 * 私有校验及审计方法参与入口事务，不依赖内部自调用重新开启事务；本类不进行远程调用。
 */
@Service
public class UserWorkspaceService {
    private final AccountService auth;
    private final AccountMapper accounts;
    private final UserWorkspaceMapper data;
    public UserWorkspaceService(AccountService auth,AccountMapper accounts,UserWorkspaceMapper data) {
        this.auth=auth;this.accounts=accounts;this.data=data;
    }
    /** 地址仅返回本人有效记录；订单继续持有原地址快照。 */
    public List<Map<String,Object>> addresses(Jwt jwt) { auth.authenticate(jwt);return data.addresses(jwt.getSubject()); }
    /** 本人地址单条读取，其他用户地址统一不存在。 */
    public Map<String,Object> address(Jwt jwt,String id) {auth.authenticate(jwt);return required(data.address(jwt.getSubject(),id),"ADDRESS_NOT_FOUND");}
    /** 同账号地址写锁账号行，默认唯一键再防并发；目标版本不符整体回滚默认地址变更。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> address(Jwt jwt,String id,AddressInput input) {
        // 锁user_account本人行，再复核登录版本；同一用户的默认地址修改在本库串行。
        accounts.lock(jwt.getSubject());
        auth.authenticate(jwt);
        if(id==null && input.version()!=0)throw error(400,"VALIDATION_ERROR");
        if(id!=null) {
            Map<String,Object> old=required(data.address(jwt.getSubject(),id),"ADDRESS_NOT_FOUND");
            version(old,input.version());
        }
        // 清除旧默认与下方新增/更新同事务，后续版本冲突或写入失败会一起回滚。
        if(input.defaultAddress()) data.clearDefault(jwt.getSubject(),id==null?"0":id);
        Map<String,Object> p=new HashMap<>();p.put("userId",jwt.getSubject());p.put("recipient",input.recipient().strip());
        p.put("phone",input.phone().strip());p.put("region",input.region());p.put("address",input.address().strip());p.put("defaultAddress",input.defaultAddress());p.put("version",input.version());
        if(id==null) data.insertAddress(p); else {p.put("id",id);changed(data.updateAddress(p));}
        return data.address(jwt.getSubject(),p.get("id").toString());
    }
    /** 软删校验归属及版本，不自动选择其他默认地址，避免隐式覆盖用户选择。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAddress(Jwt jwt,String id,long version) {
        accounts.lock(jwt.getSubject());
        auth.authenticate(jwt);
        required(data.address(jwt.getSubject(),id),"ADDRESS_NOT_FOUND");
        changed(data.deleteAddress(jwt.getSubject(),id,version));
    }
    /** 通知分页只查本人，通知文字不是资金或订单权威结果。 */
    public Page<Map<String,Object>> notifications(Jwt jwt,int page,int size) {
        pagination(page,size);auth.authenticate(jwt);return new Page<>(data.notifications(jwt.getSubject(),size,(long)(page-1)*size),page,size,data.notificationCount(jwt.getSubject()));
    }
    /** 重复已读保留首次时间；他人或不存在通知统一404。 */
    @Transactional(rollbackFor = Exception.class)
    public void read(Jwt jwt,String id) {
        auth.authenticate(jwt);
        if(data.notificationExists(jwt.getSubject(),id)==0)throw error(404,"NOTIFICATION_NOT_FOUND");
        // user_notification只更新本人记录；重复已读由SQL保留首次时间。
        data.readNotification(jwt.getSubject(),id);
    }
    /** 内部通知投递按调用服务、用户与稳定业务键幂等；调用方在业务提交后投递，失败不得回滚资金或交易事实。 */
    @Transactional(rollbackFor = Exception.class)
    public void notify(String caller,String user,NotificationInput input) {
        if(accounts.byId(user)==null)throw error(404,"USER_NOT_FOUND");
        data.notifyUser(user,caller+":"+input.businessKey(),input.title(),input.content());
    }
    /** 申请不自动获得商家权限；编号唯一避免相同商家重复入驻，拒绝后需新编号重新申请。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> apply(Jwt jwt,MerchantApply input) {
        accounts.lock(jwt.getSubject());
        auth.authenticate(jwt);
        if(accounts.merchantExists(input.merchantId())>0)throw error(409,"MERCHANT_EXISTS");
        Map<String,Object> p=new HashMap<>();p.put("userId",jwt.getSubject());p.put("merchantId",input.merchantId());p.put("name",input.name().strip());p.put("description",input.description().strip());
        data.apply(p);
        return data.application(p.get("id").toString());
    }
    /** 本人/平台申请分页，平台身份每次重新读取。 */
    public Page<Map<String,Object>> applications(Jwt jwt,boolean admin,int page,int size) {
        pagination(page,size);AuthContext c=auth.authenticate(jwt);if(admin)platform(c);String user=admin?null:c.userId();
        return new Page<>(data.applications(user,size,(long)(page-1)*size),page,size,data.applicationCount(user));
    }
    /** 审核串行授权锚点，申请状态CAS；通过同步创建商家和本店管理员成员并撤销申请人旧会话。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> review(Jwt jwt,String id,MerchantReview input,String request,String trace) {
        // 先锁授权锚点及操作者，避免与成员授权、账号停用使用不同的锁顺序。
        accounts.lockAdminRole();
        accounts.lock(jwt.getSubject());
        platform(auth.authenticate(jwt));
        Map<String,Object> row=required(data.application(id),"APPLICATION_NOT_FOUND");
        String user=row.get("userId").toString();
        accounts.lock(user);
        version(row,input.version());
        changed(data.review(id,input.approved()?"APPROVED":"REJECTED",input.reason(),input.version()));
        if(input.approved()) {
            data.insertMerchant(row);
            accounts.memberWithRole(user,row.get("merchantId").toString(),"ACTIVE","ADMIN");
            accounts.revoke(user,accounts.byId(user).status());
        }
        data.notifyUser(user,"merchant-review:"+id,"商家入驻审核结果",input.approved()?"入驻已通过，请重新登录后进入商家工作台":"入驻被拒绝："+input.reason());
        audit(jwt,"MERCHANT_REVIEW",id,input.reason(),request,trace);
        return data.application(id);
    }
    /** 本人商家列表保留停用商家，供历史订单及售后处置；经营写需另查状态。 */
    public List<Map<String,Object>> merchants(Jwt jwt) {
        AuthContext c=auth.authenticate(jwt);
        return c.merchantRoles().entrySet().stream().map(e -> {
            Map<String,Object> row=data.merchant(e.getKey());if(row==null)return null;
            Map<String,Object> result=new HashMap<>(row);result.put("role",e.getValue());return result;
        }).filter(Objects::nonNull).toList();
    }
    /** 平台经营主体分页，不依赖平台账号是否具有商家成员身份。 */
    public Page<Map<String,Object>> allMerchants(Jwt jwt,int page,int size) {pagination(page,size);platform(auth.authenticate(jwt));return new Page<>(data.allMerchants(size,(long)(page-1)*size),page,size,data.merchantCount());}
    /** 平台审计检索完整分页，查询动作不修改历史审计事实。 */
    public Page<Map<String,Object>> audits(Jwt jwt,String actor,String business,int page,int size) {pagination(page,size);platform(auth.authenticate(jwt));return new Page<>(data.audits(actor,business,size,(long)(page-1)*size),page,size,data.auditCount(actor,business));}
    /** 内部权威经营状态读取，不以经营状态取代权限；调用者凭证由控制器校验。 */
    public Map<String,Object> merchant(String id) {return required(data.merchant(id),"MERCHANT_NOT_FOUND");}
    /** 明确选择必须同时匹配请求路径和授权成员列表；平台跨商家查询走专用平台入口。 */
    public Map<String,Object> merchant(Jwt jwt,String id,String selected) {access(auth.authenticate(jwt),id,selected,false);return merchant(id);}
    /** 管理员资料写与成员、启停共用授权锁；停用商家不能修改经营资料，历史处理不受影响。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> profile(Jwt jwt,String id,String selected,MerchantProfile input,String request,String trace) {
        accounts.lockAdminRole();
        accounts.lock(jwt.getSubject());
        access(auth.authenticate(jwt),id,selected,true);
        Map<String,Object> row=required(data.lockMerchant(id),"MERCHANT_NOT_FOUND");
        if(!"ACTIVE".equals(row.get("status")))throw error(409,"MERCHANT_DISABLED");
        changed(data.profile(id,input.name().strip(),input.description().strip(),input.version()));
        audit(jwt,"MERCHANT_PROFILE",id,"商家维护资料",request,trace);
        return merchant(id);
    }
    /** 平台启停仅改变经营状态，不删除或停用历史成员，也不将历史退款义务关闭。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> state(Jwt jwt,String id,MerchantState input,String request,String trace) {
        accounts.lockAdminRole();
        accounts.lock(jwt.getSubject());
        platform(auth.authenticate(jwt));
        required(data.lockMerchant(id),"MERCHANT_NOT_FOUND");
        changed(data.merchantStatus(id,input.status().name(),input.version()));
        audit(jwt,"MERCHANT_STATUS",id,input.reason(),request,trace);
        return merchant(id);
    }
    /** 成员列表与经营状态分离，停用商家的历史有效成员仍可查。 */
    public List<Map<String,Object>> members(Jwt jwt,String id,String selected) {access(auth.authenticate(jwt),id,selected,true);merchant(id);return data.members(id);}
    /** 成员职级授权在同一锚点串行；本店ADMIN仅能授本店职级，最后有效ADMIN受保护，撤销令牌与目标审计同事务。 */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String,Object>> member(Jwt jwt,String id,String selected,MerchantMember input,String request,String trace) {
        if(!id.equals(input.merchantId()))throw error(400,"MERCHANT_MISMATCH");
        accounts.lockAdminRole();
        accounts.lock(jwt.getSubject());
        access(auth.authenticate(jwt),id,selected,true);
        required(data.lockMerchant(id),"MERCHANT_NOT_FOUND");
        var target=accounts.lock(input.userId());
        if(target==null)throw error(404,"USER_NOT_FOUND");
        auth.changeMember(target,input);
        accounts.actorAudit("MERCHANT",jwt.getSubject(),"MERCHANT_MEMBER",id,request,trace,input.reason(),AccountService.memberSummary(input));
        return data.members(id);
    }
    /** 经营权限依赖当前商家权威成员职级；外部选择值永远不直接成为身份。 */
    private void access(AuthContext c,String id,String selected,boolean admin) {
        if(!id.equals(selected)||!("ADMIN".equals(c.merchantRoles().get(id))||(!admin&&"OPERATOR".equals(c.merchantRoles().get(id)))))throw error(403,"FORBIDDEN");
    }
    private void platform(AuthContext c) {if(!c.roles().contains("PLATFORM_ADMIN"))throw error(403,"FORBIDDEN");}
    private void audit(Jwt jwt,String action,String id,String reason,String request,String trace) {accounts.actorAudit(Set.of("MERCHANT_PROFILE","MERCHANT_MEMBER").contains(action)?"MERCHANT":"PLATFORM",jwt.getSubject(),action,id,request,trace,reason,"{}");}
    private Map<String,Object> required(Map<String,Object> row,String code) {if(row==null)throw error(404,code);return row;}
    private void version(Map<String,Object> row,long v) {if(((Number)row.get("version")).longValue()!=v)throw error(409,"VERSION_CONFLICT");}
    private void changed(int n) {if(n!=1)throw error(409,"VERSION_CONFLICT");}
    private void pagination(int page,int size) {if(page<1||size<1||size>100)throw error(400,"VALIDATION_ERROR");}
    private BusinessException error(int status,String code) {return new BusinessException(status,code,"操作未完成，请检查权限、状态或刷新最新版本");}
}
