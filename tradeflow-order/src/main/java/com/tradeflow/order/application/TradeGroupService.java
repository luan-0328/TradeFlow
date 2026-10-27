package com.tradeflow.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.TradingContracts;
import com.tradeflow.order.api.OrderModels.*;
import com.tradeflow.order.infrastructure.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** 跨商家组只组织独立订单；同库原子受理全部子意图，后续支付和失败分别展示与恢复。 */
@Service
public class TradeGroupService {
    public record Create(@NotEmpty @Size(max=10) List<@NotNull @Valid PlaceOrder> orders) {
        @Override public String toString(){return "Create[REDACTED]";}
    }
    public record GroupView(String groupNo,String state,List<Map<String,Object>> children) { }
    public record ShippingRule(@Min(0) @Max(1000000) long feeCents,@Min(1) long version) { }
    private final OrderUpgradeMapper db;private final OrderMapper orders;private final OrderService service;
    private final OrderAuthority authority;private final OrderTransactions tx;private final ObjectMapper json;
    public TradeGroupService(OrderUpgradeMapper db,OrderMapper orders,OrderService service,OrderAuthority authority,OrderTransactions tx,ObjectMapper json){this.db=db;this.orders=orders;this.service=service;this.authority=authority;this.tx=tx;this.json=json;}
    /** 组键以买家为范围，摘要包含所有商家及规范子请求；组/子绑定/下单意图同一短事务。 */
    public GroupView create(Jwt jwt,String key,Create input,String request,String trace){
        authority.authenticate(jwt,false);key(key);
        var normalized=input.orders().stream().map(service::normalize).sorted(Comparator.comparing(PlaceOrder::effectiveMerchant)).toList();
        if(normalized.stream().map(PlaceOrder::effectiveMerchant).distinct().count()!=normalized.size())throw fail("DUPLICATE_MERCHANT","同一商家只能有一个子订单");
        String hash;try{hash=TradingContracts.sha256(json.writeValueAsString(normalized));}catch(Exception ex){throw new IllegalStateException("交易组请求编码失败");}
        String digest=hash,group="grp_"+UUID.randomUUID().toString().replace("-","");
        return tx.write(()->{
            db.groupClaim(group,jwt.getSubject(),key,digest);var found=db.groupByKey(jwt.getSubject(),key);
            if(!digest.equals(found.get("request_hash")))throw fail("IDEMPOTENCY_CONFLICT","原交易组不能变更商品、商家、地址或运费");
            String no=(String)found.get("group_no");
            if(group.equals(no))for(var child:normalized){
                String childKey=TradingContracts.sha256(no+":"+child.effectiveMerchant());
                var accepted=service.acceptLocked(jwt,childKey,child,request,trace);
                db.child(no,child.effectiveMerchant(),accepted.businessNo());
            }
            return view(no);
        });
    }
    /** 原键查询只是查询；不存在保持未知，不能作为换键重建依据。 */
    public GroupView lookup(Jwt jwt,String key){authority.authenticate(jwt,false);key(key);return tx.write(()->{var row=db.groupByKey(jwt.getSubject(),key);return row==null?null:view((String)row.get("group_no"));});}
    /** 组只向原买家可见，子状态直接来自订单及创建意图，避免额外可漂移汇总状态。 */
    public GroupView detail(Jwt jwt,String no){authority.authenticate(jwt,false);return tx.read(()->{var group=db.group(no);if(group==null||!jwt.getSubject().equals(String.valueOf(group.get("user_id"))))throw missing();return view(no);});}
    /** 分页只返回本人，不扫描别人的交易组。 */
    public List<GroupView> list(Jwt jwt,int page,int size){authority.authenticate(jwt,false);if(page<1||size<1||size>100)throw fail("INVALID_PAGE","分页无效");return tx.read(()->db.groups(jwt.getSubject(),size,(long)(page-1)*size).stream().map(row->view((String)row.get("group_no"))).toList());}
    /** 对外固定费报价仅用于用户确认；最终受理在规则锁下重新检查。 */
    public Map<String,Object> shipping(String merchant){merchant(merchant);var rule=db.shipping(merchant);return Map.of("merchantId",merchant,"feeCents",rule==null?0:rule.get("fee_cents"),"version",rule==null?1:rule.get("version"));}
    /** 商家管理员修改本商家运费；经营状态RPC先于本地事务，版本条件防止覆盖。 */
    public Map<String,Object> updateShipping(Jwt jwt,ShippingRule input,String request,String trace){return updateShipping(jwt,input,request,request,trace);}
    /** 原键结果同规则更新提交，响应丢失后可原键返回旧结果，绝不再次递增版本。 */
    public Map<String,Object> updateShipping(Jwt jwt,ShippingRule input,String key,String request,String trace){
        var identity=authority.merchant(jwt);String merchant=identity.merchantIds().get(0);key(key);
        if(!"ADMIN".equals(identity.merchantRoles().get(merchant)))throw fail("FORBIDDEN","需要商家管理员");
        String hash=TradingContracts.sha256(merchant+":"+input.version()+":"+input.feeCents());
        var committed=db.shippingCommand(jwt.getSubject(),key);
        if(committed!=null){if(!hash.equals(committed.get("request_hash")))throw fail("IDEMPOTENCY_CONFLICT","原运费请求不能换参");return Map.of("merchantId",committed.get("merchant_id"),"feeCents",committed.get("fee_cents"),"version",committed.get("rule_version"));}
        authority.requireTrading(merchant);
        return tx.write(()->{db.ensureShipping(merchant);db.lockShipping(merchant);var previous=db.shippingCommand(jwt.getSubject(),key);
            if(previous!=null){if(!hash.equals(previous.get("request_hash")))throw fail("IDEMPOTENCY_CONFLICT","原运费请求不能换参");return Map.<String,Object>of("merchantId",previous.get("merchant_id"),"feeCents",previous.get("fee_cents"),"version",previous.get("rule_version"));}
            if(db.shippingUpdate(merchant,input.version(),input.feeCents())!=1)throw fail("VERSION_CONFLICT","运费规则已修改");
            db.saveShippingCommand(jwt.getSubject(),key,hash,merchant,input.feeCents(),input.version()+1);
            orders.audit("MERCHANT",jwt.getSubject(),"UPDATE_SHIPPING",merchant,request,trace,"修改固定运费","{}");return shipping(merchant);});
    }
    /** 只在组内全部履约终结时展示完成；部分失败显式标记，受理不表示支付成功。 */
    private GroupView view(String no){var children=db.children(no);long failed=children.stream().filter(c->Set.of("REJECTED","CANCELLED","CLOSED").contains(c.get("status"))).count();boolean completed=!children.isEmpty()&&children.stream().allMatch(c->Set.of("COMPLETED","REFUNDED","CLOSED","REJECTED","CANCELLED").contains(c.get("status")));return new GroupView(no,failed>0?(failed==children.size()?"CLOSED":"PARTIAL"):completed?"COMPLETED":"IN_PROGRESS",children);}
    private void key(String key){if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,64}"))throw fail("INVALID_IDEMPOTENCY_KEY","需要稳定的原请求键");}
    private void merchant(String value){if(value==null||!value.matches("merchant_[a-z0-9_]{1,48}"))throw fail("INVALID_MERCHANT","商家编号无效");}
    private BusinessException fail(String code,String message){return new BusinessException(409,code,message);}
    private BusinessException missing(){return new BusinessException(404,"GROUP_NOT_FOUND","交易组不存在");}
}
