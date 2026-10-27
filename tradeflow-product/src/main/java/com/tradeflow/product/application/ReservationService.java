package com.tradeflow.product.application;

import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.TradingContracts;
import com.tradeflow.contracts.TradingContracts.*;
import com.tradeflow.product.infrastructure.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 多SKU库存协议：预占事实/拒绝/释放各有数据库终态，RPC失败不决定库存事实。 */
@Service
public class ReservationService {
    private final ReservationMapper reservations;private final ProductMapper products;private final TransactionTemplate tx;
    public ReservationService(ReservationMapper reservations,ProductMapper products,PlatformTransactionManager manager) {
        this.reservations=reservations;this.products=products;this.tx=new TransactionTemplate(manager);
    }
    /** 校验完整指纹后整单预占；失败先回滚，再独立保存拒绝，并尊重并发已成立结果。 */
    public Reservation reserve(ReservationCommand command) {
        validate(command);
        try { return retry(()->reserveInTransaction(command)); }
        catch(BusinessException failure) {
            if(!Set.of("QUOTE_EXPIRED","QUOTE_NOT_USABLE","SKU_NOT_SALEABLE","INSUFFICIENT_STOCK","CREATE_DEADLINE_EXPIRED").contains(failure.code())) throw failure;
            return retry(()->rejectAfterRollback(command,failure.code()));
        }
    }
    /** 事务中只访问本库；重复成功先返回，不因为报价后来过期而否定既有预占。 */
    private Reservation reserveInTransaction(ReservationCommand c) {
        reservations.create(c.reservationNo(),c.orderNo(),c.quoteNo(),c.requestHash(),"RESERVED","UNDECIDED",c.payDeadline(),null);
        var head=binding(reservations.lock(c.reservationNo()),c.orderNo());
        if(!"RESERVED".equals(head.status())) {
            if(head.requestHash()!=null)sameRequest(head,c);return view(head);
        }
        sameRequest(head,c);
        if(!reservations.items(c.reservationNo()).isEmpty()) return view(head);
        var quote=reservations.lockQuote(c.quoteNo());
        if(quote==null || !c.userId().equals(quote.userId()) || !c.orderNo().equals(quote.sourceOrderNo())
                || (!"VALID".equals(quote.status()) && !("BOUND".equals(quote.status()) && c.orderNo().equals(quote.boundOrderNo()))))
            throw new BusinessException(409,"QUOTE_NOT_USABLE","报价与订单/买家不匹配");
        if(!quote.valid()) throw new BusinessException(409,"QUOTE_EXPIRED","报价已过期");
        if(!c.payDeadline().isAfter(Instant.now())) throw new BusinessException(409,"CREATE_DEADLINE_EXPIRED","创建窗口已过期");
        var items=sorted(c.items());var quoted=reservations.quoteItems(c.quoteNo());
        String merchantId=reservations.quoteMerchant(c.quoteNo());
        if(merchantId==null) throw new BusinessException(409,"QUOTE_NOT_USABLE","报价商家事实缺失");
        if(quoted.size()!=items.size()) throw new BusinessException(409,"QUOTE_NOT_USABLE","报价明细不匹配");
        // 先固定可售状态，再按同一SKU顺序锁余额；任何失败回滚头、绑定和全部SKU。
        for(int i=0;i<items.size();i++) {
            var item=items.get(i);var actual=quoted.get(i);
            if(!item.skuId().equals(actual.skuId()) || item.quantity()!=actual.quantity()) throw new BusinessException(409,"QUOTE_NOT_USABLE","报价数量不匹配");
            var sku=products.quoteSku(item.skuId());
            if(sku==null || !merchantId.equals(sku.merchantId()) || !"ON_SALE".equals(sku.productStatus()) || !"ACTIVE".equals(sku.status()))
                throw new BusinessException(409,"SKU_NOT_SALEABLE","规格不可售");
        }
        if(reservations.bind(c.quoteNo(),c.orderNo())!=1) throw new BusinessException(409,"QUOTE_NOT_USABLE","报价已被占用");
        for(var item:items) {
            var before=products.lockInventory("warehouse_main",item.skuId());
            if(before==null || reservations.reserve(item.skuId(),item.quantity())!=1) throw new BusinessException(409,"INSUFFICIENT_STOCK","整单库存不足");
            reservations.item(c.reservationNo(),item.skuId(),item.quantity());
            reservations.ledger(c.reservationNo(),"RESERVE",item.skuId(),-item.quantity(),item.quantity(),before.availableQty()-item.quantity(),Math.addExact(before.reservedQty(),item.quantity()));
        }
        return view(reservations.lock(c.reservationNo()));
    }
    /** 回滚之后才写拒绝；若另一重试已成功或释放，返回其权威结果，绝不改写为失败。 */
    private Reservation rejectAfterRollback(ReservationCommand c,String code) {
        reservations.create(c.reservationNo(),c.orderNo(),c.quoteNo(),c.requestHash(),"REJECTED","UNDECIDED",c.payDeadline(),code);
        var head=binding(reservations.lock(c.reservationNo()),c.orderNo());
        if(!"RESERVED".equals(head.status())) { if(!"RELEASED".equals(head.status()))sameRequest(head,c);return view(head); }
        sameRequest(head,c);
        if(reservations.items(c.reservationNo()).isEmpty()) reservations.rejected(c.reservationNo(),code);
        return view(reservations.lock(c.reservationNo()));
    }
    /** 释放可先于预占到达；墓碑和已预占余额释放同事务，重复释放不加第二次库存。 */
    public Reservation release(String no,ReleaseCommand command) {
        if(command==null || !valid(no,"res_") || !valid(command.orderNo(),"ord_") || !"RELEASE".equals(command.decision())) throw invalid();
        return retry(()->{
            reservations.create(no,command.orderNo(),null,null,"RELEASED","RELEASE",null,null);
            var head=binding(reservations.lock(no),command.orderNo());
            if("RELEASED".equals(head.status())) return view(head);
            if("CONFIRMED".equals(head.status()) || "COMMIT".equals(head.decision()))
                throw new BusinessException(409,"STOCK_ALREADY_COMMITTED","已确认库存不能通过预占释放回补");
            if("RESERVED".equals(head.status())) for(var item:reservations.items(no)) {
                var before=products.lockInventory("warehouse_main",item.skuId());
                if(before==null || reservations.release(item.skuId(),item.quantity())!=1)
                    throw new BusinessException(409,"INVENTORY_INVARIANT_BROKEN","预占余额与明细矛盾，需人工核对");
                reservations.ledger(no,"RELEASE",item.skuId(),item.quantity(),-item.quantity(),Math.addExact(before.availableQty(),item.quantity()),before.reservedQty()-item.quantity());
            }
            reservations.released(no);return view(reservations.lock(no));
        });
    }
    /** 查无记录仅说明目前未见，不证明迟到请求不会执行；调用方仍须创建释放墓碑。 */
    public Reservation query(String no) {
        if(!valid(no,"res_"))throw invalid();var head=reservations.find(no);
        if(head==null)throw new BusinessException(404,"RESERVATION_NOT_FOUND","预占记录尚不存在");return view(head);
    }
    /** 支付已被订单接受后的幂等COMMIT；头、预占扣减和CONFIRM流水同事务，重复不重扣。 */
    public Reservation confirm(String no,ReleaseCommand command) {
        if(command==null || !valid(no,"res_") || !valid(command.orderNo(),"ord_") || !"COMMIT".equals(command.decision()))throw invalid();
        return retry(()->{
            var head=binding(reservations.lock(no),command.orderNo());
            if("CONFIRMED".equals(head.status()))return view(head);
            if(!"RESERVED".equals(head.status()) || !"UNDECIDED".equals(head.decision()))throw new BusinessException(409,"STOCK_ALREADY_RELEASED","已终止预占不能确认");
            var items=reservations.items(no);if(items.isEmpty())throw new BusinessException(409,"INVENTORY_INVARIANT_BROKEN","预占明细缺失");
            for(var item:items) {
                var before=products.lockInventory("warehouse_main",item.skuId());
                if(before==null || reservations.confirmBalance(item.skuId(),item.quantity())!=1)throw new BusinessException(409,"INVENTORY_INVARIANT_BROKEN","预占余额不符");
                reservations.ledger(no,"CONFIRM",item.skuId(),0,-item.quantity(),before.availableQty(),before.reservedQty()-item.quantity());
            }
            if(reservations.confirmed(no)!=1)throw new BusinessException(409,"STOCK_ALREADY_RELEASED","库存决策竞争失败");
            return view(reservations.lock(no));
        });
    }
    /** 同号绑定不可变；orderNo唯一键冲突不得偷偷更换业务编号重试。 */
    private ReservationMapper.Header binding(ReservationMapper.Header head,String order) {
        if(head==null || !order.equals(head.orderNo()))throw new BusinessException(409,"RESERVATION_BINDING_CONFLICT","预占编号与订单绑定冲突");return head;
    }
    /** 已有预占同号异参必须拒绝；释放墓碑不需要原报价也能阻止迟到预占。 */
    private void sameRequest(ReservationMapper.Header head,ReservationCommand c) {
        if(!c.requestHash().equals(head.requestHash()) || !c.quoteNo().equals(head.quoteNo()))
            throw new BusinessException(409,"IDEMPOTENCY_CONFLICT","预占同号参数不一致");
    }
    private Reservation view(ReservationMapper.Header h) { return new Reservation(h.reservationNo(),h.orderNo(),h.status(),h.requestHash(),h.rejectReason()); }
    /** 远端输入不因服务身份而跳过范围校验，固定格式指纹杜绝伪造相同摘要改变数量。 */
    private void validate(ReservationCommand c) {
        if(c==null || !valid(c.reservationNo(),"res_") || !valid(c.orderNo(),"ord_") || !valid(c.quoteNo(),"quo_")
                || c.userId()==null || !c.userId().matches("[1-9][0-9]{0,18}") || c.payDeadline()==null
                || c.items()==null || c.items().isEmpty() || c.items().size()>20 || c.items().stream().anyMatch(i->i==null || i.skuId()==null || !i.skuId().matches("[1-9][0-9]{0,18}") || i.quantity()<1 || i.quantity()>99)
                || c.items().stream().map(ReservationLine::skuId).distinct().count()!=c.items().size())throw invalid();
        if(!TradingContracts.reservationHash(c).equals(c.requestHash())) throw new BusinessException(400,"INVALID_REQUEST_HASH","预占请求摘要不正确");
    }
    private boolean valid(String value,String prefix) { return value!=null && value.matches(prefix+"[a-f0-9]{32}"); }
    private List<ReservationLine> sorted(List<ReservationLine> items) { return items.stream().sorted(Comparator.comparing(i->new java.math.BigInteger(i.skuId()))).toList(); }
    private BusinessException invalid() { return new BusinessException(400,"VALIDATION_ERROR","库存协议参数不合法"); }
    /** 死锁/锁超时整单回滚后新事务最多3次；超限交回订单恢复，不在锁内等待网络。 */
    private <T> T retry(Supplier<T> work) {
        for(int attempt=0;attempt<3;attempt++)try { return tx.execute(s->work.get()); }
        catch(org.springframework.dao.TransientDataAccessException failure) {
            if(attempt==2)throw new BusinessException(503,"DATABASE_BUSY","库存数据库竞争，请查询原预占号");
            try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(20,81)); }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new BusinessException(503,"REQUEST_INTERRUPTED","请查询原预占号"); }
        }
        throw new IllegalStateException("不可达分支");
    }
}
