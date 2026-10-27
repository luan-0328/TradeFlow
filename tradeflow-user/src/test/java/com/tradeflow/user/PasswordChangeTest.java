package com.tradeflow.user;

import com.tradeflow.user.api.UserModels.PasswordChange;
import com.tradeflow.user.application.*;
import com.tradeflow.user.domain.AccountRow;
import com.tradeflow.user.infrastructure.AccountMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 账号改密围栏回归：昂贵计算在事务外，写入失败回滚且不能审计成成功。 */
class PasswordChangeTest {
    @Test void bcryptRunsBeforeWriteTransactionAndCasUsesOriginalHashAndVersion() {
        var accounts=mock(AccountMapper.class);var encoder=mock(PasswordEncoder.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(x -> new SimpleTransactionStatus());
        when(encoder.encode(anyString())).thenReturn("new-hash");when(encoder.matches("Old-password1!","old-hash")).thenReturn(true);
        when(accounts.byId("7")).thenReturn(new AccountRow("7","example","old-hash","用户","ACTIVE",3));
        when(accounts.roles("7")).thenReturn(List.of("USER"));when(accounts.merchants("7")).thenReturn(List.of());
        when(accounts.password("7","old-hash",3,"new-hash")).thenReturn(1);
        var service=new AccountService(accounts,mock(LoginProtection.class),encoder,mock(JwtEncoder.class),manager,new MockEnvironment().withProperty("tradeflow.security.issuer","test"));
        var jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("7").claim("token_version",3L).build();
        service.password(jwt,new PasswordChange("Old-password1!","New-password2!"),"req","trace");
        var ordered=inOrder(encoder,accounts,manager);
        ordered.verify(encoder).matches("Old-password1!","old-hash");ordered.verify(encoder).encode("New-password2!");
        // authenticate使用短只读事务；新密码编码之后才开始改密写事务。
        ordered.verify(manager).getTransaction(argThat(definition -> !definition.isReadOnly()));ordered.verify(accounts).password("7","old-hash",3,"new-hash");
        verify(manager,atLeastOnce()).commit(any());
    }
    @Test void passwordInputNeverPrintsSecrets() {assertThat(new PasswordChange("old-secret","new-secret").toString()).isEqualTo("PasswordChange[REDACTED]");}
}
