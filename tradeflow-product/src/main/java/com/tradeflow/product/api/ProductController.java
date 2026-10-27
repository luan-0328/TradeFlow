package com.tradeflow.product.api;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.product.api.ProductModels.*;
import com.tradeflow.product.application.*;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 接口层不操作Mapper，业务权限与事务由对应应用用例负责。 */
@RestController @Validated
public class ProductController {
    private final ProductService products;private final InventoryService inventory;private final QuoteService quotes;
    public ProductController(ProductService products,InventoryService inventory,QuoteService quotes) { this.products=products;this.inventory=inventory;this.quotes=quotes; }
    /** 公开分页浏览上架商品，不返回草稿。 */
    @GetMapping("/api/v1/products")
    public ApiResponse<Page<ProductView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String keyword,@RequestParam(required=false) String merchantId,HttpServletRequest r) {
        return WebResponses.ok(r,products.list(null,false,page,pageSize,keyword,merchantId));
    }
    /** 公开详情只接受合法字符串ID，资源可见性由应用判断。 */
    @GetMapping("/api/v1/products/{id}")
    public ApiResponse<ProductView> detail(@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,HttpServletRequest r) { return WebResponses.ok(r,products.detail(id)); }
    /** 商家查询包含草稿，归属来自用户服务而非请求头。 */
    @GetMapping("/api/v1/merchant/products")
    public ApiResponse<Page<ProductView>> merchant(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String keyword,HttpServletRequest r) { return WebResponses.ok(r,products.list(jwt,true,page,pageSize,keyword,null)); }
    /** 创建商品需稳定幂等键；同一请求重放不会重复生成SKU。 */
    @PostMapping("/api/v1/merchant/products")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<ProductView> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ProductWrite body,HttpServletRequest r) { return WebResponses.ok(r,products.create(jwt,key,body,request(r),trace(r))); }
    /** 乐观编辑商品，冲突需调用方重新读取。 */
    @PatchMapping("/api/v1/merchant/products/{id}")
    public ApiResponse<ProductView> edit(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,
            @Valid @RequestBody ProductEdit body,HttpServletRequest r) { return WebResponses.ok(r,products.edit(jwt,id,body,request(r),trace(r))); }
    /** 为所属商品添加SKU；零库存与幂等结果同事务，后续入库仍需调整单。 */
    @PostMapping("/api/v1/merchant/products/{id}/skus")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<SkuView> addSku(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody WriteSku body,HttpServletRequest r) {
        return WebResponses.ok(r,products.addSku(jwt,id,key,body,request(r),trace(r)));
    }
    /** SKU价格/状态独立编辑，不允许更改所属商品。 */
    @PatchMapping("/api/v1/merchant/skus/{id}")
    public ApiResponse<SkuView> sku(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,
            @Valid @RequestBody SkuEdit body,HttpServletRequest r) { return WebResponses.ok(r,products.editSku(jwt,id,body,request(r),trace(r))); }
    /** 商家管理员提交有依据的库存调整，同键重试读取原单据结果。 */
    @PostMapping("/api/v1/merchant/inventory/adjustments")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<ReceiptView> adjust(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Adjustment body,HttpServletRequest r) { return WebResponses.ok(r,inventory.adjust(jwt,key,body,request(r),trace(r))); }
    /** 只读分页查询所属商家库存流水。 */
    @GetMapping("/api/v1/merchant/inventory/ledger")
    public ApiResponse<Page<LedgerView>> ledger(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize,HttpServletRequest r) { return WebResponses.ok(r,inventory.ledger(jwt,page,pageSize)); }
    /** 内部报价仅订单服务可调用，独立服务凭证在事务前验证。 */
    @PostMapping("/internal/v1/quotes")
    public ApiResponse<QuoteView> quote(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,
            @Valid @RequestBody QuoteRequest body,HttpServletRequest r) { return WebResponses.ok(r,quotes.quote(caller,token,body)); }
    private String request(HttpServletRequest r) { return (String)r.getAttribute("requestId"); }
    private String trace(HttpServletRequest r) { return (String)r.getAttribute("traceId"); }
}
