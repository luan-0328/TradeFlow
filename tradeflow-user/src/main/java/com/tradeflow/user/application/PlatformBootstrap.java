package com.tradeflow.user.application;

import com.tradeflow.user.infrastructure.AccountMapper;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 仅首次初始化平台账号；不提供HTTP提权入口，不覆盖或提升同名普通账号。 */
@Component
@ConditionalOnProperty(name="tradeflow.bootstrap.enabled",havingValue="true")
public class PlatformBootstrap implements ApplicationRunner {
    private final AccountMapper mapper; private final PasswordEncoder encoder; private final Environment env;
    private final TransactionTemplate tx;
    public PlatformBootstrap(AccountMapper mapper,PasswordEncoder encoder,Environment env,PlatformTransactionManager manager) {
        this.mapper=mapper;this.encoder=encoder;this.env=env;this.tx=new TransactionTemplate(manager);
    }
    /** 启动前显式注入初始密码，哈希在锁外；角色锚点避免多实例重复创建。 */
    @Override public void run(ApplicationArguments args) {
        String username=AccountService.normalize(env.getRequiredProperty("tradeflow.bootstrap.username"));
        String password=env.getRequiredProperty("tradeflow.bootstrap.password"); AccountService.validatePassword(password);
        String hash=encoder.encode(password);
        tx.executeWithoutResult(s->{
            mapper.lockAdminRole();
            if(mapper.activeAdmins()>0) return;
            if(mapper.byUsername(username)!=null) throw new IllegalStateException("初始平台账号名称已被占用，禁止自动提权");
            Map<String,Object> row=new HashMap<>();row.put("username",username);row.put("passwordHash",hash);row.put("displayName","平台管理员");
            mapper.insert(row);String id=row.get("id").toString();mapper.grantRole(id,"USER");mapper.grantRole(id,"PLATFORM_ADMIN");
            mapper.audit(id,"BOOTSTRAP_PLATFORM",id,"bootstrap","bootstrap","首次初始化平台账号","{\"roles\":[\"USER\",\"PLATFORM_ADMIN\"]}");
        });
    }
}
