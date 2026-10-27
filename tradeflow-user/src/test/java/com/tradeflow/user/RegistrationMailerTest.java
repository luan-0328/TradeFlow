package com.tradeflow.user;

import com.icegreen.greenmail.util.*;
import com.tradeflow.common.BusinessException;
import com.tradeflow.user.infrastructure.RegistrationMailer;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** 真正JavaMail SMTP协议收发验证，邮件只进入独占回环GreenMail，不访问QQ或外部收件箱。 */
class RegistrationMailerTest {
    @Test void sendsUtf8MessageToExactRecipientOverLocalSmtp() throws Exception {
        GreenMail smtp=new GreenMail(new ServerSetup(0,"127.0.0.1","smtp"));
        smtp.setUser("buyer@example.test","buyer","unused");smtp.start();
        try {
            var sender=new JavaMailSenderImpl();sender.setHost("127.0.0.1");sender.setPort(smtp.getSmtp().getPort());
            var environment=new MockEnvironment().withProperty("spring.mail.username","sender@example.test")
                    .withProperty("spring.mail.properties.mail.smtp.auth","false");
            new RegistrationMailer(sender,environment).send("buyer@example.test","123456");
            assertThat(smtp.waitForIncomingEmail(3000,1)).isTrue();
            var message=smtp.getReceivedMessages()[0];
            assertThat(message.getSubject()).isEqualTo("TradeFlow 注册验证码");
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo("buyer@example.test");
            assertThat(message.getContent().toString()).contains("123456","5分钟");
        }finally{smtp.stop();}
    }
    @Test void smtpFailureIsSanitizedAndNotRetried() {
        var sender=org.mockito.Mockito.mock(org.springframework.mail.javamail.JavaMailSender.class);
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("sensitive smtp detail"))
                .when(sender).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        var mailer=new RegistrationMailer(sender,new MockEnvironment().withProperty("spring.mail.username","sender@example.test")
                .withProperty("spring.mail.properties.mail.smtp.auth","false"));
        assertThatThrownBy(()->mailer.send("buyer@example.test","123456")).isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("sensitive").hasMessageNotContaining("123456");
        org.mockito.Mockito.verify(sender,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
    }
}
