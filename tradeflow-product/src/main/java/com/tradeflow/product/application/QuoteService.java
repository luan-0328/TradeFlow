package com.tradeflow.product.application;

import com.tradeflow.common.BusinessException;
import com.tradeflow.product.api.ProductModels.*;
import com.tradeflow.product.infrastructure.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

/** 权威报价：同商家、多SKU、预期价格确认及不可变快照；不占库存。 */
@Service
public class QuoteService {
    private final AuthorityClient authority;private final ProductMapper mapper;private final IdempotentWork work;
    public QuoteService(AuthorityClient authority,ProductMapper mapper,IdempotentWork work) { this.authority=authority;this.mapper=mapper;this.work=work; }
    /**
     * 仅订单服务可调用。身份RPC在事务外，合并同SKU后按主键顺序共享锁商品价格。
     * 请求按orderNo稳定幂等，报价头/明细/快照同事务；价格变化拒绝而非静默成交。
     * 返回报价不代表库存足够；B2预占负责库存与报价唯一订单绑定。
     */
    public QuoteView quote(String caller,String token,QuoteRequest input) {
        authority.order(caller,token,input.userId());
        authority.requireActive(input.merchantId());
        Map<String,QuoteLine> merged=new TreeMap<>(Comparator.comparing(java.math.BigInteger::new));
        for(var item:input.items()) {
            QuoteLine old=merged.get(item.skuId());
            if(old!=null && !old.expectedUnitPriceCents().equals(item.expectedUnitPriceCents()))
                throw new BusinessException(409,"PRICE_CHANGED","同一规格的预期价格不一致");
            int quantity=item.quantity()+(old==null?0:old.quantity());
            if(quantity>99) throw new BusinessException(400,"QUANTITY_LIMIT","每个规格最多99件");
            merged.put(item.skuId(),new QuoteLine(item.skuId(),quantity,item.expectedUnitPriceCents()));
        }
        var normalized=new QuoteRequest(input.orderNo(),input.userId(),input.merchantId(),List.copyOf(merged.values()));
        QuoteView result=work.run("SERVICE","order","CREATE_QUOTE",input.orderNo(),normalized,QuoteView.class,no->{
            long amount=0;Instant expires=Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
            // 先取得所有SKU共享锁并校验，避免部分明细被误当成成功报价。
            List<com.tradeflow.product.domain.ProductRows.SkuRow> skus=new ArrayList<>();
            for(var item:normalized.items()) {
                var sku=mapper.quoteSku(item.skuId());
                if(sku==null || !input.merchantId().equals(sku.merchantId()) || !"ON_SALE".equals(sku.productStatus()) || !"ACTIVE".equals(sku.status()))
                    throw new BusinessException(409,"SKU_NOT_SALEABLE","商品或规格不可售");
                if(sku.priceCents()!=item.expectedUnitPriceCents()) throw new BusinessException(409,"PRICE_CHANGED","商品价格已变化，请重新确认");
                amount=Math.addExact(amount,Math.multiplyExact(sku.priceCents(),item.quantity())); skus.add(sku);
                if(amount>100000000) throw new BusinessException(422,"ORDER_AMOUNT_LIMIT","订单金额超出上限");
            }
            mapper.quote(no,input.userId(),input.merchantId(),input.orderNo(),work.hash(normalized),expires);
            var snapshots=new ArrayList<com.tradeflow.contracts.TradingContracts.QuotedItem>();
            for(int i=0;i<skus.size();i++) {
                var sku=skus.get(i);var item=normalized.items().get(i);
                var product=mapper.product(sku.productId());
                mapper.quoteItem(no,sku.id(),item.quantity(),sku.priceCents(),sku.priceVersion(),sku.productId(),product.name(),sku.specification());
                snapshots.add(new com.tradeflow.contracts.TradingContracts.QuotedItem(sku.id(),item.quantity(),sku.priceCents(),sku.productId(),product.name(),sku.specification(),sku.priceVersion()));
            }
            return new QuoteView(no,amount,expires,List.copyOf(snapshots));
        });
        // 已发布B1报价缺少不可变商品元数据，不能用当前商品补造旧成交快照。
        if(result.items().stream().anyMatch(i->i.productId()==null || i.productName()==null || i.specification()==null))
            throw new BusinessException(409,"LEGACY_QUOTE_NOT_REUSABLE","旧报价缺少成交快照，请使用新的下单请求");
        return result;
    }
}
