package com.tradeflow.product.api;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.product.application.ProductImageService;
import com.tradeflow.product.application.ProductImageService.ImageView;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 图片独立契约保持历史商品record兼容，二进制响应禁止嗅探和缓存以尊重下架。 */
@RestController @Validated
public class ProductImageController {
    private final ProductImageService images;
    public ProductImageController(ProductImageService images) {this.images=images;}
    /** 公开图片列表仅服务上架商品。 */
    @GetMapping("/api/v1/products/{id}/images")
    public ApiResponse<List<ImageView>> list(@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,HttpServletRequest request) {
        return WebResponses.ok(request,images.list(id,null,false));
    }
    /** 商家读取包括草稿的图片元数据，归属由最新权限和所选店铺决定。 */
    @GetMapping("/api/v1/merchant/products/{id}/images")
    public ApiResponse<List<ImageView>> merchantList(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,HttpServletRequest request) {
        return WebResponses.ok(request,images.list(id,jwt,true));
    }
    /** multipart文件必须有稳定幂等键；业务事务不保存客户端路径和文件名。 */
    @PostMapping(value="/api/v1/merchant/products/{id}/images",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImageView> upload(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,
            @RequestHeader("Idempotency-Key") String key,@RequestPart("file") MultipartFile file,HttpServletRequest request) {
        return WebResponses.ok(request,images.upload(jwt,id,key,file,(String)request.getAttribute("requestId"),(String)request.getAttribute("traceId")));
    }
    /** 删除后公开URL立即不可见，重复请求无副作用。 */
    @DeleteMapping("/api/v1/merchant/products/{id}/images/{imageNo}")
    public ApiResponse<String> remove(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="[1-9][0-9]{0,18}") String id,
            @PathVariable @Pattern(regexp="img_[a-f0-9]{32}") String imageNo,HttpServletRequest request) {
        images.remove(jwt,id,imageNo,(String)request.getAttribute("requestId"),(String)request.getAttribute("traceId"));return WebResponses.ok(request,"REMOVED");
    }
    /** 商家预览草稿需携带JWT与商家选择；响应不可缓存，切店后不能复用旧店图片。 */
    @GetMapping("/api/v1/merchant/product-images/{imageNo}")
    public ResponseEntity<byte[]> merchantContent(@AuthenticationPrincipal Jwt jwt,@PathVariable @Pattern(regexp="img_[a-f0-9]{32}") String imageNo) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; sandbox")
                .body(images.merchantContent(jwt,imageNo));
    }
    /** 公开读取始终复核上架状态；下载头和固定PNG类型阻止主动内容嗅探。 */
    @GetMapping("/api/v1/product-images/{imageNo}")
    public ResponseEntity<byte[]> content(@PathVariable @Pattern(regexp="img_[a-f0-9]{32}") String imageNo) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=product.png").body(images.content(imageNo));
    }
}
