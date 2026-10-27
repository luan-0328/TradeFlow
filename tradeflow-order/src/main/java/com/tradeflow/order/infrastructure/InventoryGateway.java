package com.tradeflow.order.infrastructure;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.TradingContracts.*;
import com.tradeflow.order.infrastructure.client.ProductClient;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** RPC适配器无本地事务、无隐式写重试，回调、查询均以原业务号关联。 */
@Component
public class InventoryGateway {
    private final ProductClient client;private final String token;
    public InventoryGateway(ProductClient client,Environment env) { this.client=client;token=env.getRequiredProperty("tradeflow.internal.order-token"); }
    /** 规范下单行传给权威报价服务，不使用商品缓存价格决定金额。 */
    public Quote quote(QuoteCommand command) { outside();return unwrap(client.quote("order",token,command)); }
    /** 未决预占先查，查询404不等于未来不会执行。 */
    public Reservation query(String no) { outside();return unwrap(client.query("order",token,no)); }
    /** 同号同参预占，RPC超时交持久任务恢复。 */
    public Reservation reserve(ReservationCommand command) { outside();return unwrap(client.reserve("order",token,command)); }
    /** 原订单持久RELEASE决策后发送，先到也必须创建库存墓碑。 */
    public Reservation release(String no,String order) { outside();return unwrap(client.release("order",token,no,new ReleaseCommand(order,"RELEASE"))); }
    /** 订单持久COMMIT后的确认，不能由用户直接决定库存动作。 */
    public Reservation confirm(String no,String order) { outside();return unwrap(client.confirm("order",token,no,new ReleaseCommand(order,"COMMIT"))); }
    private void outside() { if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("禁止在订单事务内调用库存"); }
    /** 不完整/假成功响应视为未知，不将其推进为待支付或已关闭。 */
    private <T> T unwrap(ApiResponse<T> response) {
        if(response==null || !"OK".equals(response.code()) || response.data()==null)throw new FeignPolicy.RemoteFailure(503,"INVALID_REMOTE_RESPONSE");return response.data();
    }
}
