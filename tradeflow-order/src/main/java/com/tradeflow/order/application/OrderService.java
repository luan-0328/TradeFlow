package com.tradeflow.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.TradingContracts;
import com.tradeflow.order.api.OrderModels.*;
import com.tradeflow.order.domain.OrderRows.*;
import com.tradeflow.order.infrastructure.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** 买家订单用例；先保存意图和任务，异步结果通过固定进度URL查询。 */
@Service
public class OrderService {
    private static final String SEALED_HASH=TradingContracts.sha256("TRADEFLOW:SEALED:PLACE_ORDER:V1");
    private final OrderMapper orders;private final TaskMapper tasks;private final OrderAuthority authority;
    @org.springframework.beans.factory.annotation.Autowired private OrderUpgradeMapper upgrade;
    private final OrderTransactions tx;private final ObjectMapper json;private final FulfillmentMapper fulfillment;private final EntitlementMapper entitlements;
    public OrderService(OrderMapper orders,TaskMapper tasks,OrderAuthority authority,OrderTransactions tx,ObjectMapper json,FulfillmentMapper fulfillment,EntitlementMapper entitlements) {
        this.orders=orders;this.tasks=tasks;this.authority=authority;this.tx=tx;this.json=json;this.fulfillment=fulfillment;this.entitlements=entitlements;
    }
    /**
     * 同键按用户作用域串行化，摘要含合并排序SKU、预期单价及完整地址。
     * 本事务不查远程报价，稳定业务号/创建意图/任务一次提交，进程宕机也可恢复。
     */
    public Accepted place(Jwt jwt,String key,PlaceOrder input,String request,String trace) {
        authority.authenticate(jwt,false);
        if(key==null || !key.matches("[A-Za-z0-9_.:-]{1,64}")) throw new BusinessException(400,"INVALID_IDEMPOTENCY_KEY","需要稳定幂等键");
        PlaceOrder normalized=normalize(input);
        return tx.write(()->acceptLocked(jwt, key, normalized, request, trace));
    }
    /** 交易组复用本库受理流程；调用方已认证并持有短事务，不执行任何RPC。 */
    Accepted acceptLocked(Jwt jwt,String key,PlaceOrder normalized,String request,String trace) {
        String payload=encode(normalized),hash=TradingContracts.sha256(payload);
        String order=number("ord_"),payment=number("pay_"),reservation=number("res_"),task=number("tsk_");
        orders.claimRequest(jwt.getSubject(),key,hash,order);
        var existing=orders.lockRequest(jwt.getSubject(),key);
        if(SEALED_HASH.equals(existing.requestHash()))return accepted(existing.businessNo());
        if(!hash.equals(existing.requestHash()) && !legacyHash(normalized).equals(existing.requestHash()))throw new BusinessException(409,"IDEMPOTENCY_CONFLICT","同一幂等键不能修改商品、商家、价格、运费或地址");
        Intent intent=orders.intentByNo(existing.businessNo());
        if(intent==null) {
            String merchant=normalized.effectiveMerchant();
            upgrade.ensureShipping(merchant);var rule=upgrade.lockShipping(merchant);
            long fee=((Number)rule.get("fee_cents")).longValue();
            if(normalized.expectedShippingCents()==null||fee!=normalized.expectedShippingCents())throw new BusinessException(409,"SHIPPING_CHANGED","运费已变化，请刷新确认后提交");
            orders.intent(existing.businessNo(),jwt.getSubject(),payment,reservation,hash,payload);
            upgrade.charge(existing.businessNo(),merchant,fee,((Number)rule.get("version")).longValue());
            orders.task(task,existing.businessNo());
            orders.audit("USER",jwt.getSubject(),"PLACE_ORDER_ACCEPTED",existing.businessNo(),request,trace,"受理下单创建意图","{}");
        }
        return accepted(existing.businessNo());
    }
    /** 进度先校验买家归属，不返回报价前含地址的恢复材料；动作按同一订单快照映射，取消写入仍锁任务和意图重验。 */
    public Progress progress(Jwt jwt,String no) {
        authority.authenticate(jwt,false);
        return tx.read(()->requestProgress(no,jwt.getSubject()));
    }
    /** 原键只在请求头传输；仅查本人PLACE_ORDER，一致读不加锁且不把暂时缺失当作可重新下单的证据。 */
    public RequestLookup lookupRequest(Jwt jwt,String key) {
        authority.authenticate(jwt,false);validateRequestKey(key);
        return tx.read(()->{var row=orders.requestByKey(jwt.getSubject(),key);return new RequestLookup(row!=null,row==null?null:requestProgress(row.businessNo(),jwt.getSubject()));});
    }
    /**
     * 仅封住尚未受理的原键；与迟到place竞争同一唯一行，先提交者决定返回原订单或取消墓碑。
     * 已受理时只返回原进度，不撤销订单；新墓碑不创建库存/支付/恢复任务，超时必须继续查原键。
     */
    public Progress sealRequest(Jwt jwt,String key,Reason reason,String request,String trace) {
        authority.authenticate(jwt,false);validateRequestKey(key);
        String no=number("ord_"),payment=number("pay_"),reservation=number("res_");
        return tx.write(()->{
            orders.claimRequest(jwt.getSubject(),key,SEALED_HASH,no);
            var row=orders.lockRequest(jwt.getSubject(),key);
            if(no.equals(row.businessNo())) {
                orders.sealedIntent(no,jwt.getSubject(),payment,reservation,SEALED_HASH);
                orders.requestResult(no,"REJECTED","REQUEST_SEALED");
                orders.audit("USER",jwt.getSubject(),"SEAL_UNACCEPTED_ORDER",no,request,trace,reason.reason(),"{\"sealed\":true}");
            }
            return requestProgress(row.businessNo(),jwt.getSubject());
        });
    }
    /** 在调用方既有事务快照中读取安全进度，不嵌套远程身份查询或返回地址。 */
    private Progress requestProgress(String no,String user) {
        Intent intent=ownedIntent(no,user);Header order=orders.order(no);Task task=tasks.byOrder(no);
        String state=order==null?intent.state():order.status();
        return new Progress(no,state,intent.resultCode(),order==null?null:"/api/v1/orders/"+no,task==null?("REQUEST_SEALED".equals(intent.resultCode())?"NOT_REQUIRED":"UNKNOWN"):task.state(),Set.of("QUOTING","CREATING").contains(state)?List.of("CANCEL_CREATING"):List.of());
    }
    /** 查询与封键沿用下单的ASCII键约束，不回显请求键或允许跨主体指定账号。 */
    private void validateRequestKey(String key) { if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,64}"))throw new BusinessException(400,"INVALID_IDEMPOTENCY_KEY","需要稳定幂等键"); }
    /** 列表只查本人，避免每页展开最多1980件的所有权益；件级详情在单单查询提供。 */
    public Page<OrderView> list(Jwt jwt,int page,int size) {
        authority.authenticate(jwt,false);pagination(page,size);
        return tx.read(()->new Page<>(orders.list(jwt.getSubject(),size,(long)(page-1)*size).stream().map(h->view(h,false)).toList(),page,size,orders.count(jwt.getSubject())));
    }
    /** 条件筛选仍以本人为强制范围，完整订单号精确查询，不返回其他买家资源是否存在。 */
    public Page<OrderView> search(Jwt jwt,int page,int size,String status,String no){
        authority.authenticate(jwt,false);pagination(page,size);
        if(status!=null&&!status.isBlank()&&!Set.of("CREATING","PENDING_PAYMENT","PAID","SHIPPED","COMPLETED","CLOSING","CLOSED","REFUNDED").contains(status))throw new BusinessException(400,"INVALID_STATUS","订单状态无效");
        if(no!=null&&!no.isBlank()&&!no.matches("ord_[a-f0-9]{32}"))throw new BusinessException(400,"INVALID_ORDER_NO","请输入完整订单号");
        return tx.read(()->new Page<>(upgrade.searchOrders(jwt.getSubject(),status,no,size,(long)(page-1)*size).stream().map(orders::order).map(h->view(h,false)).toList(),page,size,upgrade.countOrders(jwt.getSubject(),status,no)));
    }
    /** 订单已经落地才能返回金额/商品视图，报价中请使用受理返回的进度URL。 */
    public OrderView detail(Jwt jwt,String no) {
        authority.authenticate(jwt,false);
        return tx.read(()->{
            Header header=orders.order(no);
            if(header==null || !jwt.getSubject().equals(header.userId()))throw missing();return view(header,true);
        });
    }
    /**
     * 取消创建中或待支付订单，按任务→意图→订单加锁；持久关闭决策后交原流程恢复。
     * 待支付取消递增任务栅栏，先确认通道关闭才能释放库存；迟到成功支付转人工退款义务。
     */
    public Accepted cancel(Jwt jwt,String no,Reason input,String request,String trace) {
        authority.authenticate(jwt,false);
        return tx.write(()->{
            Task flow=tasks.lockByOrder(no);
            Intent intent=orders.lockIntent(no);
            if(intent==null || !jwt.getSubject().equals(intent.userId()))throw missing();
            if("CANCELLED".equals(intent.state()) || "REJECTED".equals(intent.state()))return accepted(no);
            Header order=orders.lockOrder(no);
            if(order==null) {
                orders.intentState(no,"CANCELLED","CREATION_CANCELLED");orders.requestResult(no,"REJECTED","CREATION_CANCELLED");
            } else if("CREATING".equals(order.status())) {
                orders.closing(no,input.reason());orders.log(no,"CREATING","CLOSING","CANCEL_CREATING",jwt.getSubject(),request,input.reason());
                orders.intentState(no,"ORDER_CREATED","CREATION_CANCELLED");
            } else if("PENDING_PAYMENT".equals(order.status())) {
                orders.closingPaidWindow(no,input.reason());orders.intentState(no,"ORDER_CREATED","CANCEL_REQUESTED");
                orders.log(no,"PENDING_PAYMENT","CLOSING","CANCEL_PENDING",jwt.getSubject(),request,input.reason());
                if(flow==null)throw new BusinessException(409,"TASK_NOT_FOUND","原订单流程任务缺失");tasks.activate(flow.taskNo());
            } else if(!Set.of("CLOSING","CLOSED").contains(order.status())) {
                throw new BusinessException(409,"ORDER_NOT_CANCELLABLE","已付款订单不能直接取消，请走后续售后流程");
            }
            orders.audit("USER",jwt.getSubject(),"CANCEL_CREATING",no,request,trace,input.reason(),"{\"decision\":\"STOP_CREATION\"}");
            return accepted(no);
        });
    }
    /** 平台可查看本批恢复任务，不获取买家地址或其他服务任务。 */
    public Page<TaskView> tasks(Jwt jwt,int page,int size) {
        authority.authenticate(jwt,true);pagination(page,size);
        return tx.read(()->new Page<>(tasks.list(size,(long)(page-1)*size),page,size,tasks.count()));
    }
    /** 人工重启只改变原任务栅栏与本轮预算，保留稳定订单/预占编号和审计。 */
    public Accepted retryTask(Jwt jwt,String no,Reason reason,String request,String trace) {
        authority.authenticate(jwt,true);
        return tx.write(()->{
            Task task=tasks.lock(no);if(task==null)throw new BusinessException(404,"TASK_NOT_FOUND","任务不存在");
            if(!"MANUAL_REVIEW".equals(task.state()))throw new BusinessException(409,"TASK_NOT_MANUAL","只允许重启人工处理任务");
            tasks.restart(no);orders.audit("PLATFORM",jwt.getSubject(),"RESTART_CREATE_ORDER",task.businessNo(),request,trace,reason.reason(),"{\"taskNo\":\""+no+"\"}");
            return accepted(task.businessNo());
        });
    }
    /** SQL结果不能替代资源归属检查，不向其他买家透露订单是否存在。 */
    private Intent ownedIntent(String no,String user) { Intent intent=orders.intentByNo(no);if(intent==null || !user.equals(intent.userId()))throw missing();return intent; }
    private Accepted accepted(String no) {
        var intent=orders.intentByNo(no);var header=orders.order(no);
        String state=header==null?intent.state():header.status();
        return new Accepted(no,state,"/api/v1/orders/requests/"+no,actions(state));
    }
    /** 成交快照来自订单本库，后续商品修改不会影响；列表不展开购买件减少读取负担。 */
    OrderView view(Header header,boolean units) { return view(header,units,true); }
    /** 商家复用安全快照字段，不生成买家操作或展开其购买件权益。 */
    OrderView view(Header header,boolean units,boolean buyer) {
        var items=orders.items(header.orderNo()).stream().map(i->new ItemView(i.id(),i.skuId(),i.productNameSnapshot(),i.specSnapshot(),i.unitPriceCents(),i.quantity(),units?orders.unitsByItem(i.id()):List.<UnitView>of())).toList();
        return new OrderView(header.orderNo(),header.paymentNo(),header.status(),header.stockSyncStatus(),header.fulfillmentLock(),header.totalAmountCents(),header.currency(),header.payDeadline(),items,
                buyer?buyerActions(header):List.of(),header.createdAt(),shipment(header.orderNo()));
    }
    /** 只投影承运商和运单，绝不返回联系人、电话或地址。 */
    Shipment shipment(String no) { var row=fulfillment.shipmentByOrder(no);return row==null?null:new Shipment((String)row.get("carrier"),(String)row.get("tracking_no")); }
    /** 本库一致快照保守提供按钮；逐件金额、并发占用和七日期限仍由提交用例重新校验。 */
    private List<String> buyerActions(Header h) {
        var result=new ArrayList<>(actions(h.status()));
        if("SHIPPED".equals(h.status()))result.add("RECEIVE");
        if(!Set.of("PAID","SHIPPED","COMPLETED").contains(h.status()))return List.copyOf(result);
        var units=orders.items(h.orderNo()).stream().flatMap(i->orders.unitsByItem(i.id()).stream()).toList();
        boolean free=units.stream().anyMatch(u->!u.hasActiveAftersale() && u.reservedRefundCents()==0 && "NONE".equals(u.returnState()) && u.remainingRefundCents()>0);
        if("PAID".equals(h.status()) && "CONFIRMED".equals(h.stockSyncStatus()) && "NONE".equals(h.fulfillmentLock()) && !units.isEmpty() && units.stream().allMatch(u->!u.hasActiveAftersale() && u.reservedRefundCents()==0 && u.succeededRefundCents()==0 && "NONE".equals(u.returnState())))result.add("UNSHIPPED_FULL");
        if("CONFIRMED".equals(h.stockSyncStatus()) && "NONE".equals(h.fulfillmentLock()) && free && entitlements.eligible(h.orderNo())==1)result.addAll(List.of("REFUND_ONLY","RETURN_REFUND"));
        return List.copyOf(result);
    }
    /** 合并重复SKU但不能合并不同预期价格；地址参与摘要，幂等重试不可静默替换。 */
    PlaceOrder normalize(PlaceOrder input) {
        Map<String,Line> lines=new TreeMap<>(Comparator.comparing(java.math.BigInteger::new));
        for(var line:input.items()) {
            Line old=lines.get(line.skuId());
            if(old!=null && !old.expectedUnitPriceCents().equals(line.expectedUnitPriceCents()))throw new BusinessException(409,"PRICE_CHANGED","同一规格预期价格不一致");
            int qty=line.quantity()+(old==null?0:old.quantity());if(qty>99)throw new BusinessException(400,"QUANTITY_LIMIT","每个规格最多99件");
            lines.put(line.skuId(),new Line(line.skuId(),qty,line.expectedUnitPriceCents()));
        }
        var a=input.address();return new PlaceOrder(List.copyOf(lines.values()),new Address(a.receiverName().strip(),a.receiverPhone().strip(),a.region().strip(),a.detailAddress().strip()),input.effectiveMerchant(),input.expectedShippingCents()==null?0L:input.expectedShippingCents());
    }
    /** 仅默认商家零运费兼容旧两字段摘要；保留旧请求原号，不为其他商家复用旧幂等结果。 */
    private String legacyHash(PlaceOrder body){
        if(!"merchant_demo".equals(body.effectiveMerchant())||body.expectedShippingCents()==null||body.expectedShippingCents()!=0)return "";
        var legacy=new LinkedHashMap<String,Object>();legacy.put("items",body.items());legacy.put("address",body.address());return TradingContracts.sha256(encode(legacy));
    }
    /** 编码仅进入受保护恢复字段，异常不带原始请求或地址。 */
    private String encode(Object object) { try { return json.writeValueAsString(object); }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("下单请求无法编码");} }
    private String number(String prefix) { return prefix+UUID.randomUUID().toString().replace("-",""); }
    private BusinessException missing() { return new BusinessException(404,"ORDER_NOT_FOUND","订单或创建请求不存在"); }
    private void pagination(int page,int size) { if(page<1||size<1||size>100)throw new BusinessException(400,"VALIDATION_ERROR","分页参数不合法"); }
    /** 创建和支付阶段保留既有动作名；履约和售后动作由订单权威快照补充。 */
    private List<String> actions(String state){return List.of("QUOTING","CREATING").contains(state)?List.of("CANCEL_CREATING"):"PENDING_PAYMENT".equals(state)?List.of("PAY","CANCEL"):List.of();}
}
