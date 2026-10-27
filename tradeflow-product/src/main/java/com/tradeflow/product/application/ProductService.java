package com.tradeflow.product.application;

import com.tradeflow.common.BusinessException;
import com.tradeflow.product.api.ProductModels.*;
import com.tradeflow.product.domain.ProductRows.*;
import com.tradeflow.product.infrastructure.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 商品用例，资料编辑采用版本条件更新；商品创建与零库存初始化同事务。 */
@Service
public class ProductService {
    private final ProductMapper mapper; private final AuthorityClient authority; private final IdempotentWork idempotency;
    private final TransactionTemplate tx;private final CatalogCache cache;
    public ProductService(ProductMapper mapper,AuthorityClient authority,IdempotentWork idempotency,PlatformTransactionManager manager,CatalogCache cache) {
        this.mapper=mapper;this.authority=authority;this.idempotency=idempotency;tx=new TransactionTemplate(manager);this.cache=cache;
    }
    /** 仅包含可公开资料；余额禁止进入Redis，报价不读取此快照。 */
    public record ProductSnapshot(ProductRow product,List<SkuRow> skus) { }
    public record CatalogPage(List<ProductSnapshot> items,long total) { }
    /** 公开列表缓存上架商品/有效SKU元数据，库存实时查询；商家读库前从用户服务核对归属。 */
    public Page<ProductView> list(Jwt jwt,boolean merchant,int page,int size) {
        pagination(page,size);String owner=null;
        if(!merchant) {
            CatalogPage snapshot=cache.get("list:"+page+":"+size,CatalogPage.class,()->tx.execute(s->
                    new CatalogPage(mapper.products(null,size,(long)(page-1)*size).stream().map(this::snapshot).toList(),mapper.countProducts(null))));
            return new Page<>(snapshot.items().stream().map(this::publicView).toList(),page,size,snapshot.total());
        }
        if(merchant) { owner=authority.selectedMerchant(authority.merchant(jwt,false)); }
        String finalOwner=owner;
        return tx.execute(s->new Page<>(mapper.products(finalOwner,size,(long)(page-1)*size).stream()
                .map(row->view(row,merchant)).toList(),page,size,mapper.countProducts(finalOwner)));
    }
    /** 关键词为普通文本而非LIKE表达式；公开店铺过滤始终附带上架条件，商家范围以最新权限选择覆盖。 */
    public Page<ProductView> list(Jwt jwt,boolean merchant,int page,int size,String keyword,String merchantId) {
        pagination(page,size);
        if(keyword!=null && keyword.length()>100) throw new BusinessException(400,"VALIDATION_ERROR","关键词最多100字");
        if(merchantId!=null&&!merchantId.matches("merchant_[a-z0-9_]{1,48}")) throw new BusinessException(400,"VALIDATION_ERROR","商家编号不合法");
        String owner=merchant?authority.selectedMerchant(authority.merchant(jwt,false)):merchantId;
        String query=keyword==null?"":keyword.strip();
        return tx.execute(status->new Page<>(mapper.searchProducts(owner,merchant,query,size,(long)(page-1)*size).stream()
                .map(row->view(row,merchant)).toList(),page,size,mapper.countSearchProducts(owner,merchant,query)));
    }
    /** 不可见商品和不存在商品统一404并短期负缓存；展示可短暂陈旧，权威报价另行读库校验。 */
    public ProductView detail(String id) {
        if(id==null||!id.matches("[1-9][0-9]{0,18}"))throw missing();
        ProductSnapshot snapshot=cache.get("detail:"+id,ProductSnapshot.class,()->tx.execute(s->{
            ProductRow row=mapper.product(id);
            return row==null||!"ON_SALE".equals(row.status())?null:snapshot(row);
        }));
        if(snapshot==null)throw missing();
        return publicView(snapshot);
    }
    /** 商品/SKU/零库存/幂等同事务；任一规格重复全部回滚，仅成功提交后失效展示缓存。 */
    public ProductView create(Jwt jwt,String key,ProductWrite input,String request,String trace) {
        String owner=authority.selectedMerchant(authority.merchant(jwt,true));
        authority.requireActive(owner);
        if(input.skus().stream().map(WriteSku::skuCode).distinct().count()!=input.skus().size())
            throw new BusinessException(400,"DUPLICATE_SKU","商品内SKU编码不能重复");
        var normalized=new ProductWrite(input.name().strip(),input.description(),input.status(),
                input.skus().stream().sorted(Comparator.comparing(WriteSku::skuCode)).toList());
        return idempotency.runScoped("MERCHANT",jwt.getSubject(),"CREATE_PRODUCT",key,owner,normalized,ProductView.class,no->{
            Map<String,Object> product=new HashMap<>();product.put("merchantId",owner);
            product.put("name",normalized.name());product.put("description",normalized.description());product.put("status",normalized.status().name());
            mapper.createProduct(product);String id=product.get("id").toString();
            for(var item:normalized.skus()) {
                Map<String,Object> sku=new HashMap<>();sku.put("productId",id);sku.put("skuCode",item.skuCode());
                sku.put("specification",item.specification());sku.put("priceCents",item.priceCents());mapper.createSku(sku);
                mapper.zeroInventory(sku.get("id").toString());
            }
            audit(jwt,"CREATE_PRODUCT",id,"创建商品及零库存","{\"skuCount\":"+normalized.skus().size()+"}",request,trace);
            cache.evictAfterCommit();
            return view(requireProduct(id),true);
        });
    }
    /** 归属复核后用version更新；冲突返回409，调用方读取新版本后决定是否重试。 */
    public ProductView edit(Jwt jwt,String id,ProductEdit input,String request,String trace) {
        String owner=authority.selectedMerchant(authority.merchant(jwt,true));
        authority.requireActive(owner);
        return tx.execute(s->{
            ProductRow before=requireProduct(id);owned(before.merchantId(),owner);
            if(mapper.editProduct(id,owner,input.version(),input.name().strip(),input.description(),input.status().name())!=1) throw conflict();
            audit(jwt,"EDIT_PRODUCT",id,"修改商品资料","{\"previousVersion\":"+input.version()+",\"beforeStatus\":\""+before.status()+"\",\"afterStatus\":\""+input.status()+"\"}",request,trace);
            cache.evictAfterCommit();
            return view(requireProduct(id),true);
        });
    }
    /** SKU价格与可售状态独立版本控制，价格改变时同时递增price_version。 */
    public SkuView editSku(Jwt jwt,String id,SkuEdit input,String request,String trace) {
        String owner=authority.selectedMerchant(authority.merchant(jwt,true));
        authority.requireActive(owner);
        return tx.execute(s->{
            SkuRow row=requireSku(id);owned(row.merchantId(),owner);
            if(mapper.editSku(id,input.version(),input.priceCents(),input.status().name())!=1) throw conflict();
            audit(jwt,"EDIT_SKU",id,"修改SKU价格或状态","{\"previousVersion\":"+input.version()+",\"beforePriceCents\":"+row.priceCents()+",\"afterPriceCents\":"+input.priceCents()+",\"beforeStatus\":\""+row.status()+"\",\"afterStatus\":\""+input.status()+"\"}",request,trace);
            cache.evictAfterCommit();
            return skuView(requireSku(id));
        });
    }
    /** 向既有商品追加规格；归属、商品行锁、零库存、审计和幂等结果同事务，重复编码由唯一键拒绝。 */
    public SkuView addSku(Jwt jwt,String id,String key,WriteSku input,String request,String trace) {
        String owner=authority.selectedMerchant(authority.merchant(jwt,true));authority.requireActive(owner);
        return idempotency.run("MERCHANT",jwt.getSubject(),"ADD_SKU",key,
                new TreeMap<>(Map.of("merchantId",owner,"productId",id,"sku",input)),SkuView.class,no->{
            ProductRow product=mapper.lockProduct(id);if(product==null) throw missing();owned(product.merchantId(),owner);
            if(mapper.skus(id).size()>=100) throw new BusinessException(409,"SKU_LIMIT","每个商品最多100个规格");
            Map<String,Object> sku=new HashMap<>();sku.put("productId",id);sku.put("skuCode",input.skuCode());
            sku.put("specification",input.specification());sku.put("priceCents",input.priceCents());mapper.createSku(sku);
            String skuId=sku.get("id").toString();mapper.zeroInventory(skuId);
            audit(jwt,"ADD_SKU",id,"新增规格及零库存","{}",request,trace);cache.evictAfterCommit();return skuView(requireSku(skuId));
        });
    }
    /** 将内部行转为安全视图；不直接暴露Mapper对象。 */
    private ProductView view(ProductRow row,boolean merchant) {
        return new ProductView(row.id(),row.merchantId(),row.name(),row.description(),row.status(),row.version(),
                mapper.skus(row.id()).stream().filter(s->merchant || "ACTIVE".equals(s.status())).map(this::skuView).toList());
    }
    /** 回源事务只读取公开元数据；禁用SKU不进入公开缓存。 */
    private ProductSnapshot snapshot(ProductRow row) {
        return new ProductSnapshot(row,mapper.skus(row.id()).stream().filter(s->"ACTIVE".equals(s.status())).toList());
    }
    /** 缓存命中仍查库存余额；展示价格允许短时陈旧，下单由QuoteService重新校验。 */
    private ProductView publicView(ProductSnapshot snapshot) {
        ProductRow row=snapshot.product();
        return new ProductView(row.id(),row.merchantId(),row.name(),row.description(),row.status(),row.version(),
                snapshot.skus().stream().map(this::skuView).toList());
    }
    /** 展示真实余额，零库存行缺失属于数据错误而非自动补零。 */
    private SkuView skuView(SkuRow row) {
        InventoryRow balance=mapper.inventory("warehouse_main",row.id());
        return new SkuView(row.id(),row.skuCode(),row.specification(),row.priceCents(),row.status(),row.version(),
                row.priceVersion(),balance.availableQty(),balance.reservedQty());
    }
    private ProductRow requireProduct(String id) { ProductRow row=mapper.product(id);if(row==null)throw missing();return row; }
    private SkuRow requireSku(String id) { SkuRow row=mapper.sku(id);if(row==null)throw missing();return row; }
    /** 所属商家不可从请求传入；无归属时拒绝更新。 */
    private void owned(String merchant,String owner) { if(!owner.equals(merchant)) throw new BusinessException(403,"MERCHANT_FORBIDDEN","商品不属于当前商家"); }
    private BusinessException missing() { return new BusinessException(404,"PRODUCT_NOT_FOUND","商品或规格不存在"); }
    private BusinessException conflict() { return new BusinessException(409,"VERSION_CONFLICT","资料已被修改，请刷新后重试"); }
    private void pagination(int page,int size) { if(page<1||size<1||size>100) throw new BusinessException(400,"VALIDATION_ERROR","分页参数不合法"); }
    /** 当前操作者、入口关联号及业务变更同事务落审计，不记录认证材料。 */
    private void audit(Jwt jwt,String action,String id,String reason,String summary,String request,String trace) {
        mapper.audit(jwt.getSubject(),action,id,request,trace,reason,summary);
    }
}
