package com.tradeflow.user.infrastructure;

import com.tradeflow.common.BusinessException;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Spring Boot邮件适配器，QQ邮箱仅作SMTP发件方；凭据从环境注入，不记录验证码、正文或SMTP异常详情。 */
@Component
public class RegistrationMailer {
    private final JavaMailSender sender;
    private final String from;
    private final String password;
    private final boolean auth;
    public RegistrationMailer(JavaMailSender sender,Environment environment) {
        this.sender=sender;
        from=environment.getProperty("spring.mail.username","");
        password=environment.getProperty("spring.mail.password","");
        auth=environment.getProperty("spring.mail.properties.mail.smtp.auth",Boolean.class,true);
    }
    /** 未配置SMTP时只禁用发送入口，不影响历史用户登录；本地SMTP验收显式关闭认证。 */
    public void requireConfigured() {
        if(from.isBlank()||(auth&&password.isBlank()))
            throw new BusinessException(503,"MAIL_NOT_CONFIGURED","邮件服务尚未配置，请联系管理员");
    }
    /** 同步调用在事务外，超时视为投递未知；不自动重发、不撤销可能已经送达的验证码。 */
    public void send(String email,String code) {
        requireConfigured();
        SimpleMailMessage message=new SimpleMailMessage();
        message.setFrom(from);message.setTo(email);message.setSubject("TradeFlow 注册验证码");
        message.setText("你的 TradeFlow 注册验证码是："+code+"\n验证码5分钟内有效，请勿泄露。若非本人操作，请忽略此邮件。");
        try{sender.send(message);}
        catch(org.springframework.mail.MailException failure){
            throw new BusinessException(503,"MAIL_DELIVERY_UNCONFIRMED","邮件发送结果暂未确认，请检查收件箱及垃圾邮件，60秒后可重试");
        }
    }
}
