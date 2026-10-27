package com.tradeflow.product.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.product.application.*;
import com.tradeflow.product.domain.ProductRows.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅由entry-redis.py --catalog-jvm创建的独占回环Redis执行，验证真实SET NX、TTL、Lua及序列化。 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_CACHE_REDIS_PORT",matches="[0-9]+")
class CatalogCacheRedisTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private CatalogCache cache;
    private static final String PREFIX="tradeflow:catalog:v1:{catalog}:";
    public record Metadata(String name,List<String> skus) { }
    @BeforeEach void setup() {
        connection=new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(System.getenv("TRADEFLOW_CACHE_REDIS_PORT")));
        connection.afterPropertiesSet();connection.start();redis=new StringRedisTemplate(connection);
        cache=new CatalogCache(redis,new ObjectMapper());cache.evictAfterCommit();
    }
    @AfterEach void close(){connection.destroy();}
    private String key(String logical){return PREFIX+redis.opsForValue().get(PREFIX+"generation")+":"+logical;}

    @Test void positiveNegativeSnapshotsAndRandomExpiry() {
        var loads=new AtomicInteger();
        var expected=new Metadata("商品",List.of("sku1"));
        assertThat(cache.get("detail:1",Metadata.class,()->{loads.incrementAndGet();return expected;})).isEqualTo(expected);
        assertThat(cache.get("detail:1",Metadata.class,()->{throw new AssertionError("不应回源");})).isEqualTo(expected);
        assertThat(redis.getExpire(key("detail:1"))).isBetween(59L,90L);
        assertThat(cache.get("detail:404",Metadata.class,()->{loads.incrementAndGet();return null;})).isNull();
        assertThat(cache.get("detail:404",Metadata.class,()->{throw new AssertionError("空值应命中");})).isNull();
        assertThat(redis.getExpire(key("detail:404"))).isBetween(9L,20L);
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test void twoInstancesRebuildHotKeyOnlyOnce() throws Exception {
        var other=new CatalogCache(redis,new ObjectMapper());var loads=new AtomicInteger();
        try(var threads=Executors.newFixedThreadPool(16)) {
            var start=new CountDownLatch(1);var jobs=new ArrayList<Future<String>>();
            for(int i=0;i<16;i++){final var instance=i%2==0?cache:other;jobs.add(threads.submit(()->{
                start.await();return instance.get("hot:1",String.class,()->{
                    loads.incrementAndGet();try{Thread.sleep(60);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    return "same";
                });
            }));}
            start.countDown();for(var job:jobs)assertThat(job.get(5,TimeUnit.SECONDS)).isEqualTo("same");
            assertThat(loads.get()).isEqualTo(1);
        }
    }

    @Test void oldRebuildCannotPublishAfterEditOrUnlockNewOwner() {
        String oldKey=key("edit:1");
        assertThat(cache.get("edit:1",String.class,()->{cache.evictAfterCommit();return "old";})).isEqualTo("old");
        assertThat(redis.hasKey(oldKey)).isFalse();
        assertThat(cache.get("edit:1",String.class,()->"new")).isEqualTo("new");
        String leased=key("lease:1");
        cache.get("lease:1",String.class,()->{
            redis.opsForValue().set(leased+":lock","new-owner",Duration.ofSeconds(5));return "late";
        });
        assertThat(redis.opsForValue().get(leased)).isNull();
        assertThat(redis.opsForValue().get(leased+":lock")).isEqualTo("new-owner");
    }

    @Test void invalidationRunsAfterCommitAndNotOnRollback() {
        String before=redis.opsForValue().get(PREFIX+"generation");
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evictAfterCommit();
            assertThat(redis.opsForValue().get(PREFIX+"generation")).isEqualTo(before);
            for(var callback:TransactionSynchronizationManager.getSynchronizations())callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(redis.opsForValue().get(PREFIX+"generation")).isEqualTo(before);
        }finally {TransactionSynchronizationManager.clearSynchronization();}
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evictAfterCommit();for(var callback:TransactionSynchronizationManager.getSynchronizations())callback.afterCommit();
            assertThat(redis.opsForValue().get(PREFIX+"generation")).isNotEqualTo(before);
        }finally {TransactionSynchronizationManager.clearSynchronization();}
    }

    @Test void cachedProductMetadataStillReadsInventoryOnEveryRequest() {
        ProductMapper mapper=mock(ProductMapper.class);
        var row=new ProductRow("1","merchant_demo","名称","详情","ON_SALE",0);
        var sku=new SkuRow("2","1","SKU2","规格",100,0,"ACTIVE",0,"merchant_demo","ON_SALE");
        when(mapper.product("1")).thenReturn(row);when(mapper.skus("1")).thenReturn(List.of(sku));
        when(mapper.inventory("warehouse_main","2")).thenReturn(new InventoryRow(5,0),new InventoryRow(3,2));
        // 只模拟事务资源，使用真实TransactionTemplate建立同步边界；不连接现有数据库。
        var manager=new AbstractPlatformTransactionManager(){
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        var service=new ProductService(mapper,mock(AuthorityClient.class),mock(IdempotentWork.class),manager,cache);
        assertThat(service.detail("1").skus().getFirst().availableQty()).isEqualTo(5);
        assertThat(service.detail("1").skus().getFirst().availableQty()).isEqualTo(3);
        verify(mapper,times(1)).product("1");verify(mapper,times(1)).skus("1");
        verify(mapper,times(2)).inventory("warehouse_main","2");
        String stored=redis.opsForValue().get(key("detail:1"));
        assertThat(stored).doesNotContain("availableQty","reservedQty");
    }
}
