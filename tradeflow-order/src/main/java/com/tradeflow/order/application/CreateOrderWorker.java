package com.tradeflow.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.TradingContracts;
import com.tradeflow.contracts.TradingContracts.*;
import com.tradeflow.order.api.OrderModels.PlaceOrder;
import com.tradeflow.order.domain.OrderRows.*;
import com.tradeflow.order.infrastructure.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

/** 持久订单Saga（沿用CREATE_ORDER任务名）；每次领取提交后才RPC，应用结果前重新检查租约栅栏与订单状态。 */
@Configuration(proxyBeanMethods=false) @EnableScheduling
public class CreateOrderWorker {
    @org.springframework.beans.factory.annotation.Autowired private OrderUpgradeMapper upgrade;
    private final OrderMapper orders;private final TaskMapper tasks;private final OrderTransactions tx;
    private final OrderTaskClaimService taskClaimService;
    private final InventoryGateway inventory;private final PaymentGateway payments;private final ObjectMapper json;
    private final com.tradeflow.messaging.MessageOutbox outbox;
    private final String owner="order_"+UUID.randomUUID().toString().replace("-","");
    private static final Set<String> QUOTE_REJECTIONS=Set.of("PRICE_CHANGED","SKU_NOT_SALEABLE","ORDER_AMOUNT_LIMIT","QUANTITY_LIMIT","LEGACY_QUOTE_NOT_REUSABLE","BUYER_FORBIDDEN","MERCHANT_DISABLED","MERCHANT_NOT_FOUND");
    public CreateOrderWorker(OrderMapper orders,TaskMapper tasks,OrderTransactions tx,OrderTaskClaimService taskClaimService,InventoryGateway inventory,PaymentGateway payments,ObjectMapper json,com.tradeflow.messaging.MessageOutbox outbox) {
        this.taskClaimService=taskClaimService;
        this.orders=orders;this.tasks=tasks;this.tx=tx;this.inventory=inventory;this.payments=payments;this.json=json;this.outbox=outbox;
    }
    /**
     * Spring调度线程逐个领取并推进任务，一轮最多10次；本方法本身不加事务。
     * 领取调用另一个Bean的@Transactional方法，代理提交后才执行库存/支付等RPC。
     * 远程结果写回仍通过guarded的独立短事务核验租约和状态，保留原有恢复协议。
     */
    @Scheduled(fixedDelay=1000,initialDelay=3000)
    public void scan() {
        for(int count=0;count<10;count++) {
            // claimNextTask内部调用独立事务Bean；正常返回到这里时，领取事务已经提交并释放行锁。
            Task task=claimNextTask();
            if(task==null)break;
            // 与上面的领取使用同一线程，但没有继承其已结束的事务；execute按持久业务阶段决定下一步。
            try { execute(task); }
            catch(Exception failure) {
                String code=failure instanceof FeignPolicy.RemoteFailure r?r.code():failure instanceof BusinessException b?b.code():"LOCAL_PROCESSING_ERROR";
                boolean manual=Set.of("REMOTE_CONTRACT_INVALID","RESERVATION_BINDING_CONFLICT","IDEMPOTENCY_CONFLICT","INVENTORY_INVARIANT_BROKEN","STOCK_ALREADY_COMMITTED","INVALID_SERVICE_CREDENTIAL","PAYMENT_MANUAL_REVIEW").contains(code);
                guarded(task,current->{if(manual)tasks.manual(current.taskNo(),code);else retry(current,delay(current.attemptCount()),code);});
            }
        }
    }
    /**
     * 在事务代理外处理本库瞬时锁竞争，保持原tx.write的最多3次重试、20至80毫秒退避和错误码。
     * 每次都经taskClaimService代理重新调用，前一次回滚完成后才能再领；此处不执行任何RPC。
     */
    private Task claimNextTask() {
        for(int attempt=0;attempt<3;attempt++)try {
            return taskClaimService.claimNext(owner);
        } catch(org.springframework.dao.TransientDataAccessException failure) {
            if(attempt==2)throw new BusinessException(503,"DATABASE_BUSY","订单数据库繁忙，请按原编号查询或重试");
            try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(20,81)); }
            catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new BusinessException(503,"REQUEST_INTERRUPTED","请求中断，请查询原编号");
            }
        }
        throw new IllegalStateException("不可达分支");
    }
    /** 按已持久阶段决定下一步；任务状态不是库存事实，原预占必须先查再处理。 */
    private void execute(Task task) {
        Intent intent=orders.intentByNo(task.businessNo());
        if(intent==null)throw new BusinessException(409,"REMOTE_CONTRACT_INVALID","任务缺少创建意图");
        if(Set.of("REJECTED","CANCELLED").contains(intent.state())) { guarded(task,this::finish);return; }
        if("QUOTING".equals(intent.state())) { quote(task,intent);return; }
        Header order=orders.order(intent.orderNo());
        if(order==null)throw new BusinessException(409,"REMOTE_CONTRACT_INVALID","已创建意图缺少订单");
        if("PAID".equals(order.status())) { confirmStock(task,order);return; }
        if(Set.of("PENDING_PAYMENT","CLOSED").contains(order.status())) { guarded(task,this::finish);return; }
        if("CLOSING".equals(order.status())) { close(task,order);return; }
        if(!"CREATING".equals(order.status()))throw new BusinessException(409,"REMOTE_CONTRACT_INVALID","订单状态与创建任务矛盾");
        if(!order.payDeadline().isAfter(Instant.now())) {
            guarded(task,current->{
                orders.lockIntent(order.orderNo());Header latest=orders.lockOrder(order.orderNo());
                if("CREATING".equals(latest.status()))startClosing(latest,"CREATE_DEADLINE_EXPIRED",current.taskNo());
                retry(current,0,"CREATE_DEADLINE_EXPIRED");
            });return;
        }
        ReservationCommand command=command(order);
        Reservation result;
        try { result=inventory.query(order.reservationNo()); }
        catch(FeignPolicy.RemoteFailure missing) {
            if(missing.status()!=404 || !"RESERVATION_NOT_FOUND".equals(missing.code()))throw missing;
            result=inventory.reserve(command);
        }
        validateReservation(result,command);
        Reservation fact=result;
        guarded(task,current->{
            orders.lockIntent(order.orderNo());Header latest=orders.lockOrder(order.orderNo());
            if("CLOSING".equals(latest.status())) { retry(current,0,"CLOSE_AFTER_LATE_RESERVATION");return; }
            if(!"CREATING".equals(latest.status())) { finish(current);return; }
            if("RESERVED".equals(fact.status()) && orders.pending(order.orderNo())==1) {
                orders.log(order.orderNo(),"CREATING","PENDING_PAYMENT","STOCK_RESERVED","SYSTEM",current.taskNo(),"整单库存预占已确认");
                orders.intentState(order.orderNo(),"ORDER_CREATED","ORDER_READY");orders.requestResult(order.orderNo(),"SUCCEEDED","ORDER_READY");finish(current);
            } else {
                String code="RESERVED".equals(fact.status())?"CREATE_DEADLINE_EXPIRED":fact.rejectCode()==null?"STOCK_NOT_RESERVED":fact.rejectCode();
                startClosing(latest,code,current.taskNo());retry(current,0,code);
            }
        });
    }
    /** 报价RPC在事务外；只有当前任务栅栏和仍QUOTING的意图可以建立订单快照。 */
    private void quote(Task task,Intent intent) {
        if(!intent.createdAt().plusSeconds(900).isAfter(Instant.now())) { rejectIntent(task,"CREATE_DEADLINE_EXPIRED");return; }
        PlaceOrder input=decode(intent.requestPayload());
        QuoteCommand command=new QuoteCommand(intent.orderNo(),intent.userId(),input.effectiveMerchant(),input.items().stream()
                .map(i->new Line(i.skuId(),i.quantity(),i.expectedUnitPriceCents())).toList());
        Quote quote;
        try { quote=inventory.quote(command); }
        catch(FeignPolicy.RemoteFailure failure) {
            if(QUOTE_REJECTIONS.contains(failure.code())) { rejectIntent(task,failure.code());return; }throw failure;
        }
        validateQuote(quote,command);
        if(!quote.expiresAt().isAfter(Instant.now())) { rejectIntent(task,"QUOTE_EXPIRED");return; }
        guarded(task,current->{
            Intent latest=orders.lockIntent(intent.orderNo());
            if(!"QUOTING".equals(latest.state())) {
                if(Set.of("CANCELLED","REJECTED").contains(latest.state()))finish(current);else retry(current,0,"RESUME_CREATED_ORDER");return;
            }
            if(!latest.createdAt().plusSeconds(900).isAfter(Instant.now())) { rejectLocked(current,latest,"CREATE_DEADLINE_EXPIRED");return; }
            var charge=upgrade.chargeByOrder(latest.orderNo());
            long shipping=charge==null?0:((Number)charge.get("shipping_cents")).longValue();
            long total=Math.addExact(quote.amountCents(),shipping);
            if(total>100000000){rejectLocked(current,latest,"ORDER_AMOUNT_LIMIT");return;}
            int unitCount=quote.items().stream().mapToInt(QuotedItem::quantity).sum();
            long perUnit=shipping/unitCount,remainder=shipping%unitCount;
            Map<String,Object> row=new HashMap<>();row.put("merchantId",input.effectiveMerchant());row.put("orderNo",latest.orderNo());row.put("userId",latest.userId());row.put("paymentNo",latest.paymentNo());
            row.put("reservationNo",latest.reservationNo());row.put("quoteNo",quote.quoteNo());row.put("totalAmountCents",total);row.put("payDeadline",latest.createdAt().plusSeconds(900));
            orders.createOrder(row);
            for(var item:quote.items().stream().sorted(Comparator.comparing(i->new java.math.BigInteger(i.skuId()))).toList()) {
                Map<String,Object> detail=new HashMap<>();detail.put("orderNo",latest.orderNo());detail.put("productId",item.productId());detail.put("skuId",item.skuId());
                detail.put("productNameSnapshot",item.productName());detail.put("specSnapshot",item.specification());detail.put("unitPriceCents",item.expectedUnitPriceCents());
                long extras=Math.min(remainder,item.quantity());
                detail.put("quantity",item.quantity());detail.put("lineAmountCents",Math.addExact(Math.multiplyExact(item.expectedUnitPriceCents()+perUnit,item.quantity()),extras));orders.item(detail);
                // 固定SKU/购买件顺序均分运费，尾差前置，退款始终以购买件实付为权威。
                for(int unit=1;unit<=item.quantity();unit++)orders.units(detail.get("id").toString(),item.expectedUnitPriceCents()+perUnit+(unit<=extras?1:0),List.of(unit));
                remainder-=extras;
            }
            var a=input.address();orders.address(latest.orderNo(),a.receiverName(),a.receiverPhone(),a.region(),a.detailAddress());
            orders.log(latest.orderNo(),"NONE","CREATING","CREATE_ORDER","SYSTEM",current.taskNo(),"成交报价与购买件快照已保存");
            orders.intentState(latest.orderNo(),"ORDER_CREATED","STOCK_PENDING");retry(current,0,"STOCK_PENDING");
        });
    }
    /** 未建立订单前的明确拒绝不会占库存；同事务保存幂等终态并清除临时地址材料。 */
    private void rejectIntent(Task task,String code) {
        guarded(task,current->{Intent latest=orders.lockIntent(task.businessNo());
            if("QUOTING".equals(latest.state()))rejectLocked(current,latest,code);
            else if(Set.of("REJECTED","CANCELLED").contains(latest.state()))finish(current);else retry(current,0,"RESUME_CREATED_ORDER");
        });
    }
    private void rejectLocked(Task task,Intent intent,String code) {
        orders.intentState(intent.orderNo(),"REJECTED",code);orders.requestResult(intent.orderNo(),"REJECTED",code);finish(task);
    }
    /** 库存释放确认前保持CLOSING；释放先到也留墓碑，不能被迟到reserve重新占用。 */
    private void close(Task task,Header order) {
        if(!"RELEASE".equals(order.stockDecision()))throw new BusinessException(409,"REMOTE_CONTRACT_INVALID","关闭缺少持久释放决策");
        if("LATE_PAYMENT".equals(order.closePhase())) { guarded(task,this::finish);return; }
        if("PAYMENT_REQUIRED".equals(order.closePhase())) {
            var fact=payments.close(order.paymentNo(),order.orderNo());
            if(fact==null || fact.status()==null || !Set.of("PENDING","UNKNOWN","PROCESSING","SUCCEEDED","FAILED","CLOSED").contains(fact.status()) || !order.paymentNo().equals(fact.paymentNo()) || !order.orderNo().equals(fact.orderNo()) || order.totalAmountCents()!=fact.amountCents() || !order.currency().equals(fact.currency()))throw invalidRemote();
            guarded(task,current->{
                orders.lockIntent(order.orderNo());Header latest=orders.lockOrder(order.orderNo());
                if("LATE_PAYMENT".equals(latest.closePhase()) || "SUCCEEDED".equals(fact.status())) {
                    orders.latePayment(order.orderNo());finish(current);
                    orders.audit("SYSTEM","order","LATE_PAYMENT",order.orderNo(),current.taskNo(),current.taskNo(),"关闭竞争发现成功支付，等待独立退款来源任务","{\"obligation\":\"REFUND_REQUIRED\"}");
                } else if(Set.of("CLOSED","FAILED").contains(fact.status())) {
                    orders.channelClosed(order.orderNo());retry(current,0,"CHANNEL_CLOSED");
                } else retry(current,delay(current.attemptCount()),"WAIT_PAYMENT_CLOSE");
            });return;
        }
        Reservation result=inventory.release(order.reservationNo(),order.orderNo());
        if(!order.orderNo().equals(result.orderNo()) || !order.reservationNo().equals(result.reservationNo())
                || !Set.of("RELEASED","REJECTED").contains(result.status()))throw new BusinessException(409,"REMOTE_CONTRACT_INVALID","释放尚无可信终态");
        guarded(task,current->{
            Intent intent=orders.lockIntent(order.orderNo());Header latest=orders.lockOrder(order.orderNo());
            if("LATE_PAYMENT".equals(latest.closePhase())) { finish(current);return; }
            if("CLOSING".equals(latest.status()) && "RELEASE".equals(latest.stockDecision())) {
                orders.closed(order.orderNo());outbox.enqueue("ORDER",order.orderNo(),orders.currentVersion(order.orderNo()),"OrderClosed",order.orderNo(),current.taskNo(),Map.of("orderNo",order.orderNo(),"reason","CREATION_CLOSED"));orders.log(order.orderNo(),"CLOSING","CLOSED","CREATION_CLOSED","SYSTEM",current.taskNo(),"库存已释放或终态未占用");
                String code=intent.resultCode()==null?"CREATION_CLOSED":intent.resultCode();orders.requestResult(order.orderNo(),"REJECTED",code);
            }
            finish(current);
        });
    }
    /** 创建与待支付到期分阶段扫描；待支付必须激活原流程并经过通道关闭屏障。 */
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    public void expireCreating() {
        for(String no:orders.expiredCreating())tx.write(()->{
            orders.lockIntent(no);Header latest=orders.lockOrder(no);
            if(latest!=null && "CREATING".equals(latest.status()) && !latest.payDeadline().isAfter(Instant.now()))startClosing(latest,"CREATE_DEADLINE_EXPIRED","expiry");
            return null;
        });
        for(String no:orders.expiredPending())tx.write(()->{
            var flow=tasks.lockByOrder(no);orders.lockIntent(no);Header latest=orders.lockOrder(no);
            if(flow!=null && latest!=null && "PENDING_PAYMENT".equals(latest.status()) && !latest.payDeadline().isAfter(Instant.now())) {
                orders.closingPaidWindow(no,"PAYMENT_DEADLINE_EXPIRED");orders.intentState(no,"ORDER_CREATED","PAYMENT_DEADLINE_EXPIRED");
                orders.log(no,"PENDING_PAYMENT","CLOSING","PAYMENT_TIMEOUT","SYSTEM","expiry","支付期限到期，申请通道关闭屏障");tasks.activate(flow.taskNo());
            }
            return null;
        });
    }
    /** 已接受支付的COMMIT决策只能确认原预占；事务外调用后以栅栏应用确认事实。 */
    private void confirmStock(Task task,Header order) {
        if(!"COMMIT".equals(order.stockDecision()))throw invalidRemote();
        if("CONFIRMED".equals(order.stockSyncStatus())) { guarded(task,this::finish);return; }
        var fact=inventory.confirm(order.reservationNo(),order.orderNo());
        if(!order.orderNo().equals(fact.orderNo()) || !order.reservationNo().equals(fact.reservationNo()) || !"CONFIRMED".equals(fact.status()))throw invalidRemote();
        guarded(task,current->{
            orders.lockIntent(order.orderNo());Header latest=orders.lockOrder(order.orderNo());
            if("PAID".equals(latest.status()) && "COMMIT".equals(latest.stockDecision())) {
                if(orders.stockConfirmed(order.orderNo())==1)orders.audit("SYSTEM","order","CONFIRM_STOCK",order.orderNo(),current.taskNo(),current.taskNo(),"支付成功后确认原预占","{\"stockSyncStatus\":\"CONFIRMED\"}");
                finish(current);
            } else throw invalidRemote();
        });
    }
    /** 关闭决策单向持久化，与状态日志同事务；不在此发库存RPC。 */
    private void startClosing(Header header,String code,String request) {
        if(orders.closing(header.orderNo(),code)==1) {
            orders.intentState(header.orderNo(),"ORDER_CREATED",code);
            orders.log(header.orderNo(),"CREATING","CLOSING","CREATE_ABORT","SYSTEM",request,code);
        }
    }
    /** 使用本库不可变成交明细及固定deadline构造预占摘要，所有重试字节语义一致。 */
    private ReservationCommand command(Header order) {
        var lines=orders.items(order.orderNo()).stream().map(i->new ReservationLine(i.skuId(),i.quantity())).toList();
        var unsigned=new ReservationCommand(order.reservationNo(),order.orderNo(),order.quoteNo(),order.userId(),null,order.payDeadline(),lines);
        return new ReservationCommand(unsigned.reservationNo(),unsigned.orderNo(),unsigned.quoteNo(),unsigned.userId(),TradingContracts.reservationHash(unsigned),unsigned.payDeadline(),lines);
    }
    /** 单价、数量、总额和元数据逐项核对，错误响应进入人工，不能据不可信金额建单。 */
    private void validateQuote(Quote quote,QuoteCommand command) {
        if(quote==null || quote.quoteNo()==null || !quote.quoteNo().matches("quo_[a-f0-9]{32}") || quote.expiresAt()==null
                || quote.items()==null || quote.items().size()!=command.items().size())throw invalidRemote();
        long amount=0;Set<String> seen=new HashSet<>();
        for(var item:quote.items()) {
            if(item==null || item.productId()==null || !item.productId().matches("[1-9][0-9]{0,18}") || item.productName()==null || item.productName().isBlank()
                    || item.productName().length()>200 || item.specification()==null || item.specification().length()>500 || !seen.add(item.skuId()))throw invalidRemote();
            var requested=command.items().stream().filter(i->i.skuId().equals(item.skuId())).findFirst().orElseThrow(this::invalidRemote);
            if(item.quantity()!=requested.quantity() || item.expectedUnitPriceCents()!=requested.expectedUnitPriceCents() || item.priceVersion()<0)throw invalidRemote();
            amount=Math.addExact(amount,Math.multiplyExact(item.expectedUnitPriceCents(),item.quantity()));
        }
        if(amount!=quote.amountCents() || amount<=0 || amount>100000000)throw invalidRemote();
    }
    /** 返回状态必须绑定原编号及完整指纹，释放墓碑允许没有先前请求摘要。 */
    private void validateReservation(Reservation fact,ReservationCommand command) {
        if(fact==null || !command.orderNo().equals(fact.orderNo()) || !command.reservationNo().equals(fact.reservationNo())
                || !Set.of("RESERVED","REJECTED","RELEASED").contains(fact.status())
                || (!("RELEASED".equals(fact.status()) && fact.requestHash()==null) && !command.requestHash().equals(fact.requestHash())))throw invalidRemote();
    }
    private BusinessException invalidRemote() { return new BusinessException(409,"REMOTE_CONTRACT_INVALID","远程业务事实与原请求不一致"); }
    private PlaceOrder decode(String value) {
        try { return json.readValue(value,PlaceOrder.class); }catch(Exception failure){throw new BusinessException(409,"REMOTE_CONTRACT_INVALID","持久创建材料无法解析");}
    }
    /** 应用本地结果前锁任务并检查owner/claim_version/租约，旧执行者只能放弃。 */
    private void guarded(Task task,Consumer<Task> action) {
        tx.write(()->{Task current=tasks.fence(task.taskNo(),task.leaseOwner(),task.claimVersion());if(current!=null)action.accept(current);return null;});
    }
    /** 完成条件更新必须成功，否则回滚本地业务应用，不能完成新一轮任务。 */
    private void finish(Task task) {
        if(tasks.finish(task.taskNo(),task.leaseOwner(),task.claimVersion())!=1)throw new IllegalStateException("任务栅栏已失效");
    }
    private void retry(Task task,int seconds,String code) {
        if(tasks.retry(task.taskNo(),task.leaseOwner(),task.claimVersion(),seconds,code)!=1)throw new IllegalStateException("任务栅栏已失效");
    }
    private int delay(int attempts) { return switch(attempts){case 0,1->5;case 2->15;case 3->60;case 4->300;default->900;}; }
}
