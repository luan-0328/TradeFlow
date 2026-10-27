package com.tradeflow.user.application;

import com.tradeflow.user.api.UserModels.UserView;
import com.tradeflow.user.infrastructure.AccountMapper;
import java.util.HashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 邮箱注册的数据库边界；由独立Bean代理启动事务，SMTP和Redis校验均在调用之前完成。 */
@Service
public class RegistrationAccountWriter {
    private final AccountMapper accounts;
    public RegistrationAccountWriter(AccountMapper accounts) { this.accounts=accounts; }

    /** 唯一登录名仲裁并发；账号、邮箱验证时间和普通角色任一写入失败全部回滚，不允许自行提权。 */
    @Transactional(rollbackFor=Exception.class)
    public UserView create(String email,String passwordHash,String displayName) {
        var row=new HashMap<String,Object>();
        row.put("username",email);row.put("passwordHash",passwordHash);row.put("displayName",displayName);
        accounts.insertVerifiedEmail(row);
        String id=row.get("id").toString();
        accounts.grantRole(id,"USER");
        var account=accounts.byId(id);
        return new UserView(account.id(),account.username(),account.displayName(),account.status(),accounts.roles(id));
    }
}
