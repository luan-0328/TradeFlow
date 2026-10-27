package com.tradeflow.user;

import com.tradeflow.user.api.UserModels;
import com.tradeflow.user.application.AccountService;
import com.tradeflow.user.application.LoginProtection;
import com.tradeflow.user.infrastructure.AccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 登录限额必须先于账号查询及昂贵BCrypt；错误凭据不能执行成功清理。 */
class LoginAdmissionTest {
    @Test void rateLimitStopsBeforeAccountReadAndPasswordHash() {
        var accounts=mock(AccountMapper.class);var protection=mock(LoginProtection.class);var passwords=mock(PasswordEncoder.class);
        doThrow(new LoginProtection.Limited(60)).when(protection).before("example","127.0.0.1");
        var service=new AccountService(accounts,protection,passwords,mock(JwtEncoder.class),mock(PlatformTransactionManager.class),
                new MockEnvironment().withProperty("tradeflow.security.issuer","test"));
        assertThatThrownBy(()->service.login(new UserModels.Login("EXAMPLE","wrong"),"127.0.0.1")).isInstanceOf(LoginProtection.Limited.class);
        verifyNoInteractions(accounts);verify(passwords,never()).matches(anyString(),any());verify(protection,never()).success(any());
    }
    @Test void unknownAccountRetainsReservationAndUsesSameCredentialFailure() {
        var accounts=mock(AccountMapper.class);var protection=mock(LoginProtection.class);var passwords=mock(PasswordEncoder.class);
        when(passwords.encode(anyString())).thenReturn("test-dummy-hash");
        when(protection.before(anyString(),anyString())).thenReturn(new LoginProtection.Reservation("test-key",1));
        var service=new AccountService(accounts,protection,passwords,mock(JwtEncoder.class),mock(PlatformTransactionManager.class),
                new MockEnvironment().withProperty("tradeflow.security.issuer","test"));
        assertThatThrownBy(()->service.login(new UserModels.Login("example","wrong"),"127.0.0.1"))
                .isInstanceOfSatisfying(com.tradeflow.common.BusinessException.class,e->assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS"));
        verify(passwords).matches("wrong","test-dummy-hash");verify(protection,never()).success(any());
    }
}
