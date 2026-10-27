package com.tradeflow.aftersale;

import com.tradeflow.aftersale.application.GovernanceService;
import com.tradeflow.aftersale.domain.Aftersale;
import com.tradeflow.aftersale.infrastructure.*;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 平台争议编排回归：只证明本地状态/原号任务调用，真实事务与远端退款收敛由集成测试覆盖。 */
class GovernanceServiceTest {
    private final GovernanceMapper mapper=mock(GovernanceMapper.class);
    private final AftersaleStore db=mock(AftersaleStore.class);
    private final Authority authority=mock(Authority.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final Jwt jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("7").claim("token_version",0L).build();
    private final String no="as_"+"a".repeat(32),dispute="dsp_"+"b".repeat(32);
    private GovernanceService service;
    @BeforeEach void setup(){
        when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        when(authority.check(eq(jwt),anyString())).thenReturn(new AuthContext("7","ACTIVE",List.of("USER","PLATFORM_ADMIN"),List.of(),0,java.util.Map.of()));
        service=new GovernanceService(mapper,db,authority,new TransactionTemplate(manager));
        when(mapper.dispute(dispute)).thenReturn(Map.of("dispute_no",dispute,"aftersale_no",no,"status","OPEN","version",1L));
        when(mapper.lockDispute(dispute)).thenReturn(Map.of("dispute_no",dispute,"aftersale_no",no,"status","OPEN","version",1L));
        when(db.get(no,true)).thenReturn(aftersale("REFUND_ONLY","PENDING_REVIEW","7"));
    }
    private Aftersale aftersale(String type,String status,String user){return new Aftersale(no,"ord_original",user,"merchant_a",type,"买家原因",status,"rfd_original","NONE",100L,"CNY",null);}
    /** 平台APPROVE必须排入真实原售后退款任务，不能只改争议展示结果。 */
    @Test void approveRefundOnlyCreatesOriginalRefundTaskBeforeCommit(){
        when(mapper.approve(no)).thenReturn(1);when(mapper.decide(dispute,1,"APPROVE","平台认定退款","7")).thenReturn(1);
        service.decide(jwt,dispute,new GovernanceService.Decision("APPROVE"," 平台认定退款 ",1));
        var order=inOrder(authority,db,mapper,manager);
        order.verify(authority).check(jwt,"admin");order.verify(db).get(no,true);order.verify(mapper).lockDispute(dispute);
        order.verify(mapper).approve(no);order.verify(db).task(no,"REFUND");order.verify(mapper).decide(dispute,1,"APPROVE","平台认定退款","7");
        order.verify(mapper).audit("7",no,"PLATFORM_APPROVE","平台认定退款");order.verify(manager).commit(any());
        verify(db,never()).task(no,"TERMINATE");
    }
    /** 退货退款仍要等实物验收，平台同意不能直接跳过收货触发资金执行。 */
    @Test void approveReturnRefundDoesNotSkipPhysicalReceipt(){
        when(db.get(no,true)).thenReturn(aftersale("RETURN_REFUND","PENDING_REVIEW","7"));when(mapper.approve(no)).thenReturn(1);
        when(mapper.decide(dispute,1,"APPROVE","先退货","7")).thenReturn(1);
        service.decide(jwt,dispute,new GovernanceService.Decision("APPROVE","先退货",1));
        verify(mapper).approve(no);verify(db,never()).task(anyString(),anyString());verify(manager).commit(any());
    }
    /** 可信终态不能被平台重新APPROVE；条件更新失败须回滚且不得创建新退款号或任务。 */
    @Test void terminalAftersaleCannotBeReversedByPlatformDecision(){
        when(db.get(no,true)).thenReturn(aftersale("REFUND_ONLY","COMPLETED","7"));when(mapper.approve(no)).thenReturn(0);
        assertThatThrownBy(()->service.decide(jwt,dispute,new GovernanceService.Decision("APPROVE","再次同意",1)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("DISPUTE_STATE_CONFLICT"));
        verify(db,never()).task(anyString(),anyString());verify(mapper,never()).decide(anyString(),anyLong(),anyString(),anyString(),anyString());
        verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    /** 已裁定结果同参重放不重新执行业务副作用，不同结论不可覆盖历史裁定。 */
    @Test void decidedReplayIsIdempotentAndDifferentDecisionConflicts(){
        Map<String,Object> decided=Map.of("dispute_no",dispute,"aftersale_no",no,"status","DECIDED","decision","UPHOLD","decision_reason","保持原状态","version",2L);
        when(mapper.lockDispute(dispute)).thenReturn(decided);
        assertThat(service.decide(jwt,dispute,new GovernanceService.Decision("UPHOLD","保持原状态",1))).isEqualTo(decided);
        assertThatThrownBy(()->service.decide(jwt,dispute,new GovernanceService.Decision("REJECT","改变原结论",2)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("DECISION_CONFLICT"));
        verify(mapper,never()).approve(anyString());verify(mapper,never()).reject(anyString());verify(db,never()).task(anyString(),anyString());
        verify(mapper,never()).decide(anyString(),anyLong(),anyString(),anyString(),anyString());
    }
    /** 同键不同申请内容必须拒绝；已有申请键不能被用于另一份理由或另一张售后。 */
    @Test void disputeSameKeyDifferentReasonRejectsBeforeAudit(){
        when(mapper.byKey("7","key")).thenReturn(Map.of("request_hash","original_hash","aftersale_no",no));
        assertThatThrownBy(()->service.apply(jwt,"key",new GovernanceService.Apply(no,"新的申请理由")))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        verify(mapper,never()).audit(anyString(),anyString(),anyString(),anyString());verify(manager).rollback(any());
    }
    /** 买家不能对他人的售后申请介入，即使知道正确售后号也不创建争议。 */
    @Test void buyerCannotOpenDisputeForForeignAftersale(){
        when(db.get(no,true)).thenReturn(aftersale("REFUND_ONLY","PENDING_REVIEW","8"));
        assertThatThrownBy(()->service.apply(jwt,"key",new GovernanceService.Apply(no,"请求介入")))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("DISPUTE_NOT_FOUND"));
        verify(mapper,never()).create(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }
    /** 拒绝仍通过原任务解除授权，不直接篡改订单侧权益。 */
    @Test void rejectSchedulesOriginalTerminationTask(){
        when(mapper.reject(no)).thenReturn(1);when(mapper.decide(dispute,1,"REJECT","拒绝依据","7")).thenReturn(1);
        service.decide(jwt,dispute,new GovernanceService.Decision("REJECT","拒绝依据",1));
        verify(db).task(no,"TERMINATE");verify(db,never()).task(no,"REFUND");verify(manager).commit(any());
    }
}
