package com.tradeflow.aftersale.api;
import com.tradeflow.common.ApiResponse;
import com.tradeflow.messaging.MessageOperations;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 本服务消息运维仅平台最新身份可用，不能编辑载荷、改变事件ID或人工写资金终态。 */
@RestController
public class MessageAdminController {
 private final MessageOperations operations;
 private final com.tradeflow.aftersale.infrastructure.Authority authority;
 public MessageAdminController(MessageOperations operations,com.tradeflow.aftersale.infrastructure.Authority authority){this.operations=operations;this.authority=authority;}
 public record Retry(String reason){}
 /** 列表只读本库发布、延期和隔离记录，分页与字段投影由共享运维用例限制。 */
 @GetMapping("/api/v1/admin/aftersale-messages/{kind}")
 public ApiResponse<Object> list(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize,HttpServletRequest r){authority.check(jwt,"admin");return WebResponses.ok(r,operations.list(kind,page,pageSize));}
 /** 原ID、原正文受控重放；原因和操作者审计与恢复意图同事务。 */
 @PostMapping("/api/v1/admin/aftersale-messages/{kind}/{id}/retry")
 public ApiResponse<Map<String,String>> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@PathVariable String id,@RequestBody Retry body,HttpServletRequest r){authority.check(jwt,"admin");return WebResponses.ok(r,operations.retry(kind,id,jwt.getSubject(),body==null?null:body.reason()));}
}
