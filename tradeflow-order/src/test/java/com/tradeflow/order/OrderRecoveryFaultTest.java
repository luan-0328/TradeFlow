package com.tradeflow.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.contracts.TradingContracts;
import com.tradeflow.contracts.TradingContracts.*;
import com.tradeflow.messaging.MessageOutbox;
import com.tradeflow.order.application.*;
import com.tradeflow.order.domain.OrderRows.*;
import com.tradeflow.order.infrastructure.*;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实订单恢复器的故障编排回归；Mock 不证明 MySQL 事务、库存约束或真实 JVM 重启。 */
class OrderRecoveryFaultTest {
    private final OrderMapper orders=mock(OrderMapper.class);
    private final TaskMapper tasks=mock(TaskMapper.class);
    private final InventoryGateway inventory=mock(InventoryGateway.class);
    private final OrderTransactions tx=mock(OrderTransactions.class);
    private final String no="ord_"+"a".repeat(32), reservation="res_"+"b".repeat(32);
    private final Task task=new Task("task_1",no,"RUNNING",1,7,"worker_old",Instant.now().plusSeconds(30),Instant.now());
    private CreateOrderWorker worker;

    @BeforeEach void setup() {
        when(tx.write(any())).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());
        worker=new CreateOrderWorker(orders,tasks,tx,new OrderTaskClaimService(tasks),inventory,mock(PaymentGateway.class),new ObjectMapper(),mock(MessageOutbox.class));
        when(tasks.next()).thenReturn(task,null);when(tasks.byNo(task.taskNo())).thenReturn(task);
        when(tasks.fence(task.taskNo(),task.leaseOwner(),task.claimVersion())).thenReturn(task);
        when(tasks.finish(task.taskNo(),task.leaseOwner(),task.claimVersion())).thenReturn(1);
        Intent intent=new Intent(no,"1","pay_1",reservation,"hash",null,"ORDER_CREATED",null,Instant.now());
        Header header=new Header(no,"1","merchant_demo","pay_1",reservation,"quote_1",100,"CNY","CREATING","NONE","PENDING","NONE",Instant.now().plusSeconds(600),Instant.now(),"NONE");
        when(orders.intentByNo(no)).thenReturn(intent);when(orders.lockIntent(no)).thenReturn(intent);
        when(orders.order(no)).thenReturn(header);when(orders.lockOrder(no)).thenReturn(header);
        when(orders.items(no)).thenReturn(List.of(new Item("1","sku_1","商品","规格",100,1)));
        var unsigned=new ReservationCommand(reservation,no,"quote_1","1",null,header.payDeadline(),List.of(new ReservationLine("sku_1",1)));
        when(inventory.query(reservation)).thenReturn(new Reservation(reservation,no,"RESERVED",TradingContracts.reservationHash(unsigned),null));
        when(orders.pending(no)).thenReturn(1);
    }

    /** 库存已提交、订单尚停在 CREATING：查原预占推进订单，禁止二次 reserve 或 release。 */
    @Test void committedReservationWithoutLocalRecordRecoversByOriginalNumber() {
        worker.scan();
        verify(inventory).query(reservation);verify(inventory,never()).reserve(any());verify(inventory,never()).release(anyString(),anyString());
        verify(orders).pending(no);verify(orders).requestResult(no,"SUCCEEDED","ORDER_READY");
        verify(tasks).finish(task.taskNo(),task.leaseOwner(),task.claimVersion());
    }

    /** 查询完成前租约失效或被新 owner 接管：数据库 fence 拒绝后必须放弃所有订单回写。 */
    @Test void staleLeaseCannotApplyReservationResult() {
        when(tasks.fence(task.taskNo(),task.leaseOwner(),task.claimVersion())).thenReturn(null);
        worker.scan();
        verify(inventory).query(reservation);verify(orders,never()).lockOrder(anyString());verify(orders,never()).pending(anyString());
        verify(orders,never()).requestResult(anyString(),anyString(),anyString());
        verify(tasks,never()).finish(anyString(),anyString(),anyLong());
    }
}
