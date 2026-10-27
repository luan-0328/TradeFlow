package com.tradeflow.product.application;

import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.ReturnDispositionPolicy;
import com.tradeflow.product.infrastructure.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** 商家完成待检、维修或报损处置；保留原退货事实，绝不以修改退款状态代替实物处理。 */
@Service
public class InventoryConditionService {
    private final AuthorityClient authority;private final ProductMapper products;
    private final InventoryConditionMapper conditions;private final IdempotentWork idempotency;
    public InventoryConditionService(AuthorityClient authority,ProductMapper products,InventoryConditionMapper conditions,IdempotentWork idempotency) {
        this.authority=authority;this.products=products;this.conditions=conditions;this.idempotency=idempotency;
    }
    public record Change(String skuId,String fromCondition,String toCondition,long quantity,String reason) { }
    public record Result(String operationNo,String skuId,String fromCondition,String toCondition,long quantity) { }
    public record Page(long total,List<Map<String,Object>> items) { }

    /** 最新商家身份校验在事务外；页总量仅本服务所属商家，不汇总其他服务的任务数。 */
    public Page list(Jwt jwt,int page,int size) {
        String owner=authority.selectedMerchant(authority.merchant(jwt,false));if(page<1||size<1||size>100) throw invalid("INVALID_PAGE");
        return new Page(conditions.count(owner),conditions.list(owner,size,Math.multiplyExact((long)page-1,size)));
    }

    /** 只读恢复原请求；当前权限仍须有效，缺失记录保持未知，不要求重建原请求正文。 */
    public Result lookup(Jwt jwt,String key,String sku) {
        var actor=authority.merchant(jwt,true);String owner=authority.selectedMerchant(actor);
        if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,64}")||sku==null||!sku.matches("[1-9][0-9]{0,18}")) throw invalid("INVALID_LOOKUP");
        try { Long.parseLong(sku); } catch(NumberFormatException e) { throw invalid("INVALID_SKU"); }
        var row=products.sku(sku);if(row==null||!owner.equals(row.merchantId())) throw invalid("SKU_NOT_FOUND");
        return conditions.lookup(actor.userId(),key,sku);
    }

    /** 原键保护整笔处置；先扣不可售，再按目标增加可售/其他桶或报损，任一失败整体回滚。 */
    public Result change(Jwt jwt,String key,Change input) {
        var actor=authority.merchant(jwt,true);String owner=authority.selectedMerchant(actor);validate(input);
        var body=new Change(input.skuId(),input.fromCondition(),input.toCondition(),input.quantity(),input.reason().strip());
        return idempotency.runScoped("USER",actor.userId(),"CHANGE_INVENTORY_CONDITION",key,owner,body,Result.class,no->{
            var sku=products.sku(body.skuId());
            if(sku==null) throw invalid("SKU_NOT_FOUND");
            var product=products.product(sku.productId());
            if(product==null||!owner.equals(product.merchantId())) throw invalid("SKU_NOT_FOUND");
            var stock=products.lockInventory("warehouse_main",body.skuId());
            if(stock==null) throw invalid("INVENTORY_NOT_FOUND");
            conditions.ensure("warehouse_main",body.skuId(),body.fromCondition());
            if(conditions.adjust("warehouse_main",body.skuId(),body.fromCondition(),-body.quantity())!=1) throw invalid("CONDITION_STOCK_INSUFFICIENT");
            if("SALEABLE".equals(body.toCondition())) {
                if(products.adjust("warehouse_main",body.skuId(),body.quantity())!=1) throw invalid("STOCK_CAPACITY_EXCEEDED");
                products.ledger(no,"warehouse_main",body.skuId(),body.quantity(),Math.addExact(stock.availableQty(),body.quantity()),stock.reservedQty());
            } else if(!"SCRAPPED".equals(body.toCondition())) {
                conditions.ensure("warehouse_main",body.skuId(),body.toCondition());
                if(conditions.adjust("warehouse_main",body.skuId(),body.toCondition(),body.quantity())!=1) throw invalid("STOCK_CAPACITY_EXCEEDED");
            }
            conditions.operation(no,body.skuId(),body.fromCondition(),body.toCondition(),body.quantity(),actor.userId(),body.reason());
            products.audit(actor.userId(),"CHANGE_INVENTORY_CONDITION",no,no,no,body.reason(),"{\"sourcePreserved\":true}");
            return new Result(no,body.skuId(),body.fromCondition(),body.toCondition(),body.quantity());
        });
    }

    /** 仅允许从现有不可售实物出发，不能把报损再转成可售，也不能通过同状态操作凭空增量。 */
    static void validate(Change input) {
        if(input==null||input.skuId()==null||!input.skuId().matches("[1-9][0-9]{0,18}")) throw invalid("INVALID_SKU");
        try { Long.parseLong(input.skuId()); } catch(NumberFormatException e) { throw invalid("INVALID_SKU"); }
        if(input.fromCondition()==null||!Set.of("QUARANTINE","DAMAGED").contains(input.fromCondition())
                ||input.toCondition()==null||!ReturnDispositionPolicy.CONDITIONS.contains(input.toCondition())
                ||input.fromCondition().equals(input.toCondition())||input.quantity()<1||input.quantity()>1000000000
                ||input.reason()==null||input.reason().isBlank()||input.reason().length()>500) throw invalid("INVALID_CONDITION_CHANGE");
    }
    private static BusinessException invalid(String code) { return new BusinessException(409,code,"库存实物处置不符合当前余额或业务约束"); }
}
