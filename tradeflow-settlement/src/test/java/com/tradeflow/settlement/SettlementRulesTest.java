package com.tradeflow.settlement;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
/** 纯规则不依赖数据库；覆盖累计退款尾差、冻结边界及负债结转。 */
class SettlementRulesTest {
 @Test void cumulativeRefundReversesAllFeesWithoutRoundingLeak(){int bps=333;long initial=SettlementRules.fee(101,bps),afterFirst=SettlementRules.fee(100,bps),afterSecond=SettlementRules.fee(0,bps);assertEquals(3,initial);assertEquals(0,afterSecond);assertEquals(initial,(initial-afterFirst)+(afterFirst-afterSecond));}
 @Test void exactFreezeBoundaryRequiresCompletedAndNoActiveAftersale(){Instant received=Instant.parse("2026-01-01T00:00:00Z"),ready=received.plus(Duration.ofDays(7));assertFalse(SettlementRules.eligible("COMPLETED",received,false,7,ready.minusMillis(1)));assertTrue(SettlementRules.eligible("COMPLETED",received,false,7,ready));assertFalse(SettlementRules.eligible("COMPLETED",received,true,7,ready));assertFalse(SettlementRules.eligible("SHIPPED",received,false,7,ready));assertFalse(SettlementRules.eligible("COMPLETED",null,false,7,ready));}
 @Test void subsequentSettlementOffsetsDebtBeforeAnySimulatedPayout(){assertEquals(new SettlementRules.Allocation(0,500,300),SettlementRules.allocate(500,800));assertEquals(new SettlementRules.Allocation(200,300,0),SettlementRules.allocate(500,300));}
 @Test void feeMultiplicationCannotOverflowLongBeforeDivision(){assertEquals(Long.MAX_VALUE,SettlementRules.fee(Long.MAX_VALUE,10000));assertThrows(IllegalArgumentException.class,()->SettlementRules.fee(-1,0));assertThrows(IllegalArgumentException.class,()->SettlementRules.fee(100,10001));}
}
