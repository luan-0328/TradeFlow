package com.tradeflow.user;

import com.tradeflow.common.BusinessException;
import com.tradeflow.user.application.EmailRegistrationService.Limited;
import com.tradeflow.user.infrastructure.EmailCodeStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

/** 只连接fixture新建的独占Redis，验证生产Lua实际并发、过期与错误次数；禁止回退到开发Redis。 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_EMAIL_REDIS_PORT",matches="[0-9]+")
class EmailCodeRedisTest {
    static final String PREFIX="tradeflow:email:{register}:";
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    EmailCodeStore store;
    @BeforeEach void connect() {
        factory=new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(System.getenv("TRADEFLOW_EMAIL_REDIS_PORT")));
        factory.afterPropertiesSet();redis=new StringRedisTemplate(factory);store=new EmailCodeStore(redis);
        redis.delete(PREFIX+"total:hour");
    }
    @AfterEach void close(){redis.delete(PREFIX+"total:hour");factory.destroy();}
    String email(){return UUID.randomUUID()+"@example.test";}
    String base(String email)throws Exception{return PREFIX+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8)));}

    @Test void cooldownAndCodeHaveSeparateTtlsAndCodesAreNotPlaintext()throws Exception {
        String email=email(),key=base(email);store.issue(email,"123456");
        assertThat(redis.getExpire(key+":cooldown")).isBetween(58L,60L);
        assertThat(redis.getExpire(key+":code")).isBetween(298L,300L);
        assertThat(redis.opsForHash().entries(key+":code").toString()).doesNotContain("123456");
        assertThatThrownBy(()->store.issue(email,"654321")).isInstanceOf(Limited.class);
        assertThat(redis.opsForValue().get(key+":hour")).isEqualTo("1");
        store.consume(email,"123456");
        assertThat(redis.hasKey(key+":cooldown")).isTrue();
        assertThatThrownBy(()->store.consume(email,"123456")).isInstanceOf(BusinessException.class);
    }
    @Test void concurrentIssuingAllowsExactlyOneSender()throws Exception {
        String email=email();var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<16;i++)tasks.add(pool.submit(()->{start.await();try{store.issue(email,"123456");return true;}catch(Limited expected){return false;}}));
            start.countDown();int accepted=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentVerificationConsumesExactlyOnce()throws Exception {
        String email=email();store.issue(email,"123456");var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<16;i++)tasks.add(pool.submit(()->{start.await();try{store.consume(email,"123456");return true;}catch(BusinessException expected){assertThat(expected.code()).isEqualTo("INVALID_EMAIL_CODE");return false;}}));
            start.countDown();int accepted=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void fifthWrongAttemptInvalidatesEvenCorrectCode()throws Exception {
        String email=email();store.issue(email,"123456");
        for(int i=0;i<5;i++)assertThatThrownBy(()->store.consume(email,"000000")).isInstanceOf(BusinessException.class);
        assertThat(redis.hasKey(base(email)+":code")).isFalse();
        assertThatThrownBy(()->store.consume(email,"123456")).isInstanceOf(BusinessException.class);
    }
    @Test void resendingReplacesOriginalAndEmailScopesDoNotMix()throws Exception {
        String a=email(),b=email();store.issue(a,"123456");store.issue(b,"654321");
        redis.delete(base(a)+":cooldown");store.issue(a,"111111");
        assertThatThrownBy(()->store.consume(a,"123456")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->store.consume(a,"654321")).isInstanceOf(BusinessException.class);
        store.consume(a,"111111");store.consume(b,"654321");
    }
    @Test void expiredCodeFailsAndExpiredCooldownAllowsSending()throws Exception {
        String email=email();store.issue(email,"123456");
        redis.expire(base(email)+":code",Duration.ofMillis(1));
        Thread.sleep(20);
        assertThatThrownBy(()->store.consume(email,"123456")).isInstanceOf(BusinessException.class);
        redis.expire(base(email)+":cooldown",Duration.ofMillis(1));Thread.sleep(20);
        store.issue(email,"654321");store.consume(email,"654321");
    }
    @Test void recipientAndGlobalHourlyBudgetsCannotBeBypassed()throws Exception {
        String email=email();
        for(int i=0;i<5;i++){store.issue(email,"123456");redis.delete(base(email)+":cooldown");}
        assertThatThrownBy(()->store.issue(email,"123456")).isInstanceOf(Limited.class);
        String fresh=email();redis.opsForValue().set(PREFIX+"total:hour","100",Duration.ofHours(1));
        assertThatThrownBy(()->store.issue(fresh,"123456")).isInstanceOf(Limited.class);
        assertThat(redis.hasKey(base(fresh)+":code")).isFalse();
    }
}
