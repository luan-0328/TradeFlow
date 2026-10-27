package com.tradeflow.order.infrastructure;

import com.fasterxml.jackson.databind.*;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.*;
import com.tradeflow.contracts.MessagingContracts.Event;
import com.tradeflow.messaging.*;
import com.tradeflow.order.application.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 消费者仅应用本库事实；外层Inbox事务包含业务、状态日志与后续持久任务，不执行RPC。 */
@Configuration(proxyBeanMethods=false)
public class OrderEventHandlers {
    private final ObjectMapper json;
    public OrderEventHandlers(ObjectMapper json) {
        this.json=json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** 支付成功/失败/关闭按各自独立事实处理，不根据较小聚合版本一律丢弃。 */
    @Bean EventHandler orderPaymentHandler(OrderPaymentService service) {
        return new EventHandler() {
            public String consumer(){return "order.payment";}
            public Set<String> eventTypes(){return Set.of("PaymentSucceeded","PaymentFailed","PaymentClosed");}
            public void handle(Event event) {
                var fact=convert(event,PaymentContracts.ChannelFact.class);
                String expected=switch(event.eventType()){case "PaymentSucceeded"->"SUCCEEDED";case "PaymentFailed"->"FAILED";default->"CLOSED";};
                if(!"PAYMENT".equals(event.aggregateType())||!Objects.equals(event.aggregateId(),fact.paymentNo())||!Objects.equals(event.correlationId(),fact.orderNo())||event.aggregateVersion()!=fact.channelVersion()||!expected.equals(fact.status()))throw new QuarantinedMessageException("PAYMENT_ENVELOPE_CONFLICT");
                try{service.apply(fact.orderNo(),new PaymentContracts.Event(event.eventId(),fact));}
                catch(BusinessException failure){classify(failure);}
            }
        };
    }

    /** 退款消费复用唯一来源结算，ACK后库存回补仍由独立任务幂等完成。 */
    @Bean EventHandler orderRefundHandler(FulfillmentService service,FulfillmentMapper db) {
        return new EventHandler() {
            public String consumer(){return "order.refund";}
            public Set<String> eventTypes(){return Set.of("RefundSucceeded","RefundFailed");}
            public void handle(Event event) {
                var fact=convert(event,RefundContracts.ChannelFact.class);
                String expected="RefundSucceeded".equals(event.eventType())?"SUCCEEDED":"FAILED";
                if(!"REFUND".equals(event.aggregateType())||!Objects.equals(event.aggregateId(),fact.refundNo())||!Objects.equals(event.correlationId(),fact.orderNo())||event.aggregateVersion()!=fact.channelVersion()||!expected.equals(fact.status()))throw new QuarantinedMessageException("REFUND_ENVELOPE_CONFLICT");
                if(db.auth(fact.sourceNo())==null)throw new DeferredMessageException("REFUND_AUTHORIZATION_NOT_READY");
                try{service.result(fact.orderNo(),new RefundContracts.Event(event.eventId(),fact));}
                catch(BusinessException failure){classify(failure);}
            }
        };
    }

    /** 审计投影只追加事实，不重复执行退款、订单迁移或库存动作。 */
    @Bean EventHandler orderAuditHandler(JdbcTemplate jdbc) {
        return new EventHandler() {
            public String consumer(){return "order.audit";}
            public Set<String> eventTypes(){return Set.of("OrderPaid","OrderClosed","AftersaleCompleted");}
            public void handle(Event event) {
                if(!Objects.equals(event.correlationId(),event.payload().get("orderNo")))throw new QuarantinedMessageException("AUDIT_ORDER_BINDING_CONFLICT");
                try{jdbc.update("INSERT INTO event_projection(event_id,event_type,aggregate_id,order_no,payload,occurred_at) VALUES(?,?,?,?,?,?)",event.eventId(),event.eventType(),event.aggregateId(),event.correlationId(),json.writeValueAsString(event.payload()),java.sql.Timestamp.from(event.occurredAt()));}
                catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new QuarantinedMessageException("AUDIT_PAYLOAD_INVALID");}
            }
        };
    }

    /** 消息字段可兼容新增非必填属性；未知版本与基础信封由共享codec先隔离。 */
    private <T>T convert(Event event,Class<T> type) {
        try{return json.convertValue(event.payload(),type);}
        catch(IllegalArgumentException failure){throw new QuarantinedMessageException("FACT_PAYLOAD_INVALID");}
    }

    /** 前置未齐可持久延期，绑定/金额和幂等冲突应隔离，不能热循环重试。 */
    private void classify(BusinessException failure) {
        if(failure.status()>=500||failure.status()==404||Set.of("PAYMENT_NOT_AUTHORIZED","AUTHORIZATION_NOT_EXECUTING").contains(failure.code()))throw new DeferredMessageException(failure.code());
        throw new QuarantinedMessageException(failure.code());
    }
}
