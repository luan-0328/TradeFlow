package com.tradeflow.aftersale.infrastructure;

import com.fasterxml.jackson.databind.*;
import com.tradeflow.contracts.MessagingContracts.Event;
import com.tradeflow.contracts.RefundContracts;
import com.tradeflow.messaging.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 退款消息只更新本库尝试事实并唤醒持久任务，完成仍须任务确认订单库存收尾，消费事务中不RPC。 */
@Component
public class AftersaleRefundHandler implements EventHandler {
    private final AftersaleStore db;
    private final ObjectMapper json;
    public AftersaleRefundHandler(AftersaleStore db,ObjectMapper json) {
        this.db=db;
        this.json=json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    public String consumer(){return "aftersale.refund";}
    public Set<String> eventTypes(){return Set.of("RefundSucceeded","RefundFailed");}

    /** 同一Inbox事务锁售后主单；旧FAILED不能覆盖新尝试，CLOSE来源不伪造售后。 */
    public void handle(Event event) {
        RefundContracts.ChannelFact fact;
        try{fact=json.convertValue(event.payload(),RefundContracts.ChannelFact.class);}
        catch(IllegalArgumentException failure){throw new QuarantinedMessageException("REFUND_PAYLOAD_INVALID");}
        String expected="RefundSucceeded".equals(event.eventType())?"SUCCEEDED":"FAILED";
        if(!"REFUND".equals(event.aggregateType())||!Objects.equals(event.aggregateId(),fact.refundNo())||!Objects.equals(event.correlationId(),fact.orderNo())||event.aggregateVersion()!=fact.channelVersion()||!expected.equals(fact.status())||fact.amountCents()<=0||fact.amountCents()>100000000||!"CNY".equals(fact.currency())||fact.updatedAt()==null||("SUCCEEDED".equals(fact.status())&&fact.succeededAt()==null))throw new QuarantinedMessageException("REFUND_ENVELOPE_CONFLICT");
        if(fact.sourceNo()!=null&&fact.sourceNo().equals("CLOSE:"+fact.orderNo()))return;
        if(fact.sourceNo()==null||!fact.sourceNo().matches("as_[a-f0-9]{32}")||fact.refundNo()==null||!fact.refundNo().matches("rfd_[a-f0-9]{32}"))throw new QuarantinedMessageException("REFUND_SOURCE_INVALID");
        var sale=db.get(fact.sourceNo(),true);
        if(sale==null||sale.amountCents()==null)throw new DeferredMessageException("AFTERSALE_AUTHORIZATION_NOT_READY");
        var binding=db.jdbc().queryForList("SELECT authorized_payment_no FROM aftersale WHERE aftersale_no=?",sale.aftersaleNo());
        Object payment=binding.get(0).get("authorized_payment_no");
        if(payment==null)throw new DeferredMessageException("AFTERSALE_PAYMENT_BINDING_NOT_READY");
        if(!payment.equals(fact.paymentNo()))throw new QuarantinedMessageException("AFTERSALE_PAYMENT_BINDING_CONFLICT");
        if(!sale.orderNo().equals(fact.orderNo())||sale.amountCents()!=fact.amountCents()||!Objects.equals(sale.currency(),fact.currency()))throw new QuarantinedMessageException("AFTERSALE_REFUND_BINDING_CONFLICT");
        var attempts=db.jdbc().queryForList("SELECT aftersale_no,status FROM aftersale_refund_attempt WHERE refund_no=?",fact.refundNo());
        if(attempts.isEmpty()||!sale.aftersaleNo().equals(attempts.get(0).get("aftersale_no")))throw new QuarantinedMessageException("REFUND_ATTEMPT_NOT_BOUND");
        String previous=(String)attempts.get(0).get("status");
        if(Set.of("SUCCEEDED","FAILED").contains(previous)&&!previous.equals(fact.status()))throw new QuarantinedMessageException("REFUND_TERMINAL_CONFLICT");
        if(!sale.refundNo().equals(fact.refundNo())) {
            if("SUCCEEDED".equals(fact.status()))throw new QuarantinedMessageException("OLD_REFUND_SUCCESS_CONFLICT");
            db.jdbc().update("UPDATE aftersale_refund_attempt SET status='FAILED',last_error='REFUND_FAILED' WHERE refund_no=?",fact.refundNo());
            return;
        }
        if(!Set.of("REFUND_PROCESSING","COMPLETED").contains(sale.status()))throw new DeferredMessageException("AFTERSALE_NOT_EXECUTING");
        if("COMPLETED".equals(sale.status())) {
            if(!"SUCCEEDED".equals(fact.status()))throw new QuarantinedMessageException("COMPLETED_REFUND_CONFLICT");
            return;
        }
        if(Set.of("SUCCEEDED","FAILED").contains(sale.refundStatus())&&!sale.refundStatus().equals(fact.status()))throw new QuarantinedMessageException("REFUND_TERMINAL_CONFLICT");
        String error="FAILED".equals(fact.status())?"REFUND_FAILED":null;
        db.jdbc().update("UPDATE aftersale_refund_attempt SET status=?,last_error=? WHERE refund_no=?",fact.status(),error,fact.refundNo());
        db.jdbc().update("UPDATE aftersale SET refund_status=?,last_error=? WHERE aftersale_no=?",fact.status(),error,sale.aftersaleNo());
        // 不在消息中断言库存回补完成，任务查询原订单授权COMPLETED后才结束售后。
        db.task(sale.aftersaleNo(),"REFUND");
    }
}
