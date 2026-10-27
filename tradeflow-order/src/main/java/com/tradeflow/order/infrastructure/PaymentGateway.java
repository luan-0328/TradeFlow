package com.tradeflow.order.infrastructure;

import com.tradeflow.contracts.PaymentContracts.*;
import com.tradeflow.order.infrastructure.client.PaymentClient;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 支付关闭在事务外执行；UNKNOWN/PENDING只说明仍需查原支付，不触发库存释放。 */
@Component
public class PaymentGateway {
    private final PaymentClient client;private final String token;
    public PaymentGateway(PaymentClient client,Environment env){this.client=client;token=env.getRequiredProperty("tradeflow.internal.order-token");}
    public View close(String payment,String order) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("禁止事务内调用支付");
        var response=client.close(payment,"order",token,new CloseCommand(order));
        if(response==null || !"OK".equals(response.code()) || response.data()==null)throw new FeignPolicy.RemoteFailure(503,"INVALID_REMOTE_RESPONSE");
        return response.data();
    }
}
