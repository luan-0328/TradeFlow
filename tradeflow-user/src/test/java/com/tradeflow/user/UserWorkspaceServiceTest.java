package com.tradeflow.user;

import com.tradeflow.contracts.AuthContext;
import com.tradeflow.user.api.UserModels.*;
import com.tradeflow.user.application.*;
import com.tradeflow.user.infrastructure.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** U1/U2边界回归源码；MySQL默认唯一键和真实事务并发须在统一测试阶段执行。 */
class UserWorkspaceServiceTest {
    private final AccountService auth=mock(AccountService.class);
    private final AccountMapper accounts=mock(AccountMapper.class);
    private final UserWorkspaceMapper data=mock(UserWorkspaceMapper.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final Jwt jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("7").claim("token_version",0L).build();
    private UserWorkspaceService service() {
        when(manager.getTransaction(any())).thenAnswer(x -> new SimpleTransactionStatus());
        when(auth.authenticate(jwt)).thenReturn(new AuthContext("7","ACTIVE",List.of("MERCHANT_ADMIN"),List.of("merchant_a"),0,java.util.Map.of("merchant_a","ADMIN")));
        return transactional(new UserWorkspaceService(auth,accounts,data),manager);
    }
    /** 手动构建业务对象时也装配真实注解事务代理，不能以裸new服务冒充注解已生效。 */
    static <T> T transactional(T target,PlatformTransactionManager manager) {
        var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var proxy=new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setProxyTargetClass(true);proxy.addAdvice(interceptor);
        @SuppressWarnings("unchecked") T result=(T)proxy.getProxy();
        return result;
    }
    /** 商家选择头不能扩大有效成员集合，拒绝后不读取他商家资料。 */
    @Test void selectedMerchantCannotGrantForeignMembership() {
        var service=service();
        assertThatThrownBy(() -> service.merchant(jwt,"merchant_b","merchant_b")).isInstanceOf(com.tradeflow.common.BusinessException.class);
        verifyNoInteractions(data);
    }
    /** 请求头与路径不一致也必须拒绝，避免切换商家后残留旧页面写入。 */
    @Test void selectedMerchantMustMatchResourcePath() {
        var service=service();
        assertThatThrownBy(() -> service.merchant(jwt,"merchant_a","merchant_b")).isInstanceOf(com.tradeflow.common.BusinessException.class);
        verifyNoInteractions(data);
    }
    /** 旧版本地址不能先清除当前默认地址，失败不制造部分副作用。 */
    @Test void staleAddressVersionNeverClearsDefault() {
        var service=service();when(data.address("7","11")).thenReturn(Map.of("id","11","version",2L));
        assertThatThrownBy(() -> service.address(jwt,"11",new AddressInput("收件人","123","地址",true,1))).isInstanceOf(com.tradeflow.common.BusinessException.class);
        verify(data,never()).clearDefault(anyString(),anyString());verify(data,never()).updateAddress(anyMap());
        verify(manager).rollback(any());
    }
    /** 重复已读不要求更新行数为1，已读时间由SQL保留首次值。 */
    @Test void repeatedReadIsAcceptedButForeignNotificationIsHidden() {
        var service=service();when(data.notificationExists("7","11")).thenReturn(1L);when(data.readNotification("7","11")).thenReturn(0);
        service.read(jwt,"11");verify(manager).commit(any());
        assertThatThrownBy(() -> service.read(jwt,"12")).isInstanceOf(com.tradeflow.common.BusinessException.class);
        verify(data,never()).readNotification("7","12");
    }
    /** 停用只写经营状态，不能删除历史成员或撤销他们的处置身份。 */
    @Test void disableMerchantPreservesHistoricalMembership() {
        var service=service();when(auth.authenticate(jwt)).thenReturn(new AuthContext("7","ACTIVE",List.of("PLATFORM_ADMIN"),List.of(),0,java.util.Map.of()));
        when(data.lockMerchant("merchant_a")).thenReturn(Map.of("merchantId","merchant_a","status","ACTIVE","version",0L));
        when(data.merchantStatus("merchant_a","DISABLED",0)).thenReturn(1);
        when(data.merchant("merchant_a")).thenReturn(Map.of("merchantId","merchant_a","status","DISABLED","version",1L));
        assertThat(service.state(jwt,"merchant_a",new MerchantState(Status.DISABLED,"平台停用",0),"req","trace")).containsEntry("status","DISABLED");
        verify(accounts,never()).member(anyString(),anyString(),anyString());verify(accounts,never()).revoke(anyString(),anyString());
    }
}
