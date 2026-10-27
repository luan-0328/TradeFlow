package com.tradeflow.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.contracts.PaymentContracts.*;
import com.tradeflow.payment.application.PaymentService;
import com.tradeflow.payment.infrastructure.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 故障编排单元测试：使用真实恢复器，Mapper/RPC 为 Mock，不冒充数据库或 JVM 崩溃验收。 */
class PaymentRecoveryFaultTest {
    private static final String PAYMENT="pay_"+"a".repeat(32), ORDER="ord_"+"b".repeat(32);
    private final PaymentMapper db=mock(PaymentMapper.class);
    private final PaymentRemote remote=mock(PaymentRemote.class);
    private final Map<String,Object> row=new HashMap<>(), task=new HashMap<>();
    private PaymentService service;

    @BeforeEach void setup() {
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(i->new SimpleTransactionStatus());
        service=new PaymentService(db,remote,new TransactionTemplate(manager),new ObjectMapper().findAndRegisterModules(),
                new MockEnvironment().withProperty("tradeflow.internal.order-token","test-order")
                        .withProperty("tradeflow.callback-secret","test-callback"));
        row.putAll(Map.of("payment_no",PAYMENT,"order_no",ORDER,"user_id","1","amount_cents",100L,
                "currency","CNY","status","PENDING","review_required","NO","close_intent","NO","channel_version",0L));
        task.putAll(Map.of("state","RUNNING","claim_version",1L,"attempt_count",1L,"step","SUBMIT"));
        when(db.dueTask()).thenReturn(PAYMENT);
        when(db.lockTask(PAYMENT)).thenAnswer(i->new HashMap<>(task));
        doAnswer(i->{task.put("lease_owner",i.getArgument(1));task.put("lease_until",Instant.now().plusSeconds(30));return 1;}).when(db).claimTask(eq(PAYMENT),anyString());
        when(db.fenceTask(eq(PAYMENT),anyLong(),anyString())).thenAnswer(i->
                Objects.equals(task.get("claim_version"),i.getArgument(1)) && Objects.equals(task.get("lease_owner"),i.getArgument(2))
                && ((Instant)task.get("lease_until")).isAfter(Instant.now())?new HashMap<>(task):null);
        when(db.lock(PAYMENT)).thenAnswer(i->new HashMap<>(row));
        when(remote.intent(ORDER)).thenReturn(new Intent(ORDER,PAYMENT,"1",100,"CNY",Instant.now().plusSeconds(600),"PENDING_PAYMENT"));
        doAnswer(i->{task.put("step","QUERY");return 1;}).when(db).beforeSubmit(PAYMENT);
        doAnswer(i->{task.put("step",i.getArgument(4));return 1;}).when(db).finishTask(eq(PAYMENT),anyLong(),anyString(),anyString(),anyString(),nullable(String.class));
        doAnswer(i->{ChannelFact f=i.getArgument(0);row.put("status",f.status());row.put("channel_version",f.channelVersion());
            row.put("channel_transaction_no",f.channelTransactionNo());row.put("channel_updated_at",f.updatedAt());row.put("succeeded_at",f.succeededAt());return 1;
        }).when(db).fact(any());
    }

    /** 通道已扣款但写响应丢失：恢复步骤必须先落 QUERY，第二次只查询原号并一次入账。 */
    @Test void unknownWriteQueriesOriginalPaymentBeforeAnySecondSubmission() {
        when(remote.command(any(),eq(false))).thenThrow(new IllegalStateException("response lost"));
        when(remote.query(PAYMENT)).thenReturn(success(1));
        service.recover();
        var order=inOrder(db,remote);
        order.verify(db).beforeSubmit(PAYMENT);
        order.verify(remote).command(new ChannelCommand(PAYMENT,ORDER,100,"CNY"),false);
        verify(db).unknown(PAYMENT);
        verify(db).finishTask(eq(PAYMENT),eq(1L),anyString(),eq("READY"),eq("QUERY"),eq("通道结果未知，先查原支付号"));
        service.recover();
        verify(remote,times(1)).command(any(),anyBoolean());
        verify(remote).query(PAYMENT);
        verify(db).ledger(any());verify(db).refundAccount(any());
        verify(db,times(1)).outbox(anyString(),eq(PAYMENT),anyLong(),eq("PaymentSucceeded"),eq(ORDER),anyString());
    }

    /** RPC 返回期间任务被新版本接管：旧执行器不得应用资金或完成新任务。 */
    @Test void supersededClaimCannotApplyLateSuccess() {
        task.put("step","QUERY");
        when(remote.query(PAYMENT)).thenAnswer(i->{task.put("claim_version",2L);return success(1);});
        service.recover();
        verify(db,never()).fact(any());verify(db,never()).ledger(any());
        verify(db,never()).finishTask(anyString(),anyLong(),anyString(),anyString(),anyString(),nullable(String.class));
    }

    /** 即便还没有新执行者接管，过期租约也失去本地资金写回权，等待下一轮重新领取。 */
    @Test void expiredLeaseCannotApplyLateSuccess() {
        task.put("step","QUERY");
        task.put("lease_until",Instant.now().plusSeconds(30));
        when(remote.query(PAYMENT)).thenAnswer(i->{task.put("lease_until",Instant.now().minusSeconds(1));return success(1);});
        service.recover();
        verify(db,never()).fact(any());verify(db,never()).ledger(any());
        verify(db,never()).finishTask(anyString(),anyLong(),anyString(),anyString(),anyString(),nullable(String.class));
    }

    /** 不同主动查询重复提供同一成功事实或较新成功证据，只允许首笔创建流水、额度和 Outbox。 */
    @Test void repeatedSuccessfulFactsDoNotDuplicateFinancialEffects() {
        when(db.find(PAYMENT)).thenAnswer(i->new HashMap<>(row));
        when(remote.query(PAYMENT)).thenReturn(success(1),success(1),success(2));
        for(int i=0;i<3;i++)service.queryChannel("order","test-order",PAYMENT);
        verify(db,times(1)).ledger(any());verify(db,times(1)).refundAccount(any());
        verify(db,times(1)).outbox(anyString(),eq(PAYMENT),anyLong(),eq("PaymentSucceeded"),eq(ORDER),anyString());
        verify(db,never()).issue(anyString(),anyString());
    }

    private ChannelFact success(long version) {
        Instant at=Instant.parse("2026-10-25T00:00:00Z");
        return new ChannelFact(PAYMENT,ORDER,100,"CNY","SUCCEEDED","channel_1",version,at,at);
    }
}
