package com.tradeflow.user;

import com.tradeflow.contracts.AuthContext;
import com.tradeflow.user.api.UserModels.*;
import com.tradeflow.user.application.*;
import com.tradeflow.user.domain.AccountRow;
import com.tradeflow.user.infrastructure.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 每店职级回归；模拟事务仅验证拒绝路径及撤销/审计编排，数据库竞争交由统一MySQL验证。 */
class MerchantRolesTest {
    private final AccountMapper accounts=mock(AccountMapper.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final Jwt jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("7").claim("token_version",0L).build();
    private final AccountRow target=new AccountRow("8","target","hash","成员","ACTIVE",0);
    private AccountService accountService() {
        when(manager.getTransaction(any())).thenAnswer(x -> new SimpleTransactionStatus());
        return new AccountService(accounts,mock(LoginProtection.class),mock(PasswordEncoder.class),mock(JwtEncoder.class),manager,
                new MockEnvironment().withProperty("tradeflow.security.issuer","test"));
    }
    /** 相同账号A店ADMIN、B店OPERATOR；全局旧管理员角色不能把B店提升为管理员。 */
    @Test void authorityIsIndependentForEachMerchant() {
        var auth=mock(AccountService.class);var data=mock(UserWorkspaceMapper.class);
        when(auth.authenticate(jwt)).thenReturn(new AuthContext("7","ACTIVE",List.of("MERCHANT_ADMIN"),List.of("merchant_a","merchant_b"),0,
                Map.of("merchant_a","ADMIN","merchant_b","OPERATOR")));
        when(manager.getTransaction(any())).thenAnswer(x -> new SimpleTransactionStatus());
        var service=UserWorkspaceServiceTest.transactional(new UserWorkspaceService(auth,accounts,data),manager);
        when(data.merchant("merchant_a")).thenReturn(Map.of("merchantId","merchant_a"));
        service.members(jwt,"merchant_a","merchant_a");
        assertThatThrownBy(() -> service.members(jwt,"merchant_b","merchant_b")).isInstanceOf(com.tradeflow.common.BusinessException.class);
        verify(data,never()).members("merchant_b");
        when(data.merchant("merchant_b")).thenReturn(Map.of("merchantId","merchant_b"));
        assertThat(service.merchant(jwt,"merchant_b","merchant_b")).containsEntry("merchantId","merchant_b");
    }
    /** capabilities只从有效本店职级聚合导航；旧全局ADMIN不能让操作员出现管理员动作。 */
    @Test void capabilitiesAggregateAuthoritativeMemberRoles() {
        var service=accountService();
        when(accounts.byId("7")).thenReturn(new AccountRow("7","operator","hash","操作员","ACTIVE",0));
        when(accounts.roles("7")).thenReturn(List.of("USER","MERCHANT_ADMIN"));
        when(accounts.merchants("7")).thenReturn(List.of("merchant_b"));
        when(accounts.merchantRoles("7")).thenReturn(List.of(Map.of("merchantId","merchant_b","role","OPERATOR")));
        var result=service.capabilities(jwt);
        assertThat(result.merchantRoles()).containsEntry("merchant_b","OPERATOR");
        assertThat(result.allowedActions()).contains("MERCHANT_ORDERS","MERCHANT_PRODUCTS").doesNotContain("MANAGE_PRODUCTS","MANAGE_INVENTORY");
    }
    /** 每店最后一个有效ADMIN不能降级或禁用，拒绝发生在任何成员或会话写入之前。 */
    @Test void lastAdminCannotBeDisabledOrDemoted() {
        var service=accountService();when(accounts.merchantMember("8","merchant_a")).thenReturn(Map.of("role","ADMIN","status","ACTIVE"));
        when(accounts.activeMerchantAdmins("merchant_a")).thenReturn(1L);
        for(var input:List.of(new MerchantMember("8","merchant_a",Status.ACTIVE,"降级",MerchantRole.OPERATOR),
                new MerchantMember("8","merchant_a",Status.DISABLED,"停用",MerchantRole.ADMIN))) {
            assertThatThrownBy(() -> service.changeMember(target,input)).hasMessageContaining("最后一个");
        }
        verify(accounts,never()).memberWithRole(anyString(),anyString(),anyString(),anyString());
        verify(accounts,never()).revoke(anyString(),anyString());
    }
    /** 可恢复路径：另一有效ADMIN已存在时允许原管理员降级，目标旧会话立即撤销。 */
    @Test void secondAdminAllowsDemotionAndRevokesTargetSessions() {
        var service=accountService();when(accounts.merchantMember("8","merchant_a")).thenReturn(Map.of("role","ADMIN","status","ACTIVE"));
        when(accounts.activeMerchantAdmins("merchant_a")).thenReturn(2L);
        service.changeMember(target,new MerchantMember("8","merchant_a",Status.ACTIVE,"交接",MerchantRole.OPERATOR));
        verify(accounts).memberWithRole("8","merchant_a","ACTIVE","OPERATOR");verify(accounts).revoke("8","ACTIVE");
    }
    /** 全局停用同样会移除有效ADMIN，不能绕过成员接口的最后管理员保护。 */
    @Test void disablingAccountCannotStrandMerchant() {
        var service=accountService();when(accounts.lock("7")).thenReturn(new AccountRow("7","admin","hash","平台","ACTIVE",0));
        when(accounts.lock("8")).thenReturn(target);when(accounts.byId("8")).thenReturn(target);
        when(accounts.byId("7")).thenReturn(new AccountRow("7","admin","hash","平台","ACTIVE",0));
        when(accounts.roles("7")).thenReturn(List.of("PLATFORM_ADMIN"));
        when(accounts.merchantRoles("8")).thenReturn(List.of(Map.of("merchantId","merchant_a","role","ADMIN")));
        when(accounts.activeMerchantAdmins("merchant_a")).thenReturn(1L);
        assertThatThrownBy(() -> service.update(jwt,"8",new UserUpdate(Status.DISABLED,"停用"),"req","trace")).hasMessageContaining("最后一个");
        verify(accounts,never()).revoke(eq("8"),anyString());verify(manager).rollback(any());
    }
    /** 平台恢复历史无管理员商家时，职级、撤销和审计均先于事务提交。 */
    @Test void platformCanRestoreMissingAdminWithAtomicAudit() {
        var service=accountService();
        var writeStatus=new SimpleTransactionStatus();
        when(manager.getTransaction(argThat(definition -> definition!=null&&!definition.isReadOnly()))).thenReturn(writeStatus);var actor=new AccountRow("7","admin","hash","平台","ACTIVE",0);
        when(accounts.lock("7")).thenReturn(actor);when(accounts.lock("8")).thenReturn(target);
        when(accounts.byId("7")).thenReturn(actor);when(accounts.byId("8")).thenReturn(target);
        when(accounts.roles("7")).thenReturn(List.of("PLATFORM_ADMIN"));when(accounts.merchantExists("merchant_a")).thenReturn(1L);
        var input=new MerchantMember("8","merchant_a",Status.ACTIVE,"恢复管理员",MerchantRole.ADMIN);
        service.member(jwt,input,"req","trace");
        var ordered=inOrder(accounts,manager);
        ordered.verify(accounts).lockAdminRole();
        ordered.verify(accounts).memberWithRole("8","merchant_a","ACTIVE","ADMIN");
        ordered.verify(accounts).revoke("8","ACTIVE");
        ordered.verify(accounts).audit(eq("7"),eq("MERCHANT_MEMBER"),eq("8"),eq("req"),eq("trace"),eq("恢复管理员"),contains("ADMIN"));
        ordered.verify(manager).commit(writeStatus);
    }
    /** 新成员缺省操作员；旧Java身份快照不能意外恢复旧全局角色授权。 */
    @Test void missingRolesNeverEscalate() {
        assertThat(new MerchantMember("8","merchant_a",Status.ACTIVE,"新增").role()).isEqualTo(MerchantRole.OPERATOR);
        assertThat(new AuthContext("7","ACTIVE",List.of("MERCHANT_ADMIN"),List.of("merchant_a"),0).merchantRoles()).isEmpty();
    }
}
