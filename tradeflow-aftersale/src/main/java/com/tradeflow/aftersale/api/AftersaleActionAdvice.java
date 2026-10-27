package com.tradeflow.aftersale.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.aftersale.application.AftersaleService;
import com.tradeflow.aftersale.domain.Aftersale;
import com.tradeflow.common.ApiResponse;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 只扩展本包已经完成身份和资源归属校验的售后响应，不承担鉴权，也不改变原字段或写状态机。 */
@RestControllerAdvice(basePackageClasses={AftersaleController.class,ReturnController.class})
public class AftersaleActionAdvice implements ResponseBodyAdvice<Object> {
 private final AftersaleService service;private final ObjectMapper json;
 public AftersaleActionAdvice(AftersaleService service,ObjectMapper json){this.service=service;this.json=json;}
 @Override public boolean supports(MethodParameter type,Class<? extends HttpMessageConverter<?>> converter){return type.getContainingClass()==AftersaleController.class||type.getContainingClass()==ReturnController.class;}
 /** 仅识别显式Aftersale类型；用户/商家依据已授权入口区分，平台恢复响应不能获得商家按钮。 */
 @Override public Object beforeBodyWrite(Object body,MethodParameter type,MediaType media,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
  if(!(body instanceof ApiResponse<?> api)||!"OK".equals(api.code()))return body;
  String path=request.getURI().getPath();String audience=path.startsWith("/api/v1/merchant/aftersales")?"merchant":path.startsWith("/api/v1/aftersales")?"user":"admin";
  Object data=map(api.data(),audience);
  return new ApiResponse<>(api.code(),api.message(),data,api.requestId(),api.traceId());
 }
 private Object map(Object data,String audience){
  if(data instanceof Aftersale a){Map<String,Object> result=json.convertValue(a,new TypeReference<LinkedHashMap<String,Object>>(){});result.put("allowedActions",service.allowedActions(a,audience));return result;}
  if(data instanceof List<?> list)return list.stream().map(item->map(item,audience)).toList();
  return data;
 }
}
