package com.tradeflow.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.product.infrastructure.CatalogCache;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证Redis不可用时回源有界，业务异常不会被缓存层重试。 */
class CatalogCacheTest {
    @Test void redisFailureDoesNotRepeatBusinessLoader() {
        var redis=mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));
        var cache=new CatalogCache(redis,new ObjectMapper());
        var calls=new AtomicInteger();
        assertThatThrownBy(()->cache.get("detail:1",String.class,()->{
            calls.incrementAndGet();throw new BusinessException(404,"MISSING","不存在");
        })).isInstanceOf(BusinessException.class);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(cache.get("detail:2",String.class,()->"database")).isEqualTo("database");
        verify(redis,times(1)).opsForValue();
    }

    @Test void outageCannotCreateUnlimitedDatabaseConcurrency() throws Exception {
        var redis=mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));
        var cache=new CatalogCache(redis,new ObjectMapper());
        var active=new AtomicInteger();var maximum=new AtomicInteger();
        var release=new CountDownLatch(1);var entered=new CountDownLatch(4);
        try(var workers=Executors.newFixedThreadPool(24)) {
            var start=new CountDownLatch(1);
            var futures=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<24;i++) {final int id=i;futures.add(workers.submit(()->{
                try {start.await();cache.get("detail:"+id,String.class,()->{
                    int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);entered.countDown();
                    try {release.await(3,TimeUnit.SECONDS);return "value";}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    finally {active.decrementAndGet();}
                });}catch(BusinessException expected){assertThat(expected.getMessage()).contains("繁忙");}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
            }));}
            start.countDown();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);release.countDown();for(var future:futures)future.get(5,TimeUnit.SECONDS);
            assertThat(maximum.get()).isLessThanOrEqualTo(4);
        } finally {release.countDown();}
    }
}
