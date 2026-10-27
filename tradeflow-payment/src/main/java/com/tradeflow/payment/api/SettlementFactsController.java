package com.tradeflow.payment.api;

import com.tradeflow.common.*;
import com.tradeflow.web.WebResponses;
import com.tradeflow.payment.infrastructure.SettlementFactsMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

/** 结算专用只读事实出口；只认独立调用凭据，不授予支付命令或用户资料访问权限。 */
@RestController
public class SettlementFactsController {
 private final SettlementFactsMapper db; private final String secret;
 public SettlementFactsController(SettlementFactsMapper db,Environment env){this.db=db;this.secret=env.getProperty("tradeflow.internal.settlement-token","");}
 /** 成功账本可重复遍历；自增号不代表提交顺序，消费方必须周期从零重扫以修复晚提交空洞。 */
 @GetMapping("/internal/v1/settlement/facts")
 public ApiResponse<Map<String,Object>> facts(@RequestHeader("X-Caller-Service") String caller,@RequestHeader("X-Service-Token") String token,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="100") int pageSize,HttpServletRequest request){
  if(secret.isBlank()||!"settlement".equals(caller)||!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(403,"FORBIDDEN","结算事实访问凭据无效");
  if(after<0||pageSize<1||pageSize>100)throw new BusinessException(400,"INVALID_CURSOR","资金事实分页无效");
  var rows=db.page(after,pageSize);long next=rows.isEmpty()?after:((Number)rows.get(rows.size()-1).get("factId")).longValue();
  return WebResponses.ok(request,Map.of("items",rows,"nextAfter",next,"hasMore",rows.size()==pageSize));
 }
}
