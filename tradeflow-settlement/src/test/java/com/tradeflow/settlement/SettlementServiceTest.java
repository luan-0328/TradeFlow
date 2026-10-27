package com.tradeflow.settlement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
/** 应用边界验证只能证明事务编排，不替代真实MySQL锁、回滚和并发约束验收。 */
class SettlementServiceTest {
 SettlementMapper db;SettlementRemote remote;SettlementService service;
 static final Instant AT=Instant.parse("2026-01-01T00:00:00Z");
 @BeforeEach void setup(){db=mock(SettlementMapper.class);remote=mock(SettlementRemote.class);
  // MyBatis单行SELECT未命中返回null；Mockito的Map默认空集合会伪造“已有事实”。
  when(db.fact(anyString(),anyString())).thenReturn(null);
  when(db.item(anyString())).thenReturn(null);
  when(db.lockItem(anyString())).thenReturn(null);
  var tm=mock(PlatformTransactionManager.class);when(tm.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());service=new SettlementService(db,remote,tm,new ObjectMapper(),new MockEnvironment());when(db.claim(anyString())).thenReturn(1);when(db.lockTask()).thenReturn(Map.of("claim_version",1L,"scan_cursor",0L,"candidate_cursor",0L));when(db.fence(anyString(),anyLong())).thenReturn(Map.of("claim_version",1));when(db.finish(anyString(),anyLong(),anyLong(),any())).thenReturn(1);when(db.candidates(anyLong())).thenReturn(List.of());}
 SettlementRemote.Fact fact(String kind,String business,long amount){return new SettlementRemote.Fact(1,business,"pay_1","ord_1",kind,amount,"CNY",AT);}
 SettlementRemote.Order order(){return new SettlementRemote.Order("ord_1","buyer","merchant_a","COMPLETED",AT,false);}
 @Test void expiredOwnerCannotApplySuccessFact(){when(remote.facts(0)).thenReturn(new SettlementRemote.Page(List.of(fact("PAYMENT_SUCCEEDED","pay_1",100)),1,false));when(remote.order("ord_1")).thenReturn(order());when(db.fence(anyString(),anyLong())).thenReturn(null);assertEquals("LEASE_LOST",assertThrows(BusinessException.class,()->service.run()).code());verify(db,never()).insertFact(any(),anyString());verify(db,never()).insertItem(any(),anyString(),anyInt(),anyInt(),anyLong());}
 @Test void refundBeforePaymentDoesNotGuessPrincipalAndScanRestarts(){when(remote.facts(0)).thenReturn(new SettlementRemote.Page(List.of(fact("REFUND_SUCCEEDED","ref_1",100)),1,false));when(remote.order("ord_1")).thenReturn(order());service.run();verify(db,never()).insertFact(any(),anyString());verify(db).finish(anyString(),eq(1L),eq(0L),isNull());}
 @Test void duplicateFactWithChangedAmountStopsBeforeWriting(){when(remote.facts(0)).thenReturn(new SettlementRemote.Page(List.of(fact("PAYMENT_SUCCEEDED","pay_1",100)),1,false));when(db.fact("PAYMENT_SUCCEEDED","pay_1")).thenReturn(Map.of("payment_no","pay_1","order_no","ord_1","amount_cents",99L,"currency","CNY","occurred_at",AT));assertEquals("SETTLEMENT_FACT_CONFLICT",assertThrows(BusinessException.class,()->service.run()).code());verify(db,never()).ledger(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong(),anyLong(),any());}
 @Test void identicalImportedFactIsVerifiedWithoutAnotherBusinessWrite(){when(remote.facts(0)).thenReturn(new SettlementRemote.Page(List.of(fact("PAYMENT_SUCCEEDED","pay_1",100)),1,false));when(db.fact("PAYMENT_SUCCEEDED","pay_1")).thenReturn(Map.of("payment_no","pay_1","order_no","ord_1","amount_cents",100L,"currency","CNY","occurred_at",AT));service.run();verify(remote,never()).order(anyString());verify(db,never()).insertFact(any(),anyString());verify(db,never()).insertItem(any(),anyString(),anyInt(),anyInt(),anyLong());verify(db,never()).ledger(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong(),anyLong(),any());}
 @Test void newPaymentFreezesDefaultRuleAndBalancesLedger(){when(remote.facts(0)).thenReturn(new SettlementRemote.Page(List.of(fact("PAYMENT_SUCCEEDED","pay_1",100)),1,false));when(remote.order("ord_1")).thenReturn(order());service.run();verify(db).insertItem(any(),eq("merchant_a"),eq(0),eq(7),eq(0L));verify(db).ledger("P:pay_1","pay_1","merchant_a","PAYMENT",100,0,100,0,null);}
 @Test void postSettlementRefundCreatesDebtAndNegativeSimulatedRight(){when(remote.facts(0)).thenReturn(new SettlementRemote.Page(List.of(fact("REFUND_SUCCEEDED","ref_1",20)),1,false));when(remote.order("ord_1")).thenReturn(order());when(db.lockItem("pay_1")).thenReturn(Map.of("merchant_id","merchant_a","order_no","ord_1","paid_cents",100L,"refund_cents",0L,"fee_cents",10L,"fee_bps",1000,"status","SIMULATED_COMPLETED"));when(db.refund(anyString(),anyLong(),anyLong())).thenReturn(1);when(db.debt(anyString(),anyLong())).thenReturn(1);service.run();verify(db).debt("merchant_a",18);verify(db).ledger("R:ref_1","pay_1","merchant_a","POST_SETTLEMENT_REFUND",-20,-2,0,-18,null);}
 @Test void activeAftersaleRecheckPreventsBatch(){when(remote.facts(0)).thenReturn(new SettlementRemote.Page(List.of(),0,false));Map<String,Object> item=Map.of("id",1L,"merchant_id","merchant_a","payment_no","pay_1","order_no","ord_1","status","FROZEN","freeze_days",0);when(db.candidates(0)).thenReturn(List.of(item));when(db.lockAccount("merchant_a")).thenReturn(Map.of("debt_cents",0L));when(db.lockItem("pay_1")).thenReturn(item);when(remote.order("ord_1")).thenReturn(new SettlementRemote.Order("ord_1","buyer","merchant_a","COMPLETED",AT,true));service.run();verify(db,never()).batch(anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong());}
 Jwt jwt(){return Jwt.withTokenValue("verified").header("alg","RS256").subject("u1").claim("token_version",2L).build();}
 @Test void merchantResourceOwnershipCannotBeReplacedByAdminGlobalRole(){when(remote.context("u1")).thenReturn(new AuthContext("u1","ACTIVE",List.of("MERCHANT_ADMIN"),List.of("merchant_a"),2,java.util.Map.of("merchant_a","ADMIN")));assertEquals("u1",service.authorize(jwt(),false,"merchant_a"));assertThrows(BusinessException.class,()->service.authorize(jwt(),false,"merchant_b"));}
 /** 本店OPERATOR无需旧全局商家角色也能只读本店结算，不能查询另一店。 */
 @Test void operatorReadsOnlyOwnMerchantSettlement(){when(remote.context("u1")).thenReturn(new AuthContext("u1","ACTIVE",List.of("USER"),List.of("merchant_b"),2,Map.of("merchant_b","OPERATOR")));assertEquals("u1",service.authorize(jwt(),false,"merchant_b"));assertThrows(BusinessException.class,()->service.authorize(jwt(),false,"merchant_a"));}
 @Test void revokedTokenFailsEvenForPlatformAdmin(){when(remote.context("u1")).thenReturn(new AuthContext("u1","ACTIVE",List.of("PLATFORM_ADMIN"),List.of(),3,java.util.Map.of()));assertThrows(BusinessException.class,()->service.authorize(jwt(),true,null));}
}
