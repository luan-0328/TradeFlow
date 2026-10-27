package com.tradeflow.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.contracts.TradingContracts.*;
import com.tradeflow.messaging.MessageOutbox;
import com.tradeflow.order.api.OrderModels.*;
import com.tradeflow.order.domain.OrderRows.*;
import com.tradeflow.order.infrastructure.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 升级用例编排回归；Mock只证明事务边界和参数，不能替代真实MySQL回滚、并发及跨服务验收。 */
class UpgradeWorkflowTest {
    private final Jwt jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("7").claim("token_version",0L).build();
    private PlaceOrder input(String merchant){return new PlaceOrder(List.of(new com.tradeflow.order.api.OrderModels.Line("1",1,100L)),new Address("用户","123","区域","地址"),merchant,0L);}
    private PlatformTransactionManager manager(){var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());return manager;}
    /** 同键异参在任何子单受理之前失败，并回滚组请求事务。 */
    @Test void groupParameterConflictNeverAcceptsChildren() {
        var upgrade=mock(OrderUpgradeMapper.class);var orders=mock(OrderMapper.class);var child=mock(OrderService.class);var authority=mock(OrderAuthority.class);var manager=manager();
        when(child.normalize(any())).thenAnswer(i->i.getArgument(0));
        when(upgrade.groupByKey("7","same-key")).thenReturn(Map.of("group_no","grp_old","request_hash","different"));
        var service=new TradeGroupService(upgrade,orders,child,authority,new OrderTransactions(manager),new ObjectMapper());
        assertThatThrownBy(()->service.create(jwt,"same-key",new TradeGroupService.Create(List.of(input("merchant_a"))),"req","trace"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        verify(child,never()).acceptLocked(any(),anyString(),any(),anyString(),anyString());verify(upgrade,never()).child(anyString(),anyString(),anyString());
        verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    /** 两商家子意图与组绑定都位于同一次写事务；第二子单失败不会提交第一子单意图。 */
    @Test void groupChildrenShareSingleTransactionAndSecondFailureRollsBack() {
        var upgrade=mock(OrderUpgradeMapper.class);var orders=mock(OrderMapper.class);var child=mock(OrderService.class);var authority=mock(OrderAuthority.class);var manager=manager();
        when(child.normalize(any())).thenAnswer(i->i.getArgument(0));AtomicReference<Map<String,Object>> claimed=new AtomicReference<>();
        when(upgrade.groupClaim(anyString(),eq("7"),eq("group-key"),anyString())).thenAnswer(i->{claimed.set(Map.of("group_no",i.getArgument(0),"request_hash",i.getArgument(3)));return 1;});
        when(upgrade.groupByKey("7","group-key")).thenAnswer(i->claimed.get());
        when(child.acceptLocked(eq(jwt),anyString(),any(),eq("req"),eq("trace"))).thenAnswer(i->{PlaceOrder request=i.getArgument(2);if(request.effectiveMerchant().equals("merchant_b"))throw new BusinessException(409,"SHIPPING_CHANGED","运费变化");return new Accepted("ord_first","QUOTING","/progress",List.of());});
        var service=new TradeGroupService(upgrade,orders,child,authority,new OrderTransactions(manager),new ObjectMapper());
        assertThatThrownBy(()->service.create(jwt,"group-key",new TradeGroupService.Create(List.of(input("merchant_b"),input("merchant_a"))),"req","trace"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("SHIPPING_CHANGED"));
        verify(upgrade).child(anyString(),eq("merchant_a"),eq("ord_first"));verify(upgrade,never()).child(anyString(),eq("merchant_b"),anyString());
        verify(manager,times(1)).getTransaction(any());verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    /** 商家操作员不能写运费；管理员只能写最新身份里已选商家的规则。 */
    @Test void shippingWriteRequiresAdminAndUsesAuthorizedMerchant() {
        var upgrade=mock(OrderUpgradeMapper.class);var authority=mock(OrderAuthority.class);var manager=manager();var orders=mock(OrderMapper.class);
        // Mockito对Map默认返回空Map；不存在的数据库记录必须显式模拟null，避免误走已提交幂等分支。
        when(upgrade.shippingCommand(anyString(),anyString())).thenReturn(null);
        var service=new TradeGroupService(upgrade,orders,mock(OrderService.class),authority,new OrderTransactions(manager),new ObjectMapper());
        when(authority.merchant(jwt)).thenReturn(new AuthContext("7","ACTIVE",List.of("MERCHANT_OPERATOR"),List.of("merchant_b"),0,java.util.Map.of("merchant_b","OPERATOR")));
        assertThatThrownBy(()->service.updateShipping(jwt,new TradeGroupService.ShippingRule(20,1),"req","trace")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(upgrade);verify(manager,never()).getTransaction(any());
        when(authority.merchant(jwt)).thenReturn(new AuthContext("7","ACTIVE",List.of("MERCHANT_ADMIN"),List.of("merchant_b"),0,java.util.Map.of("merchant_b","ADMIN")));
        when(upgrade.shippingUpdate("merchant_b",1,20)).thenReturn(1);when(upgrade.shipping("merchant_b")).thenReturn(Map.of("fee_cents",20L,"version",2L));
        assertThat(service.updateShipping(jwt,new TradeGroupService.ShippingRule(20,1),"req","trace")).containsEntry("merchantId","merchant_b");
        verify(authority).requireTrading("merchant_b");verify(upgrade,never()).shippingUpdate(eq("merchant_a"),anyLong(),anyLong());
    }
    /** 真实订单受理核验持久规则，运费变化不能留下创建意图和任务。 */
    @Test void changedShippingRejectsBeforeIntentAndTask() {
        var orders=mock(OrderMapper.class);var upgrade=mock(OrderUpgradeMapper.class);var authority=mock(OrderAuthority.class);var manager=manager();var json=new ObjectMapper();
        var service=new OrderService(orders,mock(TaskMapper.class),authority,new OrderTransactions(manager),json,mock(FulfillmentMapper.class),mock(EntitlementMapper.class));
        ReflectionTestUtils.setField(service,"upgrade",upgrade);
        when(orders.claimRequest(eq("7"),eq("key"),anyString(),anyString())).thenAnswer(i->{when(orders.lockRequest("7","key")).thenReturn(new RequestRow(i.getArgument(2),i.getArgument(3),"PROCESSING"));return 1;});
        when(upgrade.lockShipping("merchant_a")).thenReturn(Map.of("fee_cents",25L,"version",1L));
        assertThatThrownBy(()->service.place(jwt,"key",input("merchant_a"),"req","trace")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("SHIPPING_CHANGED"));
        verify(orders,never()).intent(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());verify(orders,never()).task(anyString(),anyString());verify(manager).rollback(any());
    }
    /** 已发布版本仅含items/address摘要：默认商家零运费原键可恢复，但不能借兼容逻辑换商家。 */
    @Test void historicalDefaultMerchantRequestReplaysWithoutNewChargeButCannotChangeMerchant() throws Exception {
        var orders=mock(OrderMapper.class);var upgrade=mock(OrderUpgradeMapper.class);var manager=manager();var json=new ObjectMapper();
        var service=new OrderService(orders,mock(TaskMapper.class),mock(OrderAuthority.class),new OrderTransactions(manager),json,mock(FulfillmentMapper.class),mock(EntitlementMapper.class));ReflectionTestUtils.setField(service,"upgrade",upgrade);
        var body=new PlaceOrder(input("merchant_demo").items(),input("merchant_demo").address());Map<String,Object> legacy=new LinkedHashMap<>();legacy.put("items",body.items());legacy.put("address",body.address());
        String hash=com.tradeflow.contracts.TradingContracts.sha256(json.writeValueAsString(legacy));
        when(orders.lockRequest("7","old-key")).thenReturn(new RequestRow(hash,"ord_old","PROCESSING"));
        when(orders.intentByNo("ord_old")).thenReturn(new Intent("ord_old","7","pay_old","res_old",hash,"legacy_payload","QUOTING",null,Instant.now()));
        assertThat(service.place(jwt,"old-key",body,"req","trace").businessNo()).isEqualTo("ord_old");
        assertThatThrownBy(()->service.place(jwt,"old-key",input("merchant_b"),"req","trace"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        verifyNoInteractions(upgrade);verify(orders,never()).intent(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }
    /** 当前商家选择头只缩窄最新成员关系；伪造商家拒绝，多成员无头不能猜第一家。 */
    @Test void merchantHeaderIsVerifiedAgainstMembershipAndSingleMembershipKeepsLegacyPath(){
        var context=new AuthContext("7","ACTIVE",List.of("MERCHANT_ADMIN"),List.of("merchant_a","merchant_b"),0,java.util.Map.of("merchant_a","ADMIN","merchant_b","ADMIN"));
        var request=new MockHttpServletRequest();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThatThrownBy(()->com.tradeflow.web.MerchantSelection.select(context)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("MERCHANT_SELECTION_REQUIRED"));
            request.addHeader("X-Merchant-Id","merchant_b");assertThat(com.tradeflow.web.MerchantSelection.narrow(context).merchantIds()).containsExactly("merchant_b");
            request.removeHeader("X-Merchant-Id");request.addHeader("X-Merchant-Id","merchant_foreign");
            assertThatThrownBy(()->com.tradeflow.web.MerchantSelection.select(context)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("MERCHANT_FORBIDDEN"));
            request.removeHeader("X-Merchant-Id");assertThat(com.tradeflow.web.MerchantSelection.select(new AuthContext("7","ACTIVE",List.of("MERCHANT_ADMIN"),List.of("merchant_demo"),0,java.util.Map.of("merchant_demo","ADMIN")))).isEqualTo("merchant_demo");
        }finally{RequestContextHolder.resetRequestAttributes();}
    }
    /** 通过真实恢复器捕获购买件实付：2分运费分给3件，SKU数值顺序尾差前置，总额守恒。 */
    @Test void quoteRecoveryAllocatesShippingToStablePurchaseUnits() throws Exception {
        var orders=mock(OrderMapper.class);var tasks=mock(TaskMapper.class);var upgrade=mock(OrderUpgradeMapper.class);var inventory=mock(InventoryGateway.class);var manager=manager();var json=new ObjectMapper();
        String no="ord_"+"a".repeat(32);Instant now=Instant.now();var task=new Task("tsk",no,"RUNNING",1,1,"worker",now.plusSeconds(30),now);
        var input=new PlaceOrder(List.of(new com.tradeflow.order.api.OrderModels.Line("1",2,100L),new com.tradeflow.order.api.OrderModels.Line("2",1,100L)),new Address("用户","123","区域","地址"),"merchant_a",2L);
        var intent=new Intent(no,"7","pay","res","hash",json.writeValueAsString(input),"QUOTING",null,now);
        when(tasks.next()).thenReturn(task,null);when(tasks.byNo("tsk")).thenReturn(task);when(tasks.fence("tsk","worker",1)).thenReturn(task);when(tasks.retry(eq("tsk"),eq("worker"),eq(1L),anyInt(),anyString())).thenReturn(1);
        when(orders.intentByNo(no)).thenReturn(intent);when(orders.lockIntent(no)).thenReturn(intent);when(upgrade.chargeByOrder(no)).thenReturn(Map.of("shipping_cents",2L));
        when(inventory.quote(any())).thenReturn(new Quote("quo_"+"b".repeat(32),300,now.plusSeconds(300),List.of(new QuotedItem("2",1,100,"2","商品二","规格",1),new QuotedItem("1",2,100,"1","商品一","规格",1))));
        Map<String,Long> paid=new LinkedHashMap<>();AtomicReference<Long> total=new AtomicReference<>();
        when(orders.createOrder(anyMap())).thenAnswer(i->{Map<String,Object> row=i.getArgument(0);total.set(((Number)row.get("totalAmountCents")).longValue());return 1;});
        when(orders.item(anyMap())).thenAnswer(i->{Map<String,Object> row=i.getArgument(0);row.put("id",row.get("skuId"));return 1;});
        when(orders.units(anyString(),anyLong(),anyList())).thenAnswer(i->{List<Integer> units=i.getArgument(2);for(int unit:units)paid.put(i.getArgument(0)+":"+unit,i.getArgument(1));return 1;});
        var worker=new CreateOrderWorker(orders,tasks,new OrderTransactions(manager),new OrderTaskClaimService(tasks),inventory,mock(PaymentGateway.class),json,mock(MessageOutbox.class));ReflectionTestUtils.setField(worker,"upgrade",upgrade);worker.scan();
        assertThat(total.get()).isEqualTo(302L);assertThat(paid).containsEntry("1:1",101L).containsEntry("1:2",101L).containsEntry("2:1",100L);assertThat(paid.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(total.get());
        verify(tasks).retry("tsk","worker",1,0,"STOCK_PENDING");verify(inventory,never()).reserve(any());
    }
    /** 运费令总额超过单笔上限时明确拒绝原意图并完成任务，不创建订单或反复重试已知拒绝。 */
    @Test void shippingAmountLimitRejectsOriginalIntentAndCompletesTask() throws Exception {
        var orders=mock(OrderMapper.class);var tasks=mock(TaskMapper.class);var upgrade=mock(OrderUpgradeMapper.class);var inventory=mock(InventoryGateway.class);var manager=manager();var json=new ObjectMapper();
        String no="ord_"+"c".repeat(32);Instant now=Instant.now();var task=new Task("tsk_limit",no,"RUNNING",1,2,"worker",now.plusSeconds(30),now);
        var input=new PlaceOrder(List.of(new com.tradeflow.order.api.OrderModels.Line("1",1,100000000L)),new Address("用户","123","区域","地址"),"merchant_a",1L);
        var intent=new Intent(no,"7","pay","res","hash",json.writeValueAsString(input),"QUOTING",null,now);
        when(tasks.next()).thenReturn(task,null);when(tasks.byNo("tsk_limit")).thenReturn(task);when(tasks.fence("tsk_limit","worker",2)).thenReturn(task);when(tasks.finish("tsk_limit","worker",2)).thenReturn(1);
        when(orders.intentByNo(no)).thenReturn(intent);when(orders.lockIntent(no)).thenReturn(intent);when(upgrade.chargeByOrder(no)).thenReturn(Map.of("shipping_cents",1L));
        when(inventory.quote(any())).thenReturn(new Quote("quo_"+"d".repeat(32),100000000L,now.plusSeconds(300),List.of(new QuotedItem("1",1,100000000L,"1","商品","规格",1))));
        var worker=new CreateOrderWorker(orders,tasks,new OrderTransactions(manager),new OrderTaskClaimService(tasks),inventory,mock(PaymentGateway.class),json,mock(MessageOutbox.class));ReflectionTestUtils.setField(worker,"upgrade",upgrade);worker.scan();
        var ordered=inOrder(orders,tasks);ordered.verify(orders).intentState(no,"REJECTED","ORDER_AMOUNT_LIMIT");ordered.verify(orders).requestResult(no,"REJECTED","ORDER_AMOUNT_LIMIT");ordered.verify(tasks).finish("tsk_limit","worker",2);
        verify(orders,never()).createOrder(anyMap());verify(orders,never()).item(anyMap());verify(orders,never()).units(anyString(),anyLong(),anyList());
        verify(tasks,never()).retry(anyString(),anyString(),anyLong(),anyInt(),anyString());verify(inventory,never()).reserve(any());
    }
    /** 商家停用不阻断原键事实恢复；授权仍有效时直接返回原金额/版本，不再发经营状态RPC或新写规则。 */
    @Test void committedShippingCommandReplaysEvenWhenMerchantNowDisabled() {
        var upgrade=mock(OrderUpgradeMapper.class);var authority=mock(OrderAuthority.class);var manager=manager();var orders=mock(OrderMapper.class);
        when(authority.merchant(jwt)).thenReturn(new AuthContext("7","ACTIVE",List.of("MERCHANT_ADMIN"),List.of("merchant_b"),0,java.util.Map.of("merchant_b","ADMIN")));
        String hash=com.tradeflow.contracts.TradingContracts.sha256("merchant_b:4:25");
        when(upgrade.shippingCommand("7","original-key")).thenReturn(Map.of("request_hash",hash,"merchant_id","merchant_b","fee_cents",25L,"rule_version",5L));
        doThrow(new BusinessException(409,"MERCHANT_DISABLED","商家已停用")).when(authority).requireTrading("merchant_b");
        var service=new TradeGroupService(upgrade,orders,mock(OrderService.class),authority,new OrderTransactions(manager),new ObjectMapper());
        assertThat(service.updateShipping(jwt,new TradeGroupService.ShippingRule(25,4),"original-key","req","trace"))
                .containsEntry("merchantId","merchant_b").containsEntry("feeCents",25L).containsEntry("version",5L);
        verify(authority,never()).requireTrading(anyString());verify(manager,never()).getTransaction(any());
        verify(upgrade,never()).shippingUpdate(anyString(),anyLong(),anyLong());verify(upgrade,never()).saveShippingCommand(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        verifyNoInteractions(orders);
    }
}
