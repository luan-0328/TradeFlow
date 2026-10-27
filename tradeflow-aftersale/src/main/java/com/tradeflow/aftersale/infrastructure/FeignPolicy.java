package com.tradeflow.aftersale.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.*;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.*;

/** 业务重试由持久任务控制，禁止Feign自行重试未知写请求或记录凭据。 */
@Configuration(proxyBeanMethods=false)
public class FeignPolicy {
    /** 同步RPC继承安全日志关联号，不作为权限，也不打印凭据。 */
 @Bean public feign.RequestInterceptor traceRpcHeaders(){return template->com.tradeflow.observability.TracePropagation.headers().forEach((name,value)->template.header(name,value));}
 public static final class RemoteFailure extends RuntimeException {
        private final int status;private final String code;
        public RemoteFailure(int status,String code) { super("RPC_"+code);this.status=status;this.code=code; }
        public int status() { return status; } public String code() { return code; }
    }
    @Bean public Retryer noHiddenRpcRetry() { return Retryer.NEVER_RETRY; }
    @Bean public Logger.Level noCredentialLogs() { return Logger.Level.NONE; }
    /** 只保留稳定错误码，绝不将URL、请求头、地址或响应原文存入任务错误。 */
    @Bean public ErrorDecoder safeRpcErrorDecoder(ObjectMapper json) {
        return (method,response)->{
            String code="REMOTE_HTTP_"+response.status();
            if(response.body()!=null)try(var input=response.body().asInputStream()) {
                var body=json.readTree(input.readNBytes(65536));
                String candidate=body.path("code").asText();if(candidate.matches("[A-Z0-9_]{1,64}"))code=candidate;
            } catch(Exception ignored) { }
            return new RemoteFailure(response.status(),code);
        };
    }
}

