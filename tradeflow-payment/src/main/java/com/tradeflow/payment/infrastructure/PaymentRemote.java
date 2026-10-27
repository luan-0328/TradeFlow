package com.tradeflow.payment.infrastructure;

import com.tradeflow.discovery.ServiceEndpoints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.*;
import com.tradeflow.contracts.*;
import com.tradeflow.contracts.PaymentContracts.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 固定服务名经Nacos选择实例、固定调用方和独立服务凭据；无写请求自动重试，所有RPC必须在本地事务外。 */
@Component
public class PaymentRemote {
    private final ObjectMapper json; private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String user="http://tradeflow-user",order="http://tradeflow-order",channel="http://tradeflow-payment-simulator";
    private final String paymentToken,channelToken;
    private final ServiceEndpoints endpoints;
    public PaymentRemote(ObjectMapper json,Environment env,ServiceEndpoints endpoints) {
        this.json=json;this.endpoints=endpoints;
        paymentToken=env.getRequiredProperty("tradeflow.internal.payment-token");channelToken=env.getRequiredProperty("tradeflow.internal.channel-token");if(paymentToken.isBlank()||channelToken.isBlank())throw new IllegalStateException("支付出站内部凭据不能为空");
    }
    /** JWT验签之后仍读取最新撤销版本，失败不能降级接受旧权限。 */
    public AuthContext context(String id) { return call(user+"/internal/v1/users/"+safe(id)+"/auth-context","GET",null,paymentToken,AuthContext.class,false); }
    /** 订单提供唯一支付号、金额和归属；外部调用者不能自行指定资金参数。 */
    public Intent intent(String no) { return call(order+"/internal/v1/orders/"+safe(no)+"/payment-intent","GET",null,paymentToken,Intent.class,false); }
    /** 稳定业务号提交/关闭共享同一通道记录；超时作为未知，不本地合成终态。 */
    public ChannelFact command(ChannelCommand command,boolean close) { return call(channel+"/channel/v1/payments"+(close?"/"+safe(command.paymentNo())+"/close":""),"POST",command,channelToken,ChannelFact.class,false); }
    public ChannelFact query(String no) { return call(channel+"/channel/v1/payments/"+safe(no),"GET",null,channelToken,ChannelFact.class,true); }
    /** 只有订单明确ACK才完成Outbox；丢响应由原eventId重复投递。 */
    public void publish(Event event) {
        Map<?,?> ack=call(order+"/internal/v1/orders/"+safe(event.fact().orderNo())+"/payment-results","POST",event,paymentToken,Map.class,false);
        if(ack==null || !Boolean.TRUE.equals(ack.get("ack")))throw new BusinessException(503,"ORDER_ACK_REQUIRED","订单尚未确认支付事实");
    }
    /** 退款授权只在事务外查询，激活事实不可普通撤销；来源号允许固定CLOSE冒号。 */
    public RefundContracts.Authorization authorization(String source) {
        if(source==null||!source.matches("(?:as_[a-f0-9]{32}|CLOSE:ord_[a-f0-9]{32})"))throw new BusinessException(400,"INVALID_SOURCE","退款来源不合法");
        return call(order+"/internal/v1/refund-authorizations/"+source,"GET",null,paymentToken,RefundContracts.Authorization.class,false);
    }
    /** 通道写入不自动重试；不确定结果由持久任务查询原退款号。 */
    public RefundContracts.ChannelFact refund(RefundContracts.ChannelCommand c){return call(channel+"/channel/v1/refunds","POST",c,channelToken,RefundContracts.ChannelFact.class,false);}
    public RefundContracts.ChannelFact refundQuery(String no){return call(channel+"/channel/v1/refunds/"+safe(no),"GET",null,channelToken,RefundContracts.ChannelFact.class,true);}
    /** 对账冻结使用稳定请求号，网络超时只能重试原号，不直接访问通道数据库。 */
    public ReconciliationContracts.BillManifest freezeBill(ReconciliationContracts.BillRequest body){return call(channel+"/channel/v1/bill-snapshots","POST",body,channelToken,ReconciliationContracts.BillManifest.class,false);}
    /** 只读冻结清单，完整性必须由对账任务再次校验。 */
    public ReconciliationContracts.BillManifest billManifest(String no){return call(channel+"/channel/v1/bill-snapshots/"+safe(no),"GET",null,channelToken,ReconciliationContracts.BillManifest.class,false);}
    /** 按原快照游标读取，分页最大100，不能用实时分页替代冻结账单。 */
    public ReconciliationContracts.BillPage billPage(String no,long after,int size){if(after<0||size<1||size>100)throw new BusinessException(400,"INVALID_CURSOR","账单游标或分页无效");return call(channel+"/channel/v1/bill-snapshots/"+safe(no)+"/lines?after="+after+"&pageSize="+size,"GET",null,channelToken,ReconciliationContracts.BillPage.class,false);}
    /** 退款结果仅交付订单，订单持久ACK之后才完成本地HTTP Outbox。 */
    public void refundPublish(RefundContracts.Event e){Map<?,?> ack=call(order+"/internal/v1/orders/"+safe(e.fact().orderNo())+"/refund-results","POST",e,paymentToken,Map.class,false);if(ack==null||!Boolean.TRUE.equals(ack.get("ack")))throw new BusinessException(503,"ORDER_ACK_REQUIRED","订单尚未确认退款事实");}
    private <T> T call(String url,String method,Object body,String token,Class<T> type,boolean missing) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("禁止在事务中执行支付RPC");
        try {
            HttpRequest.Builder builder=HttpRequest.newBuilder(endpoints.resolve(url)).timeout(Duration.ofSeconds(3)).header("X-Caller-Service","payment").header("X-Service-Token",token).header("Content-Type","application/json");
            com.tradeflow.observability.TracePropagation.apply(builder);
            HttpRequest request=builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            HttpResponse<byte[]> response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
            if(missing && response.statusCode()==404)return null;
            if(response.statusCode()<200 || response.statusCode()>=300)throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","暂时无法确认依赖服务事实");
            ApiResponse<T> envelope=json.readValue(response.body(),json.getTypeFactory().constructParametricType(ApiResponse.class,type));
            if(!"OK".equals(envelope.code()) || envelope.data()==null)throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","依赖响应未确认");return envelope.data();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","依赖请求中断");}
        catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(503,"DEPENDENCY_UNAVAILABLE","依赖请求结果未知");}
    }
    public static String safe(String value){if(value==null || !value.matches("[A-Za-z0-9_-]{1,64}"))throw new BusinessException(400,"INVALID_ARGUMENT","业务编号格式不合法");return value;}
}
