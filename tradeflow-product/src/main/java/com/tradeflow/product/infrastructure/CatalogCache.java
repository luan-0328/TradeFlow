package com.tradeflow.product.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 公开商品资料缓存；不缓存库存/权益，不参与报价和资金判断。展示允许TTL内最终一致。 */
@Component
public class CatalogCache {
    private static final String PREFIX="tradeflow:catalog:v1:{catalog}:";
    private static final String GENERATION=PREFIX+"generation";
    private static final String ABSENT="~";
    // 同代际且仍持有租约才能写回；编辑提交或租约过期后，旧重建者不能污染新缓存。
    static final String PUBLISH="""
        if redis.call('GET', KEYS[1]) == ARGV[1] and redis.call('GET', KEYS[2]) == ARGV[2] then
          redis.call('SET', KEYS[3], ARGV[3], 'EX', ARGV[4]); return 1
        end
        return 0
        """;
    static final String RELEASE="""
        if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
        return 0
        """;
    private static final DefaultRedisScript<Long> WRITE=new DefaultRedisScript<>(PUBLISH,Long.class);
    private static final DefaultRedisScript<Long> UNLOCK=new DefaultRedisScript<>(RELEASE,Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    // 与数据库连接池预算配合；单实例最多4个缓存重建/故障回源，扩容时需合计预算。
    private final Semaphore origins=new Semaphore(4);
    private final ReentrantLock[] stripes=new ReentrantLock[256];
    private final AtomicLong unavailableUntil=new AtomicLong();

    public CatalogCache(StringRedisTemplate redis,ObjectMapper json) {
        this.redis=redis;this.json=json;
        for(int i=0;i<stripes.length;i++)stripes[i]=new ReentrantLock();
    }

    /**
     * 短空值TTL防重复穿透，随机正TTL分散雪崩；SET NX租约串行化跨实例热点重建。
     * 本机条带锁及回源配额始终生效；Redis异常冷却2秒后再探测，容量不足快速503。
     * loader必须是只读本地事务；其业务异常原样传播，绝不因缓存异常重复执行loader。
     */
    public <T> T get(String key,Class<T> type,Supplier<T> loader) {
        ReentrantLock local=stripes[(key.hashCode()&Integer.MAX_VALUE)%stripes.length];
        try {
            if(!local.tryLock(250,TimeUnit.MILLISECONDS))throw busy();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw busy();}
        try {
            String generation=null,cacheKey=null,lockKey=null,owner=UUID.randomUUID().toString();
            boolean acquired=false;
            if(available()) {
                try {
                    generation=redis.opsForValue().get(GENERATION);
                    if(generation==null) {
                        redis.opsForValue().setIfAbsent(GENERATION,UUID.randomUUID().toString());
                        generation=redis.opsForValue().get(GENERATION);
                        if(generation==null)throw new IllegalStateException("缓存代际缺失");
                    }
                    cacheKey=PREFIX+generation+":"+key;lockKey=cacheKey+":lock";
                    String value=redis.opsForValue().get(cacheKey);
                    if(value!=null)return decode(value,type);
                    acquired=Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey,owner,Duration.ofSeconds(10)));
                    if(!acquired) {
                        // 未抢到锁不能直接回源；有界等候其他节点生成结果，否则由调用方短暂退避。
                        for(int i=0;i<10;i++) {
                            Thread.sleep(20);value=redis.opsForValue().get(cacheKey);
                            if(value!=null)return decode(value,type);
                        }
                    }
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw busy();}
                catch(Exception failure){unavailable();}
                if(available()&&!acquired)throw busy();
            }
            try {
                if(!origins.tryAcquire())throw busy();
                T loaded;
                try {loaded=loader.get();} finally {origins.release();}
                if(acquired&&available()) {
                    try {
                        long ttl=loaded==null?ThreadLocalRandom.current().nextLong(10,21):ThreadLocalRandom.current().nextLong(60,91);
                        redis.execute(WRITE,List.of(GENERATION,lockKey,cacheKey),generation,owner,
                                loaded==null?ABSENT:json.writeValueAsString(loaded),Long.toString(ttl));
                    }catch(Exception failure){unavailable();}
                }
                return loaded;
            }finally {
                if(acquired)try {redis.execute(UNLOCK,List.of(lockKey),owner);}catch(Exception failure){unavailable();}
            }
        }finally {local.unlock();}
    }

    /** 业务提交后切换代际；回滚不失效。失败不回滚已提交业务，旧展示最多保留正TTL。 */
    public void evictAfterCommit() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){invalidate();}
            });
        }else invalidate();
    }

    /** 只改变展示代际，不删除库存或入口配额；缓存失败按短TTL收敛。 */
    private void invalidate() {
        try {redis.opsForValue().set(GENERATION,UUID.randomUUID().toString());}
        catch(Exception failure){unavailable();}
    }
    /** 空值标记与JSON快照严格区分；损坏内容走有界回源，不返回伪造业务资料。 */
    private <T> T decode(String value,Class<T> type) throws java.io.IOException {
        return ABSENT.equals(value)?null:json.readValue(value,type);
    }
    private boolean available(){return System.nanoTime()>=unavailableUntil.get();}
    /** 降级日志按冷却窗口合并，不打印缓存值、连接地址或认证材料。 */
    private void unavailable(){
        long now=System.nanoTime(),before=unavailableUntil.get();
        if(now>=before&&unavailableUntil.compareAndSet(before,now+TimeUnit.SECONDS.toNanos(2)))
            LoggerFactory.getLogger(CatalogCache.class).warn("商品缓存暂不可用，已启用有界数据库回源");
    }
    private BusinessException busy(){return new BusinessException(503,"CATALOG_BUSY","商品查询繁忙，请稍后重试");}
}
