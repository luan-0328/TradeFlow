package com.tradeflow.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** B7冻结账单协议；只传事实，不共享实体或允许调用方修改通道交易。 */
public final class ReconciliationContracts {
 private ReconciliationContracts(){}
 public record BillRequest(String requestNo,Instant start,Instant end,Instant cutoff,String currency){}
 public record BillManifest(String snapshotNo,String requestNo,String channel,String currency,Instant start,Instant end,Instant cutoff,Instant watermark,String status,long lineCount,long paymentCount,long refundCount,long paymentSucceededCents,long refundSucceededCents,String checksum){}
 public record BillLine(long position,String transactionType,String businessNo,PaymentContracts.ChannelFact payment,RefundContracts.ChannelFact refund){}
 public record BillPage(String snapshotNo,long after,List<BillLine> lines,long nextCursor,boolean hasMore){}
 /** 固定协议字段与空值标记，逐行UTF8拼接做SHA256；不依赖JSON对象键顺序或浮点金额。 */
 public static byte[] canonical(BillLine line){var fields=new ArrayList<String>();fields.add(Long.toString(line.position()));fields.add(line.transactionType());fields.add(line.businessNo());if("PAYMENT".equals(line.transactionType())&&line.payment()!=null&&line.refund()==null){var p=line.payment();Collections.addAll(fields,p.paymentNo(),p.orderNo(),Long.toString(p.amountCents()),p.currency(),p.status(),p.channelTransactionNo(),Long.toString(p.channelVersion()),stamp(p.updatedAt()),stamp(p.succeededAt()));}else if("REFUND".equals(line.transactionType())&&line.refund()!=null&&line.payment()==null){var r=line.refund();Collections.addAll(fields,r.refundNo(),r.paymentNo(),r.orderNo(),r.sourceNo(),Long.toString(r.amountCents()),r.currency(),r.status(),r.channelRefundNo(),Long.toString(r.channelVersion()),stamp(r.updatedAt()),stamp(r.succeededAt()));}else throw new IllegalArgumentException("账单交易类型与事实不匹配");StringBuilder out=new StringBuilder();for(String value:fields){if(value==null)out.append("-1:");else out.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);out.append('|');}return out.append('\n').toString().getBytes(StandardCharsets.UTF_8);}
 /** 空账单同样有确定摘要，所有调用者使用同一协议编码。 */
 public static String checksum(Iterable<BillLine> lines){try{MessageDigest digest=MessageDigest.getInstance("SHA-256");for(var line:lines)digest.update(canonical(line));return HexFormat.of().formatHex(digest.digest());}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static String stamp(Instant value){return value==null?null:value.toString();}
}
