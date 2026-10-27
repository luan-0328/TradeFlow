package com.tradeflow.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.TradingContracts;
import com.tradeflow.contracts.TradingContracts.*;
import com.tradeflow.messaging.MessageOutbox;
import com.tradeflow.order.application.*;
import com.tradeflow.order.domain.OrderRows.*;
import com.tradeflow.order.infrastructure.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实Spring注解事务代理验证边界；事务管理器及Mapper为替身，不声称验证真实MySQL行锁。 */
class OrderTaskClaimServiceTest {
    private final TaskMapper tasks=mock(TaskMapper.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final AtomicBoolean active=new AtomicBoolean();
    private final OrderMapper orders=mock(OrderMapper.class);
    private final InventoryGateway inventory=mock(InventoryGateway.class);
    private final PaymentGateway payments=mock(PaymentGateway.class);
    private final OrderTransactions tx=mock(OrderTransactions.class);
    private final String no="ord_"+"a".repeat(32),reservation="res_"+"b".repeat(32);
    private OrderTaskClaimService service;
    private Task ready(int attempts) {return new Task("tsk_claim",no,"READY",attempts,0,null,null,Instant.now());}
    private Task running() {return new Task("tsk_claim",no,"RUNNING",1,1,"claimant",Instant.now().plusSeconds(30),Instant.now());}

    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenAnswer(call->{assertThat(active.get()).isFalse();active.set(true);return new SimpleTransactionStatus();});
        doAnswer(call->{assertThat(active.get()).isTrue();active.set(false);return null;}).when(manager).commit(any());
        doAnswer(call->{assertThat(active.get()).isTrue();active.set(false);return null;}).when(manager).rollback(any());
        var interceptor=new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory=new ProxyFactory(new OrderTaskClaimService(tasks));factory.setProxyTargetClass(true);factory.addAdvice(interceptor);
        service=(OrderTaskClaimService)factory.getProxy();
        when(tx.write(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());
    }
    private CreateOrderWorker worker() {
        return new CreateOrderWorker(orders,tasks,tx,service,inventory,payments,new ObjectMapper(),mock(MessageOutbox.class));
    }

    /** 真正经过Spring代理：所有领取Mapper操作在事务内，调用方拿到结果前已经提交。 */
    @Test void claimCommitsBeforeReturningToCaller() {
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        Task expected=running();
        when(tasks.next()).thenAnswer(call->{assertThat(active.get()).isTrue();return ready(0);});
        when(tasks.claim("tsk_claim","claimant")).thenAnswer(call->{assertThat(active.get()).isTrue();return 1;});
        when(tasks.byNo("tsk_claim")).thenAnswer(call->{assertThat(active.get()).isTrue();return expected;});
        assertThat(service.claimNext("claimant")).isSameAs(expected);
        assertThat(active.get()).isFalse();verify(manager).commit(any());verify(manager,never()).rollback(any());
    }

    /** 领取更新后的读回失败必须回滚，不能把不完整领取当成功返回。 */
    @Test void readbackFailureRollsBackClaim() {
        when(tasks.next()).thenReturn(ready(0));
        when(tasks.byNo("tsk_claim")).thenThrow(new DataAccessResourceFailureException("fixture"));
        assertThatThrownBy(()->service.claimNext("claimant")).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(active.get()).isFalse();verify(manager).rollback(any());verify(manager,never()).commit(any());
    }

    /** 恢复预算耗尽仍同事务转人工，不能创建新的领取版本或执行远程业务。 */
    @Test void exhaustedTaskMovesToManualWithoutClaim() {
        when(tasks.next()).thenReturn(ready(10));
        assertThat(service.claimNext("claimant")).isNull();
        verify(tasks).manual("tsk_claim","RECOVERY_BUDGET_EXHAUSTED");verify(tasks,never()).claim(anyString(),anyString());
        verify(manager).commit(any());assertThat(active.get()).isFalse();
    }

    /** 前两次锁竞争回滚后，第三次经过代理重新开事务，保留原最多3次预算。 */
    @Test void transientContentionRetriesOutsideRolledBackTransactions() {
        when(tasks.next()).thenThrow(new CannotAcquireLockException("one")).thenThrow(new CannotAcquireLockException("two")).thenReturn(null);
        worker().scan();
        verify(manager,times(3)).getTransaction(any());verify(manager,times(2)).rollback(any());verify(manager).commit(any());
        verifyNoInteractions(inventory,payments);assertThat(active.get()).isFalse();
    }

    /** 三次领取都失败时返回原数据库繁忙语义，不能无限重试或调用库存。 */
    @Test void contentionExhaustionKeepsBoundedFailure() {
        when(tasks.next()).thenThrow(new CannotAcquireLockException("fixture"));
        assertThatThrownBy(()->worker().scan()).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("DATABASE_BUSY"));
        verify(manager,times(3)).rollback(any());verify(manager,never()).commit(any());verifyNoInteractions(inventory,payments);
    }

    /** Worker真实推进至库存查询时，领取事务已提交；后续围栏失效则不写业务结果。 */
    @Test void inventoryRpcRunsAfterClaimCommit() {
        Task claimed=running();when(tasks.next()).thenReturn(ready(0),null);when(tasks.byNo("tsk_claim")).thenReturn(claimed);
        when(orders.intentByNo(no)).thenReturn(new Intent(no,"1","pay_1",reservation,"hash",null,"ORDER_CREATED",null,Instant.now()));
        Header header=new Header(no,"1","merchant_demo","pay_1",reservation,"quote_1",100,"CNY","CREATING","NONE","PENDING","NONE",Instant.now().plusSeconds(600),Instant.now(),"NONE");
        when(orders.order(no)).thenReturn(header);when(orders.items(no)).thenReturn(List.of(new Item("1","sku_1","商品","规格",100,1)));
        var command=new ReservationCommand(reservation,no,"quote_1","1",null,header.payDeadline(),List.of(new ReservationLine("sku_1",1)));
        when(inventory.query(reservation)).thenAnswer(call->{
            assertThat(active.get()).isFalse();verify(manager).commit(any());
            return new Reservation(reservation,no,"RESERVED",TradingContracts.reservationHash(command),null);
        });
        worker().scan();
        verify(inventory).query(reservation);verify(inventory,never()).reserve(any());verify(orders,never()).pending(anyString());
        assertThat(active.get()).isFalse();
    }
}
