package com.tradeflow.aftersale.api;
import com.tradeflow.common.ApiResponse;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 仅为已有平台GET入口增加保守操作提示；入口已核验最新平台身份，提示不替代写命令的状态/租约复核。 */
@RestControllerAdvice(basePackages="com.tradeflow.aftersale.api")
public class PlatformActionAdvice implements ResponseBodyAdvice<Object> {
 @Override public boolean supports(MethodParameter type,Class<? extends HttpMessageConverter<?>> converter){return true;}
 /** 白名单匹配已实现的恢复和对账查询，不处理买家、商家或内部资源。 */
 @Override public Object beforeBodyWrite(Object body,MethodParameter type,MediaType media,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
  String path=request.getURI().getPath();
  if(request.getMethod()!=HttpMethod.GET||!path.matches("(/api/v1/admin/aftersale-messages/(outbox|deferred|failure))|(/api/v1/admin/aftersale-tasks)")||!(body instanceof ApiResponse<?> api)||!"OK".equals(api.code()))return body;
  return new ApiResponse<>(api.code(),api.message(),map(api.data(),path),api.requestId(),api.traceId());
 }
 /** 仅复制已知列表容器和原始行，保留原snake_case键；冻结差异只提示重新核验，不增加解冻能力。 */
 private Object map(Object data,String path){
  if(data instanceof List<?> list)return list.stream().map(item->map(item,path)).toList();
  if(!(data instanceof Map<?,?> original))return data;
  var row=new LinkedHashMap<String,Object>();original.forEach((key,value)->row.put(key.toString(),value));
  if(row.get("items") instanceof List<?> items){row.put("items",items.stream().map(item->map(item,path)).toList());return row;}
  Object state=row.containsKey("state")?row.get("state"):row.get("status");
  List<String> actions=List.of();
  if(path.matches("/api/v1/admin/reconciliation/issues(/[^/]+)?")){
   if(!"RESOLVED".equals(state))actions=List.of("REPAIR");
  }else if(path.matches("/api/v1/admin/(order|payment|aftersale)-messages/outbox") && "SENT".equals(state)
    && (Boolean.TRUE.equals(row.get("wire_frozen")) || row.get("wire_frozen") instanceof Number frozen && frozen.intValue()==1)){
   // Broker确认不代表全部预期目标收到；只对有冻结原文的历史Outbox给出明确补投候选动作。
   // 平台写入口仍重验最新身份、原文、RUNNING租约与审计，后台发布仍执行资金guard和领取围栏。
   actions=List.of("REDELIVER_ORIGINAL");
  }else if("MANUAL_REVIEW".equals(state))actions=List.of("RETRY");
  row.put("allowedActions",actions);return row;
 }
}
