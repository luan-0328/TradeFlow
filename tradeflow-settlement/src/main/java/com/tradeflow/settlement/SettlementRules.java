package com.tradeflow.settlement;
import java.math.BigInteger;
import java.time.*;
/** 结算规则纯函数：整数分、向下取整，累计剩余本金计费避免分次退款尾差。 */
public final class SettlementRules {
 private SettlementRules(){}
 /** 对剩余本金重新计算费用，最终全退必然全额冲回费用。 */
 public static long fee(long principal,int bps){if(principal<0||bps<0||bps>10000)throw new IllegalArgumentException("本金或费率无效");return BigInteger.valueOf(principal).multiply(BigInteger.valueOf(bps)).divide(BigInteger.valueOf(10000)).longValueExact();}
 /** 资格是最新订单已收货、无活动售后且过单据冻结期；时间使用UTC。 */
 public static boolean eligible(String status,Instant receivedAt,boolean aftersale,int freezeDays,Instant now){return "COMPLETED".equals(status)&&receivedAt!=null&&!aftersale&&!receivedAt.plus(Duration.ofDays(freezeDays)).isAfter(now);}
 /** 单笔应付先抵扣历史模拟完成后退款形成的债务，余额不足保留剩余负债。 */
 public static Allocation allocate(long net,long debt){if(net<0||debt<0)throw new IllegalArgumentException("结算金额无效");long offset=Math.min(net,debt);return new Allocation(net-offset,offset,debt-offset);}
 public record Allocation(long payout,long offset,long debt){}
}
