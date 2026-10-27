package com.tradeflow.user;

import com.tradeflow.common.BusinessException;
import com.tradeflow.user.api.UserModels.Register;
import com.tradeflow.user.application.*;
import com.tradeflow.user.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 编排边界回归；真实Redis原子性、SMTP和MySQL事务另由隔离集成测试覆盖。 */
class EmailRegistrationTest {
    final EmailCodeStore codes=mock(EmailCodeStore.class);
    final RegistrationMailer mailer=mock(RegistrationMailer.class);
    final RegistrationAccountWriter writer=mock(RegistrationAccountWriter.class);
    final PasswordEncoder passwords=mock(PasswordEncoder.class);
    final EmailRegistrationService service=new EmailRegistrationService(codes,mailer,writer,passwords);

    @Test void normalizesEmailButRejectsHeaderInjectionAndInvalidAddress() {
        assertThat(EmailRegistrationService.normalizeEmail(" Buyer@QQ.COM ")).isEqualTo("buyer@qq.com");
        for(String invalid:new String[]{"buyer", "a..b@qq.com","a@qq.com\r\nBcc:x@y.com","姓名@qq.com","a@-qq.com"})
            assertThatThrownBy(()->EmailRegistrationService.normalizeEmail(invalid)).isInstanceOf(BusinessException.class);
        assertThat(AccountService.normalize("platform_admin")).isEqualTo("platform_admin");
        assertThat(AccountService.normalize("Buyer@QQ.COM")).isEqualTo("buyer@qq.com");
    }
    @Test void invalidCodeNeverCreatesAccount() {
        when(passwords.encode(any())).thenReturn("hash");
        doThrow(new BusinessException(400,"INVALID_EMAIL_CODE","验证码失效")).when(codes).consume(any(),any());
        assertThatThrownBy(()->service.register(new Register("a@qq.com","Password-123!","买家","123456"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(writer);
    }
    @Test void weakPasswordDoesNotConsumeCode() {
        assertThatThrownBy(()->service.register(new Register("a@qq.com","weak","买家","123456"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(codes,writer);
    }
    @Test void verifiedRegistrationUsesHashAndConsumesBeforeDatabaseWrite() {
        when(passwords.encode("Password-123!")).thenReturn("hash");
        service.register(new Register("A@QQ.COM","Password-123!"," 买家 ","123456"));
        var order=inOrder(codes,writer);
        order.verify(codes).consume("a@qq.com","123456");
        order.verify(writer).create("a@qq.com","hash","买家");
    }
    @Test void missingMailConfigurationDoesNotConsumeSendQuota() {
        doThrow(new BusinessException(503,"MAIL_NOT_CONFIGURED","未配置")).when(mailer).requireConfigured();
        assertThatThrownBy(()->service.send("a@qq.com")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(codes);
    }
    @Test void cooldownRejectsBeforeSmtpAndFailureDoesNotAutomaticallyResend() {
        doThrow(new EmailRegistrationService.Limited(30)).when(codes).issue(any(),any());
        assertThatThrownBy(()->service.send("a@qq.com")).isInstanceOf(EmailRegistrationService.Limited.class);
        verify(mailer,never()).send(any(),any());
        reset(codes);
        doThrow(new BusinessException(503,"MAIL_DELIVERY_UNCONFIRMED","发送未知")).when(mailer).send(any(),any());
        assertThatThrownBy(()->service.send("a@qq.com")).isInstanceOf(BusinessException.class);
        verify(codes,times(1)).issue(eq("a@qq.com"),matches("[0-9]{6}"));
        verify(mailer,times(1)).send(eq("a@qq.com"),matches("[0-9]{6}"));
    }
    @Test void redisOutageRejectsIssuingAndVerificationWithoutLeakingConnectionDetails() {
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class,invocation->{
            throw new org.springframework.data.redis.RedisConnectionFailureException("private redis connection detail");
        });
        var unavailable=new EmailCodeStore(redis);
        assertThatThrownBy(()->unavailable.issue("a@qq.com","123456")).isInstanceOfSatisfying(BusinessException.class,
                failure->{assertThat(failure.status()).isEqualTo(503);assertThat(failure.getMessage()).doesNotContain("private");});
        assertThatThrownBy(()->unavailable.consume("a@qq.com","123456")).isInstanceOfSatisfying(BusinessException.class,
                failure->assertThat(failure.status()).isEqualTo(503));
    }
}
