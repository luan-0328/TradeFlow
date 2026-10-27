package com.tradeflow.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.PaymentContracts.*;
import com.tradeflow.contracts.TradingContracts;
import com.tradeflow.order.infrastructure.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 接收支付权威事实：订单是否接受支付由本库状态竞争决定，资金事实不可被关单抹去。 */
@Service
public class OrderPaymentService {
    private final OrderMapper orders;private final TaskMapper tasks;private final OrderTransactions tx;private final ObjectMapper json;private final com.tradeflow.messaging.MessageOutbox outbox;
    public OrderPaymentService(OrderMapper orders,TaskMapper tasks,OrderTransactions tx,ObjectMapper json,com.tradeflow.messaging.MessageOutbox outbox){this.orders=orders;this.tasks=tasks;this.tx=tx;this.json=json;this.outbox=outbox;}
    /** 支付服务用于提交校验和关闭墓碑的不可变金额来源；不是已付款证明。 */
    public Intent intent(String no) {
        var order=orders.order(no);if(order==null)throw new BusinessException(404,"ORDER_NOT_FOUND","订单不存在");
        return new Intent(order.orderNo(),order.paymentNo(),order.userId(),order.totalAmountCents(),order.currency(),order.payDeadline(),order.status());
    }
    /** 验证后事务内统一按task→intent→order加锁，通知证据/状态/后续任务一次提交。 */
    public Map<String,Boolean> apply(String no,Event event) {
        validate(no,event);String hash=hash(event.fact());
        return tx.write(()->{
            var task=tasks.lockByOrder(no);orders.lockIntent(no);var order=orders.lockOrder(no);
            if(task==null || order==null)throw new BusinessException(404,"ORDER_NOT_FOUND","订单不存在");
            var fact=event.fact();
            if(!order.paymentNo().equals(fact.paymentNo()) || order.totalAmountCents()!=fact.amountCents() || !order.currency().equals(fact.currency()))
                throw new BusinessException(409,"PAYMENT_BINDING_CONFLICT","支付事实绑定或金额不匹配");
            String old=orders.paymentFactHash(event.eventId());
            if(old!=null) { if(!old.equals(hash))throw new BusinessException(409,"EVENT_IDEMPOTENCY_CONFLICT","同事件号事实不一致");return Map.of("ack",true); }
            if("CREATING".equals(order.status()))throw new BusinessException(409,"PAYMENT_NOT_AUTHORIZED","创建中订单尚无支付授权");
            orders.paymentFact(event.eventId(),fact.paymentNo(),no,hash,fact.status(),fact.channelVersion());
            if("SUCCEEDED".equals(fact.status())) {
                if("PENDING_PAYMENT".equals(order.status()) && orders.paid(no,fact.succeededAt())==1) {
                    orders.intentState(no,"ORDER_CREATED","PAYMENT_SUCCEEDED");
                    outbox.enqueue("ORDER",no,orders.currentVersion(no),"OrderPaid",no,event.eventId(),Map.of("orderNo",no,"paymentNo",fact.paymentNo()));
                    orders.log(no,"PENDING_PAYMENT","PAID","PAYMENT_SUCCEEDED","payment",event.eventId(),"权威支付成功已接受");
                    tasks.activate(task.taskNo());
                } else if(Set.of("CLOSING","CLOSED").contains(order.status())) {
                    orders.latePayment(no);
                    orders.audit("SERVICE","payment","LATE_PAYMENT",no,event.eventId(),event.eventId(),"关闭先成立但通道已成功，等待独立退款来源任务","{\"obligation\":\"REFUND_REQUIRED\"}");
                } else if(!Set.of("PAID","SHIPPED","COMPLETED").contains(order.status())) {
                    tasks.manual(task.taskNo(),"CONTRADICTORY_PAYMENT_FACT");
                }
            } else if(Set.of("FAILED","CLOSED").contains(fact.status())) {
                if("PENDING_PAYMENT".equals(order.status()) && orders.failedPayment(no,"PAYMENT_"+fact.status())==1) {
                    orders.intentState(no,"ORDER_CREATED","PAYMENT_"+fact.status());
                    orders.log(no,"PENDING_PAYMENT","CLOSING","PAYMENT_TERMINAL","payment",event.eventId(),"通道终态不可再成功");tasks.activate(task.taskNo());
                } else if("CLOSING".equals(order.status()) && "PAYMENT_REQUIRED".equals(order.closePhase())) {
                    orders.channelClosed(no);tasks.activate(task.taskNo());
                } else if(Set.of("PAID","SHIPPED","COMPLETED").contains(order.status())) {
                    tasks.manual(task.taskNo(),"CONTRADICTORY_PAYMENT_FACT");
                    orders.audit("SERVICE","payment","PAYMENT_FACT_CONFLICT",no,event.eventId(),event.eventId(),"已接受成功后的矛盾支付事实","{\"manualReview\":true}");
                }
            }
            return Map.of("ack",true);
        });
    }
    /** 只接受明确终态与完整绑定，不将UNKNOWN当失败；验真由调用服务凭据边界完成。 */
    private void validate(String no,Event event) {
        if(event==null || event.eventId()==null || !event.eventId().matches("[A-Za-z0-9_:-]{1,64}") || event.fact()==null)throw invalid();
        var f=event.fact();
        if(!no.equals(f.orderNo()) || f.paymentNo()==null || !f.paymentNo().matches("pay_[a-f0-9]{32}") || f.amountCents()<=0 || f.amountCents()>100000000
                || !"CNY".equals(f.currency()) || f.status()==null || !Set.of("SUCCEEDED","FAILED","CLOSED").contains(f.status()) || f.channelVersion()<0
                || f.updatedAt()==null || f.channelTransactionNo()==null || f.channelTransactionNo().length()>64
                || ("SUCCEEDED".equals(f.status()) && f.succeededAt()==null))throw invalid();
    }
    private String hash(Object value) { try{return TradingContracts.sha256(json.writeValueAsString(value));}catch(Exception e){throw invalid();} }
    private BusinessException invalid(){return new BusinessException(400,"INVALID_PAYMENT_FACT","支付事实不合法");}
}
