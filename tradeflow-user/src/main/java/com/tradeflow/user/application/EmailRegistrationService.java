package com.tradeflow.user.application;

import com.tradeflow.common.BusinessException;
import com.tradeflow.user.api.UserModels.*;
import com.tradeflow.user.infrastructure.EmailCodeStore;
import com.tradeflow.user.infrastructure.RegistrationMailer;
import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 邮箱注册编排：Redis验证原子消费，BCrypt在数据库事务外，只有最终账号写入才开启本地短事务。 */
@Service
public class EmailRegistrationService {
    private final EmailCodeStore codes;
    private final RegistrationMailer mailer;
    private final RegistrationAccountWriter writer;
    private final PasswordEncoder passwords;
    private final SecureRandom random=new SecureRandom();
    public EmailRegistrationService(EmailCodeStore codes,RegistrationMailer mailer,
            RegistrationAccountWriter writer,PasswordEncoder passwords) {
        this.codes=codes;this.mailer=mailer;this.writer=writer;this.passwords=passwords;
    }

    /** 先检查邮件配置，再领取冷却和小时额度；SMTP失败/超时保留验证码与冷却，避免未知发送结果引发重复发送。 */
    public EmailCodeResult send(String input) {
        String email=normalizeEmail(input);
        mailer.requireConfigured();
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));
        codes.issue(email,code);
        mailer.send(email,code);
        return new EmailCodeResult(60,300);
    }

    /** 先校验输入并在事务外哈希，再原子消费验证码；数据库失败不恢复旧码，用户可登录确认或冷却后重发。 */
    public UserView register(Register input) {
        String email=normalizeEmail(input.email());
        AccountService.validatePassword(input.password());
        String name=input.displayName()==null?"":input.displayName().strip();
        if(name.isEmpty()||name.length()>64)throw new BusinessException(400,"VALIDATION_ERROR","显示名称为1至64个字符");
        if(input.code()==null||!input.code().matches("[0-9]{6}"))throw new BusinessException(400,"INVALID_EMAIL_CODE","请输入6位邮箱验证码");
        String hash=passwords.encode(input.password());
        codes.consume(email,input.code());
        return writer.create(email,hash,name);
    }

    /** 邮箱大小写统一；当前只支持常见ASCII邮箱，不限制收件人必须为QQ邮箱，拒绝换行和显示名注入。 */
    public static String normalizeEmail(String input) {
        String email=input==null?"":input.strip().toLowerCase(Locale.ROOT);
        String local="[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*";
        String label="[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
        if(email.length()>254||!email.matches(local+"@"+label+"(?:\\."+label+")+" )||email.indexOf('@')>64)
            throw new BusinessException(400,"INVALID_EMAIL","请输入有效的邮箱地址");
        return email;
    }

    /** 所有实例共享Redis剩余TTL；客户端倒计时只作展示，不能代替后端限额。 */
    public static class Limited extends RuntimeException {
        private final long retry;
        public Limited(long retry) {super("邮件发送过于频繁，请在"+retry+"秒后重试");this.retry=retry;}
        public long retryAfterSeconds(){return retry;}
    }
}
