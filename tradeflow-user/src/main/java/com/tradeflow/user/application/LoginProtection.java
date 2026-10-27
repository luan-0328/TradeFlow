package com.tradeflow.user.application;

import com.tradeflow.user.infrastructure.LoginEntryMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 登录前先持久化来源及账号准入，BCrypt不占数据库事务；数据库不可用直接失败，不降级。 */
@Component
public class LoginProtection {
    private final LoginEntryMapper mapper;
    private final TransactionTemplate tx;
    private final int seconds,accountMaximum,sourceMaximum;
    public LoginProtection(LoginEntryMapper mapper,PlatformTransactionManager manager,Environment environment){
        this.mapper=mapper;tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        seconds=environment.getProperty("tradeflow.login-protection.window-seconds",Integer.class,900);
        accountMaximum=environment.getProperty("tradeflow.login-protection.account-maximum",Integer.class,10);
        sourceMaximum=environment.getProperty("tradeflow.login-protection.source-maximum",Integer.class,100);
        if(seconds<1 || seconds>86400 || accountMaximum<1 || accountMaximum>100000 || sourceMaximum<1 || sourceMaximum>100000)
            throw new IllegalArgumentException("登录保护配置不合法");
    }
    /** 固定顺序来源→账号锁；失败/拒绝次数独立提交，异常不能回滚限额，禁止在其中调用RPC或验密。 */
    public Reservation before(String username,String source){
        String account=key("account:"+seconds+":",username), origin=key("source:"+seconds+":",source);
        Outcome result=tx.execute(status->{
            mapper.reserve(origin,seconds,sourceMaximum);
            var sourceWindow=mapper.current(origin,seconds);
            if(sourceWindow.attempts()>sourceMaximum)return new Outcome(null,sourceWindow.retryAfterSeconds());
            mapper.reserve(account,seconds,accountMaximum);
            var accountWindow=mapper.current(account,seconds);
            if(accountWindow.attempts()>accountMaximum)return new Outcome(null,accountWindow.retryAfterSeconds());
            return new Outcome(new Reservation(account,accountWindow.windowId()),0);
        });
        if(result.retry>0)throw new Limited(result.retry);
        return result.reservation;
    }
    /** 成功释放本次账号预留，不清除历史失败及并发未决请求；来源保留全部尝试，防止多账号轮换绕过。 */
    public void success(Reservation reservation){tx.executeWithoutResult(status->mapper.release(reservation.key,reservation.windowId));}
    private static String key(String scope,String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((scope+value).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public record Reservation(String key,long windowId){}
    private record Outcome(Reservation reservation,int retry){}
    public static final class Limited extends RuntimeException {
        private final int retryAfterSeconds;
        public Limited(int seconds){super("登录尝试过多，请在"+seconds+"秒后重试");retryAfterSeconds=seconds;}
        public int retryAfterSeconds(){return retryAfterSeconds;}
    }
}
