package com.tradeflow.aftersale.api;

import com.tradeflow.aftersale.application.*;
import com.tradeflow.aftersale.application.ReturnService.*;
import com.tradeflow.aftersale.domain.Aftersale;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.RefundContracts.Preview;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** B6接口仅解析参数，权限、幂等与事务由用例处理，下载受控且禁止浏览器内容嗅探。 */
@RestController
public class ReturnController {
 private final ReturnService returns;private final EvidenceService evidence;
 public ReturnController(ReturnService returns,EvidenceService evidence){this.returns=returns;this.evidence=evidence;}
 /** 逐件预览只读，不预占权益。 */
 @PostMapping("/api/v1/aftersales/preview") public ApiResponse<Preview> preview(@AuthenticationPrincipal Jwt jwt,@RequestBody PreviewInput input,HttpServletRequest r){return WebResponses.ok(r,returns.preview(jwt,input));}
 /** 本人查询原申请分配、证据和退货进度。 */
 @GetMapping("/api/v1/aftersales/{no}/materials") public ApiResponse<Materials> materials(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,HttpServletRequest r){return WebResponses.ok(r,returns.materials(jwt,no,false));}
 /** 商家资料查询也核验最新成员身份。 */
 @GetMapping("/api/v1/merchant/aftersales/{no}/materials") public ApiResponse<Materials> merchantMaterials(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,HttpServletRequest r){return WebResponses.ok(r,returns.materials(jwt,no,true));}
 /** 提交物流与七天截止由数据库仲裁，不允许通过换幂等键修改已寄出事实。 */
 @PostMapping("/api/v1/aftersales/{no}/return-shipment") public ApiResponse<Aftersale> ship(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,@RequestHeader("Idempotency-Key")String key,@RequestBody Shipment input,HttpServletRequest r){return WebResponses.ok(r,returns.ship(jwt,no,key,input));}
 /** 商家一次验收整张售后；拒收需证据。 */
 @PostMapping("/api/v1/merchant/aftersales/{no}/return-inspection") public ApiResponse<Aftersale> inspect(@AuthenticationPrincipal Jwt jwt,@PathVariable String no,@RequestHeader("Idempotency-Key")String key,@RequestBody Inspection input,HttpServletRequest r){return WebResponses.ok(r,returns.inspect(jwt,no,key,input));}
 /** 上传只受理实际文件，不接受任意URL作为证据。 */
 @PostMapping(value="/api/v1/evidence-files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<EvidenceService.FileView> upload(@AuthenticationPrincipal Jwt jwt,@RequestPart("file")MultipartFile file,HttpServletRequest r){return WebResponses.ok(r,evidence.upload(jwt,file));}
 /** 本人、所属商家及平台受控下载，响应不缓存私人证据。 */
 @GetMapping("/api/v1/evidence-files/{key}") public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt,@PathVariable String key){var d=evidence.download(jwt,key);return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.mediaType())).header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).body(d.bytes());}
}
