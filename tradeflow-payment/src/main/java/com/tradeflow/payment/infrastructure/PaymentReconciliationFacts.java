package com.tradeflow.payment.infrastructure;

import com.tradeflow.contracts.ReconciliationContracts.*;
import com.tradeflow.payment.application.*;
import com.tradeflow.payment.application.reconciliation.ReconciliationFacts;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 对账资金适配：远程只读通道，写入只能经过既有支付/退款统一应用器，不造单不再次扣退。 */
@Component
public class PaymentReconciliationFacts implements ReconciliationFacts {
 private final PaymentRemote remote;private final ReconciliationLocalStore local;private final PaymentService payment;private final RefundService refund;private final PaymentMapper payments;private final RefundMapper refunds;
 public PaymentReconciliationFacts(PaymentRemote remote,ReconciliationLocalStore local,PaymentService payment,RefundService refund,PaymentMapper payments,RefundMapper refunds){this.remote=remote;this.local=local;this.payment=payment;this.refund=refund;this.payments=payments;this.refunds=refunds;}
 /** 所有账单调用使用通道RPC，PaymentRemote会拒绝事务内网络。 */
 public BillManifest freezeBill(BillRequest request){return remote.freezeBill(request);}
 /** 清单只读且绑定原快照，网络必须在恢复领取事务提交后执行。 */
 public BillManifest manifest(String snapshotNo){return remote.billManifest(snapshotNo);}
 /** 游标读取原快照页，不使用实时交易列表替代冻结证据。 */
 public BillPage page(String snapshotNo,long after,int size){return remote.billPage(snapshotNo,after,size);}
 /** 实时查询不发起资金命令，查无记录保持null，不能合成FAILED。 */
 public BillLine live(String type,String no){ReconciliationLocalStore.validate(type,no);if("PAYMENT".equals(type)){var f=remote.query(no);return f==null?null:new BillLine(0,type,no,f,null);}var f=remote.refundQuery(no);return f==null?null:new BillLine(0,type,no,null,f);}
 /** 本地一致资金检查，允许加入action围栏事务。 */
 public LocalRecord local(String type,String no){return local.inspect(type,no);}
 /** 反向扫描包含历史未决项，不能因日扫窗口滑动丢弃资金义务。 */
 public List<LocalRecord> localPage(Instant start,Instant end,Instant cutoff,String after,int size){return local.page(start,end,cutoff,after,size);}
 /** action已围栏后复查最新本地资金，只有绑定一致、无冻结且汇总正常才统一应用；矛盾冻结后返回false。 */
 public boolean apply(BillLine fact,String actionNo,boolean repairLedger){requireTransaction();ReconciliationLocalStore.validate(fact.transactionType(),fact.businessNo());var record=local(fact.transactionType(),fact.businessNo());if(record==null||record.reviewRequired())return false;if(record.integrityCode()!=null){freeze(fact.transactionType(),fact.businessNo(),record.integrityCode(),actionNo);return false;}if(!binding(record,fact)){freeze(fact.transactionType(),fact.businessNo(),"CHANNEL_BINDING_CONFLICT",actionNo);return false;}return "PAYMENT".equals(fact.transactionType())?payment.reconcileApply(fact.payment(),actionNo,repairLedger):refund.reconcileApply(fact.refund(),actionNo,repairLedger);}
 /** 先锁原支付，退款再锁账户、来源与执行；冻结同时约束未来退款及未发出的资金事件，不撤回已在途副作用。 */
 public void freeze(String type,String no,String reason,String actionNo){requireTransaction();ReconciliationLocalStore.validate(type,no);var initial="PAYMENT".equals(type)?payments.find(no):refunds.find(no);if(initial==null)return;String paymentNo="PAYMENT".equals(type)?no:Objects.toString(initial.get("payment_no"),null);if(payments.lock(paymentNo)==null)return;if("REFUND".equals(type)){refunds.account(paymentNo);refunds.sourceLock(Objects.toString(initial.get("source_no"),null));refunds.lock(no);refunds.issue(no,reason);}payments.issue(paymentNo,"对账完整性异常："+reason);payments.audit(paymentNo,actionNo,"对账冻结，action="+actionNo+"，原因="+reason);}
 /** 再次在资金锁下核对业务与金额，不能相信早先下载/展示时的本地快照。 */
 private boolean binding(LocalRecord r,BillLine line){if("PAYMENT".equals(r.transactionType())){var f=line.payment();return f!=null&&line.refund()==null&&Objects.equals(r.businessNo(),f.paymentNo())&&Objects.equals(r.orderNo(),f.orderNo())&&r.amountCents()==f.amountCents()&&Objects.equals(r.currency(),f.currency());}var f=line.refund();return f!=null&&line.payment()==null&&Objects.equals(r.businessNo(),f.refundNo())&&Objects.equals(r.paymentNo(),f.paymentNo())&&Objects.equals(r.orderNo(),f.orderNo())&&Objects.equals(r.sourceNo(),f.sourceNo())&&r.amountCents()==f.amountCents()&&Objects.equals(r.currency(),f.currency());}
 private void requireTransaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("资金修复必须加入已围栏的本地写事务");}
}
