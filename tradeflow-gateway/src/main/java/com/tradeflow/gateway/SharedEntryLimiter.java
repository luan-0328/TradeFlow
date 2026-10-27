package com.tradeflow.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Redis单次脚本同时仲裁分类总额和主体额度；多网关共享同一约束，无本机Map或故障放行。 */
@Component
public class SharedEntryLimiter {
    // 同类两个key有相同hash tag，Redis Cluster也保证单分片原子执行；拒绝不消耗另一桶额度、不延长TTL。
    static final String SCRIPT="""
            local retry=0
            for i=1,2 do
              local used=tonumber(redis.call('GET',KEYS[i]) or '0')
              if used>=tonumber(ARGV[i]) then
                local ttl=redis.call('TTL',KEYS[i])
                if ttl<1 then ttl=tonumber(ARGV[3]) end
                retry=math.max(retry,ttl)
              end
            end
            if retry>0 then return {0,retry} end
            for i=1,2 do
              local n=redis.call('INCR',KEYS[i])
              if n==1 then redis.call('EXPIRE',KEYS[i],ARGV[3]) end
            end
            return {1,0}
            """;
    private final ReactiveStringRedisTemplate redis;
    private final EntryProtectionProperties properties;
    public SharedEntryLimiter(ReactiveStringRedisTemplate redis,EntryProtectionProperties properties){this.redis=redis;this.properties=properties;}
    /** 先原子准入再转发；超时结果可能已计数，返回503且绝不自动重放写请求。 */
    public Mono<Decision> acquire(String category,String principal){
        var rule=properties.rule(category);
        String base=properties.getKeyPrefix()+"{:"+category+"}:";
        return redis.execute(RedisScript.of(SCRIPT,List.class),List.of(base+"total",base+hash(principal)),
                        List.of(Integer.toString(rule.getTotal()),Integer.toString(rule.getPerPrincipal()),Integer.toString(rule.getWindowSeconds())))
                .single().timeout(Duration.ofMillis(properties.getTimeoutMillis()))
                .map(values->new Decision(((Number)values.get(0)).longValue()==1,((Number)values.get(1)).longValue()));
    }
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public record Decision(boolean allowed,long retryAfterSeconds){}
}
