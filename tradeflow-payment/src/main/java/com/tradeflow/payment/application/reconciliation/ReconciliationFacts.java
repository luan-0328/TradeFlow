package com.tradeflow.payment.application.reconciliation;

import com.tradeflow.contracts.ReconciliationContracts.*;
import java.time.Instant;
import java.util.List;

/** 对账与资金用例边界；远程读取只在事务外，apply/freeze必须加入调用方已围栏的本地事务。 */
public interface ReconciliationFacts {
 record LocalRecord(String transactionType,String businessNo,String paymentNo,String orderNo,String sourceNo,long amountCents,String currency,String status,String channelNo,long channelVersion,Instant channelUpdatedAt,Instant succeededAt,boolean reviewRequired,int ledgerCount,long ledgerAmount,String integrityCode){}
 /** 原请求号冻结不可变通道快照；网络超时重试同号。 */
 BillManifest freezeBill(BillRequest request);
 /** 读取冻结元数据，不允许变化的清单内容。 */
 BillManifest manifest(String snapshotNo);
 /** 游标最多100，始终读取同一快照。 */
 BillPage page(String snapshotNo,long after,int size);
 /** 原业务号只查询不提交，null只代表当前查无记录，绝不等同失败。 */
 BillLine live(String type,String businessNo);
 /** 一致本地快照，返回额度/流水完整性问题；不访问其他服务数据库。 */
 LocalRecord local(String type,String businessNo);
 /** 游标格式PAYMENT:业务号或REFUND:业务号；包含范围内终态及截止前所有未决项。 */
 List<LocalRecord> localPage(Instant start,Instant end,Instant cutoff,String after,int size);
 /** 仅统一事实应用与受控唯一流水补记；不创建缺失业务、不解除冻结、不再次发起支付或退款。 */
 boolean apply(BillLine fact,String actionNo,boolean repairLedger);
 /** 关联/金额/额度矛盾时冻结相关资金自动推进，保留原终态与审计。 */
 void freeze(String type,String businessNo,String reason,String actionNo);
}
