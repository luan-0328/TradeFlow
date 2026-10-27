package com.tradeflow.user.infrastructure;

import com.tradeflow.common.BusinessException;
import com.tradeflow.user.application.EmailRegistrationService.Limited;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 注册验证码临时状态；同槽Lua同时仲裁冷却、单邮箱/全局额度，Redis不可用时禁止发送或注册。 */
@Component
public class EmailCodeStore {
    private static final String PREFIX="tradeflow:email:{register}:";
    // 先检查所有额度，再一起更新；拒绝不延长TTL、不替换现有验证码。验证码只保存随机盐与摘要。
    static final String ISSUE="""
        local retry=0
        if redis.call('EXISTS',KEYS[1])==1 then retry=math.max(1,redis.call('TTL',KEYS[1])) end
        for i=3,4 do
          local limit=tonumber(ARGV[i])
          if tonumber(redis.call('GET',KEYS[i]) or '0')>=limit then
            retry=math.max(retry,1,redis.call('TTL',KEYS[i]))
          end
        end
        if retry>0 then return retry end
        for i=3,4 do
          if redis.call('INCR',KEYS[i])==1 then redis.call('EXPIRE',KEYS[i],3600) end
        end
        redis.call('SET',KEYS[1],ARGV[1],'EX',60)
        redis.call('DEL',KEYS[2])
        redis.call('HSET',KEYS[2],'salt',ARGV[1],'digest',ARGV[2],'attempts',0)
        redis.call('EXPIRE',KEYS[2],300)
        return 0
        """;
    // 比较当前摘要并原子删除，只有一个并发请求可消费成功；累计5次错误后作废，不删除发送冷却。
    static final String CONSUME="""
        local digest=redis.call('HGET',KEYS[1],'digest')
        if not digest then return 0 end
        if digest==ARGV[1] then redis.call('DEL',KEYS[1]); return 1 end
        if redis.call('HINCRBY',KEYS[1],'attempts',1)>=5 then redis.call('DEL',KEYS[1]) end
        return 0
        """;
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT=new DefaultRedisScript<>(ISSUE,Long.class);
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT=new DefaultRedisScript<>(CONSUME,Long.class);
    private final StringRedisTemplate redis;
    public EmailCodeStore(StringRedisTemplate redis){this.redis=redis;}

    /** 每邮箱每小时最多5次、全局每小时100次，失败调用仍占额度；默认阈值按项目规模设定，不是投递成功量。 */
    public void issue(String email,String code) {
        try {
            String key=key(email),salt=UUID.randomUUID().toString();
            Long retry=redis.execute(ISSUE_SCRIPT,List.of(key+":cooldown",key+":code",key+":hour",PREFIX+"total:hour"),
                    salt,digest(salt+":"+email+":"+code),"5","100");
            if(retry==null)throw unavailable();
            if(retry>0)throw new Limited(retry);
        }catch(Limited|BusinessException failure){throw failure;}
        catch(RuntimeException failure){throw unavailable();}
    }

    /** 读取盐不消耗次数，比较/错误计数/删除由Lua一次完成；重发覆盖或并发消费时旧快照不能通过。 */
    public void consume(String email,String code) {
        try {
            String codeKey=key(email)+":code";
            Object salt=redis.opsForHash().get(codeKey,"salt");
            if(salt==null)throw invalid();
            Long accepted=redis.execute(CONSUME_SCRIPT,List.of(codeKey),digest(salt+":"+email+":"+code));
            if(accepted==null)throw unavailable();
            if(accepted!=1)throw invalid();
        }catch(BusinessException failure){throw failure;}
        catch(RuntimeException failure){throw unavailable();}
    }
    private String key(String email){return PREFIX+digest(email);}
    private String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private BusinessException invalid(){return new BusinessException(400,"INVALID_EMAIL_CODE","验证码错误、已失效或错误次数过多，请重新获取");}
    private BusinessException unavailable(){return new BusinessException(503,"EMAIL_VERIFICATION_UNAVAILABLE","邮箱验证暂时不可用，请稍后重试");}
}
