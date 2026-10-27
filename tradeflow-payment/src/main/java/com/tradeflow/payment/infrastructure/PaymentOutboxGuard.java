package com.tradeflow.payment.infrastructure;
import com.tradeflow.messaging.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/** 支付/退款冻结后未发送事件不能再作为下游释放库存或结算依据；网络发送前只读本库权威标记。 */
@Component public class PaymentOutboxGuard implements OutboxGuard {
 private final JdbcTemplate jdbc;
 public PaymentOutboxGuard(JdbcTemplate jdbc){this.jdbc=jdbc;}
 /** 旧事件的聚合绑定必须真实存在，退款还要复核原支付未进入人工冻结。 */
 public void check(String type,String id){
  if("PAYMENT".equals(type)){
   var rows=jdbc.queryForList("SELECT review_required FROM payment_order WHERE payment_no=?",id);
   if(rows.size()!=1||"YES".equals(rows.get(0).get("review_required")))throw new QuarantinedMessageException("PAYMENT_MANUAL_REVIEW");
  }else if("REFUND".equals(type)){
   var rows=jdbc.queryForList("SELECT r.review_required refund_review,p.review_required payment_review FROM refund_order r JOIN payment_order p ON p.payment_no=r.payment_no WHERE r.refund_no=?",id);
   if(rows.size()!=1||"YES".equals(rows.get(0).get("refund_review"))||"YES".equals(rows.get(0).get("payment_review")))throw new QuarantinedMessageException("REFUND_MANUAL_REVIEW");
  }else throw new QuarantinedMessageException("OUTBOX_AGGREGATE_INVALID");
 }
}
