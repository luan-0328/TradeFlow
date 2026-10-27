package com.tradeflow.user;

import com.tradeflow.user.application.LoginProtection;
import com.tradeflow.user.infrastructure.LoginEntryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 事务边界测试不代替真实MySQL并发测试；保证拒绝先提交以及成功仅释放本次账号版本。 */
class LoginProtectionTest {
    private PlatformTransactionManager manager(){
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());return manager;
    }
    @Test void sourceLimitCommitsBefore429AndDoesNotTouchAnotherAccount() {
        var mapper=mock(LoginEntryMapper.class);var manager=manager();
        when(mapper.current(anyString(),eq(900))).thenReturn(new LoginEntryMapper.Window(101,22,53));
        var guard=new LoginProtection(mapper,manager,new MockEnvironment());
        assertThatThrownBy(()->guard.before("example","127.0.0.1")).isInstanceOfSatisfying(LoginProtection.Limited.class,e->assertThat(e.retryAfterSeconds()).isEqualTo(53));
        verify(manager).commit(any());verify(manager,never()).rollback(any());
        verify(mapper,times(1)).reserve(anyString(),eq(900),anyInt());
    }
    @Test void accountLimitDoesNotDisappearOnTransactionRollback() {
        var mapper=mock(LoginEntryMapper.class);var manager=manager();
        when(mapper.current(anyString(),eq(900))).thenReturn(new LoginEntryMapper.Window(1,22,53),new LoginEntryMapper.Window(11,22,53));
        var guard=new LoginProtection(mapper,manager,new MockEnvironment());
        assertThatThrownBy(()->guard.before("example","127.0.0.1")).isInstanceOf(LoginProtection.Limited.class);
        verify(manager).commit(any());verify(manager,never()).rollback(any());
    }
    @Test void successReleasesOnlyItsOwnAccountReservationVersion() {
        var mapper=mock(LoginEntryMapper.class);var manager=manager();
        when(mapper.current(anyString(),eq(900))).thenReturn(new LoginEntryMapper.Window(7,22,53));
        var guard=new LoginProtection(mapper,manager,new MockEnvironment());
        var reservation=guard.before("example","127.0.0.1");guard.success(reservation);
        verify(mapper).release(reservation.key(),22);
        verify(manager,times(2)).commit(any());
    }
    @Test void unavailableDatabaseCannotAdmitLogin() {
        var mapper=mock(LoginEntryMapper.class);var manager=manager();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test offline")).when(mapper).reserve(anyString(),anyInt(),anyInt());
        assertThatThrownBy(()->new LoginProtection(mapper,manager,new MockEnvironment()).before("example","127.0.0.1"))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verify(manager).rollback(any());verify(mapper,never()).release(anyString(),anyLong());
    }
    @Test void rateResponseIncludesDatabaseBackoff() {
        var response=new com.tradeflow.user.api.DatabaseErrors().loginLimited(new LoginProtection.Limited(53),new org.springframework.mock.web.MockHttpServletRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("53");
    }
}
