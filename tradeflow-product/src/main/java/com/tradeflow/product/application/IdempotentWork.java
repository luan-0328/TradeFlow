package com.tradeflow.product.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.product.infrastructure.ProductMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 商品服务的同步本地幂等边界；幂等结果与业务数据必须同事务提交。 */
@Component
public class IdempotentWork {
    private final ProductMapper mapper; private final ObjectMapper json; private final TransactionTemplate tx;
    public IdempotentWork(ProductMapper mapper,ObjectMapper json,PlatformTransactionManager manager) {
        this.mapper=mapper;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    /**
     * 作用域为主体+操作+键；唯一键串行化同请求，同键异参409。
     * 崩溃在提交前则全部回滚，提交后丢响应则原键重放快照；函数中严禁RPC。
     */
    public <T> T run(String type,String actor,String operation,String key,Object payload,Class<T> resultType,Function<String,T> work) {
        return runInternal(type,actor,operation,key,payload,resultType,work,null,null);
    }
    /** 新经营幂等包含商家上下文；旧成功键只在旧摘要相同且原业务事实能证明当前商家时安全重放。 */
    public <T> T runScoped(String type,String actor,String operation,String key,String merchant,Object payload,Class<T> resultType,Function<String,T> work) {
        var scoped=new TreeMap<String,Object>();scoped.put("merchantId",merchant);scoped.put("input",payload);
        return runInternal(type,actor,operation,key,scoped,resultType,work,hash(payload),merchant);
    }
    /** 兼容分支只读原成功快照，绝不改写历史摘要、补执行业务或为异店返回旧结果。 */
    private <T> T runInternal(String type,String actor,String operation,String key,Object payload,Class<T> resultType,Function<String,T> work,String legacyHash,String merchant) {
        if(key==null || !key.matches("[A-Za-z0-9_.:-]{1,64}")) throw new BusinessException(400,"INVALID_IDEMPOTENCY_KEY","需要1至64位稳定幂等键");
        String hash=hash(payload);
        String prefix=switch(operation) { case "CREATE_PRODUCT"->"prdreq_";case "ADJUST_INVENTORY"->"rcp_";case "CREATE_QUOTE"->"quo_";default->"req_"; };
        String no=prefix+UUID.randomUUID().toString().replace("-","");
        for(int attempt=0;attempt<3;attempt++) {
            try { return tx.execute(status->{
            mapper.claim(type,actor,operation,key,hash,no);
            var existing=mapper.lockRequest(type,actor,operation,key);
            if(!hash.equals(existing.requestHash())) {
                if(legacyHash!=null && legacyHash.equals(existing.requestHash()) && "SUCCEEDED".equals(existing.state())
                        && merchant.equals(mapper.legacyRequestMerchant(operation,existing.businessNo(),existing.resultSnapshot())))
                    return decode(existing.resultSnapshot(),resultType);
                throw new BusinessException(409,"IDEMPOTENCY_CONFLICT","同一幂等键不能用于不同请求或商家");
            }
            if("SUCCEEDED".equals(existing.state())) return decode(existing.resultSnapshot(),resultType);
            T result=work.apply(existing.businessNo());
            mapper.completed(type,actor,operation,key,encode(result)); return result;
            }); } catch(org.springframework.dao.TransientDataAccessException contention) {
                // 原事务已整体回滚；固定业务号与摘要在新事务重试，不在锁内等待。
                if(attempt==2) throw new BusinessException(503,"DATABASE_BUSY","数据库竞争，请保留原幂等键稍后重试");
                try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(20,81)); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new BusinessException(503,"REQUEST_INTERRUPTED","请求中断，请保留原幂等键查询或重试"); }
            }
        }
        throw new IllegalStateException("不可达的重试分支");
    }
    /** 规范化由用例先完成；摘要不包含动态时间、Token或客户端身份头。 */
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA256不可用"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("幂等快照序列化失败"); }
    }
    private <T> T decode(String value,Class<T> type) {
        try { return json.readValue(value,type); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("已提交幂等快照无法解析"); }
    }
}
