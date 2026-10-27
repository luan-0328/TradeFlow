package com.tradeflow.product.application;

import com.tradeflow.common.BusinessException;
import com.tradeflow.product.api.ProductModels.*;
import com.tradeflow.product.infrastructure.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** 有依据的入库/调整；不提供直接覆盖库存余额的接口。 */
@Service
public class InventoryService {
    private final ProductMapper mapper;private final AuthorityClient authority;private final IdempotentWork work;
    public InventoryService(ProductMapper mapper,AuthorityClient authority,IdempotentWork work) {
        this.mapper=mapper;this.authority=authority;this.work=work;
    }
    /**
     * 商家管理员权限在事务前确认。幂等行→调整单→按SKU升序锁余额，
     * 单据/余额/流水/审计/结果同事务，任何一项失败全部回滚；丢响应以原键查询重放。
     * 首次采购也用ADJUSTMENT正数入库，商品初始余额保持0，不允许负可用库存。
     */
    public ReceiptView adjust(Jwt jwt,String key,Adjustment input,String request,String trace) {
        String owner=authority.selectedMerchant(authority.merchant(jwt,true));
        authority.requireActive(owner);
        if(input.items().stream().anyMatch(i->i.quantityDelta()==0)
                || input.items().stream().map(AdjustmentLine::skuId).distinct().count()!=input.items().size())
            throw new BusinessException(400,"INVALID_ADJUSTMENT","数量变化不能为零，SKU不能重复");
        List<AdjustmentLine> sorted=input.items().stream().sorted(Comparator.comparing(i->new java.math.BigInteger(i.skuId()))).toList();
        var normalized=new Adjustment(input.warehouseId(),input.reason().strip(),sorted);
        return work.runScoped("MERCHANT",jwt.getSubject(),"ADJUST_INVENTORY",key,owner,normalized,ReceiptView.class,no->{
            mapper.receipt(no,owner,normalized.reason(),jwt.getSubject());
            for(var item:sorted) {
                var sku=mapper.sku(item.skuId());
                if(sku==null || !owner.equals(sku.merchantId()))
                    throw new BusinessException(403,"MERCHANT_FORBIDDEN","规格不存在或不属于当前商家");
                var before=mapper.lockInventory(input.warehouseId(),item.skuId());
                if(before==null) throw new BusinessException(409,"INVENTORY_NOT_INITIALIZED","规格零库存记录缺失");
                long after=Math.addExact(before.availableQty(),item.quantityDelta());
                // 预占出现后上限约束可用+预占总量，保证合法释放不会突破同一容量边界。
                if(after<0 || Math.addExact(after,before.reservedQty())>1000000000 || mapper.adjust(input.warehouseId(),item.skuId(),item.quantityDelta())!=1)
                    throw new BusinessException(409,"INSUFFICIENT_AVAILABLE_STOCK","调整后可用不能为负，可用与预占合计不能超过10亿");
                mapper.receiptItem(no,input.warehouseId(),item.skuId(),item.quantityDelta());
                mapper.ledger(no,input.warehouseId(),item.skuId(),item.quantityDelta(),after,before.reservedQty());
            }
            mapper.audit(jwt.getSubject(),"ADJUST_INVENTORY",no,request,trace,normalized.reason(),
                    "{\"warehouseId\":\"warehouse_main\",\"skuCount\":"+sorted.size()+"}");
            return new ReceiptView(no,"APPLIED");
        });
    }
    /** 只读查询同样验证商家归属；不需要锁库存余额。 */
    public Page<LedgerView> ledger(Jwt jwt,int page,int size) {
        String owner=authority.selectedMerchant(authority.merchant(jwt,false));
        if(page<1||size<1||size>100) throw new BusinessException(400,"VALIDATION_ERROR","分页参数不合法");
        return new Page<>(mapper.ledgers(owner,size,(long)(page-1)*size),page,size,
                mapper.countLedgers(owner));
    }
}
