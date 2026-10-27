package com.tradeflow.user.application;

import com.tradeflow.user.infrastructure.LoginRateMapper;
import com.tradeflow.user.infrastructure.LoginEntryMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 过期登录限额按小批删除；多实例重复执行也不影响当前窗口计数。 */
@Configuration(proxyBeanMethods=false) @EnableScheduling
public class LoginRateCleanup {
    private final LoginRateMapper mapper;
    private final LoginEntryMapper entry;
    public LoginRateCleanup(LoginRateMapper mapper,LoginEntryMapper entry) { this.mapper=mapper;this.entry=entry; }
    /** 不持有业务锁、不调用RPC；单次最多500行，失败由下次调度继续。 */
    @Scheduled(fixedDelay=300000,initialDelay=300000)
    public void cleanup() { mapper.cleanup();entry.cleanup(); }
}
