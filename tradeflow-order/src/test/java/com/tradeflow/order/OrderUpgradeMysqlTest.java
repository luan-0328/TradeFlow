package com.tradeflow.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.contracts.TradingContracts;
import com.tradeflow.order.api.OrderModels.*;
import com.tradeflow.order.application.*;
import com.tradeflow.order.infrastructure.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅upgrade-mysql.py全新订单库：真实交易组/订单Service、XML和Spring事务；只模拟授权/库存报价/支付RPC边界。 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_RETURN_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/trade_product.*")
class OrderUpgradeMysqlTest {
    private JdbcTemplate jdbc;private OrderMapper orders;private TaskMapper tasks;private OrderUpgradeMapper upgrade;
    private OrderService service;private TradeGroupService groups;private OrderTransactions tx;private OrderAuthority authority;private ObjectMapper json;
    private String buyer,merchantA,merchantB,unique;private Jwt jwt;
    @BeforeEach void setup()throws Exception {
        unique=UUID.randomUUID().toString().replace("-","");buyer=Long.toString(ThreadLocalRandom.current().nextLong(10000000,99999999));
        merchantA="merchant_ua"+unique.substring(0,12);merchantB="merchant_ub"+unique.substring(0,12);
        jwt=Jwt.withTokenValue("fixture").header("alg","RS256").subject(buyer).build();
        String url=System.getenv("TRADEFLOW_RETURN_MYSQL_URL").replace("/trade_product","/trade_order");
        var data=new DriverManagerDataSource(url,"root","");jdbc=new JdbcTemplate(data);tx=new OrderTransactions(new DataSourceTransactionManager(data));
        var config=new org.apache.ibatis.session.Configuration(new Environment("order-upgrade",new SpringManagedTransactionFactory(),data));
        config.setMapUnderscoreToCamelCase(true);config.setArgNameBasedConstructorAutoMapping(true);
        for(Class<?> type:List.of(OrderMapper.class,TaskMapper.class,OrderUpgradeMapper.class,FulfillmentMapper.class,EntitlementMapper.class)){
            String resource="mapper/order/"+type.getSimpleName()+".xml";
            try(var input=Resources.getResourceAsStream(resource)){new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();}
        }
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));orders=session.getMapper(OrderMapper.class);tasks=session.getMapper(TaskMapper.class);upgrade=session.getMapper(OrderUpgradeMapper.class);
        authority=mock(OrderAuthority.class);json=new ObjectMapper();
        when(authority.authenticate(any(),anyBoolean())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return identity();});
        when(authority.merchant(any())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return identity();});
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return null;}).when(authority).requireTrading(anyString());
        service=new OrderService(orders,tasks,authority,tx,json,session.getMapper(FulfillmentMapper.class),session.getMapper(EntitlementMapper.class));
        ReflectionTestUtils.setField(service,"upgrade",upgrade);
        groups=new TradeGroupService(upgrade,orders,service,authority,tx,json);
        jdbc.update("INSERT INTO merchant_shipping_rule(merchant_id,fee_cents,version) VALUES(?,0,1),(?,0,1)",merchantA,merchantB);
    }
    private AuthContext identity(){return new AuthContext(buyer,"ACTIVE",List.of("USER","MERCHANT_ADMIN"),List.of(merchantA),1,java.util.Map.of(merchantA,"ADMIN"));}
    private Address address(){return new Address("隔离买家","10086","隔离地区","隔离地址");}
    private PlaceOrder child(String merchant,long shipping){return new PlaceOrder(List.of(new Line("101",2,100L)),address(),merchant,shipping);}
    private TradeGroupService.Create group(){return new TradeGroupService.Create(List.of(child(merchantB,0),child(merchantA,0)));}
    private long count(String sql,Object... arguments){return jdbc.queryForObject(sql,Long.class,arguments);}
    /** 并发原键经同一数据库唯一行串行化，两个返回同组号，不能出现第二套子意图/任务/运费快照。 */
    @Test void concurrentOriginalGroupKeyCommitsOneSetOfChildrenAndIntents()throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);String key="group-"+unique;
        try{
            var first=pool.submit(()->{start.await();return groups.create(jwt,key,group(),"request","trace");});
            var second=pool.submit(()->{start.await();return groups.create(jwt,key,new TradeGroupService.Create(List.of(child(merchantA,0),child(merchantB,0))),"request","trace");});
            start.countDown();var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);
            assertThat(a.groupNo()).isEqualTo(b.groupNo());assertThat(a.children()).hasSize(2);
            assertThat(count("SELECT COUNT(*) FROM trade_group WHERE user_id=?",buyer)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM trade_group_child WHERE group_no=?",a.groupNo())).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM order_creation_intent WHERE user_id=?",buyer)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM process_task t JOIN order_creation_intent i ON i.order_no=t.business_no WHERE i.user_id=?",buyer)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM order_charge_snapshot s JOIN order_creation_intent i ON i.order_no=s.order_no WHERE i.user_id=?",buyer)).isEqualTo(2);
            assertThat(groups.lookup(jwt,key).groupNo()).isEqualTo(a.groupNo());
        }finally{pool.shutdownNow();}
    }
    /** 组摘要包括地址/商品/商家/运费；同键异参不能添加或覆盖子订单。 */
    @Test void changedGroupInputIsRejectedWithoutChangingOriginalChildren(){
        String key="changed-"+unique;var original=groups.create(jwt,key,group(),"request","trace");
        var changed=new TradeGroupService.Create(List.of(child(merchantA,0),new PlaceOrder(List.of(new Line("101",3,100L)),address(),merchantB,0L)));
        assertThatThrownBy(()->groups.create(jwt,key,changed,"request","trace")).isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(groups.lookup(jwt,key).children()).isEqualTo(original.children());assertThat(count("SELECT COUNT(*) FROM order_creation_intent WHERE user_id=?",buyer)).isEqualTo(2);
    }
    /** 排序后的第二商家运费不匹配时，第一子单已写内容、组头、幂等键、任务、审计全部回滚，原键随后可恢复。 */
    @Test void secondChildShippingMismatchRollsBackEntireGroupAndFirstIntent(){
        String key="rollback-"+unique;jdbc.update("UPDATE merchant_shipping_rule SET fee_cents=50 WHERE merchant_id=?",merchantB);
        assertThatThrownBy(()->groups.create(jwt,key,group(),"request","trace")).isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code()).isEqualTo("SHIPPING_CHANGED"));
        assertThat(groups.lookup(jwt,key)).isNull();assertThat(count("SELECT COUNT(*) FROM trade_group WHERE user_id=?",buyer)).isZero();
        assertThat(count("SELECT COUNT(*) FROM order_creation_intent WHERE user_id=?",buyer)).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE actor_id=?",buyer)).isZero();
        assertThat(count("SELECT COUNT(*) FROM order_charge_snapshot WHERE merchant_id IN (?,?)",merchantA,merchantB)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE actor_id=?",buyer)).isZero();
        jdbc.update("UPDATE merchant_shipping_rule SET fee_cents=0 WHERE merchant_id=?",merchantB);
        assertThat(groups.create(jwt,key,group(),"request","trace").children()).hasSize(2);
    }
    /** 运费修改原键返回旧结果而不重复增版本，订单受理快照不随之后的规则修改变化。 */
    @Test void shippingCommandReplaysOriginalSnapshotAndAcceptedOrderKeepsItsCharge(){
        var rule=new TradeGroupService.ShippingRule(17,1);String key="shipping-"+unique;
        var first=groups.updateShipping(jwt,rule,key,"request","trace");assertThat(((Number)first.get("version")).longValue()).isEqualTo(2);
        var accepted=service.place(jwt,"place-"+unique,child(merchantA,17),"request","trace");
        groups.updateShipping(jwt,new TradeGroupService.ShippingRule(29,2),"shipping-next-"+unique,"request","trace");
        var replay=groups.updateShipping(jwt,rule,key,"request","trace");assertThat(((Number)replay.get("feeCents")).longValue()).isEqualTo(17);assertThat(((Number)replay.get("version")).longValue()).isEqualTo(2);
        assertThat(((Number)upgrade.shipping(merchantA).get("version")).longValue()).isEqualTo(3);
        assertThat(((Number)upgrade.chargeByOrder(accepted.businessNo()).get("shipping_cents")).longValue()).isEqualTo(17);
        assertThat(service.place(jwt,"place-"+unique,child(merchantA,17),"request","trace").businessNo()).isEqualTo(accepted.businessNo());
        assertThatThrownBy(()->groups.updateShipping(jwt,new TradeGroupService.ShippingRule(18,1),key,"request","trace")).isInstanceOf(BusinessException.class);
    }
    /** 真Worker在事务外报价、本库栅栏内建订单和购买件；整数运费尾差逐件守恒且旧无运费快照订单按零兼容。 */
    @Test void workerPersistsUnitShippingConservationAndSupportsLegacyMissingCharge()throws Exception {
        jdbc.update("UPDATE merchant_shipping_rule SET fee_cents=5 WHERE merchant_id=?",merchantA);
        var request=new PlaceOrder(List.of(new Line("102",1,200L),new Line("101",2,100L)),address(),merchantA,5L);
        var accepted=service.place(jwt,"worker-"+unique,request,"request","trace");
        var inventory=mock(InventoryGateway.class);var payments=mock(PaymentGateway.class);
        when(inventory.quote(any())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();TradingContracts.QuoteCommand command=call.getArgument(0);
            var items=command.items().stream().map(line->new TradingContracts.QuotedItem(line.skuId(),line.quantity(),line.expectedUnitPriceCents(),"1","商品快照","规格快照",0)).toList();
            long amount=items.stream().mapToLong(item->item.expectedUnitPriceCents()*item.quantity()).sum();return new TradingContracts.Quote("quo_"+UUID.randomUUID().toString().replace("-",""),amount,Instant.now().plusSeconds(300),items);});
        var worker=new CreateOrderWorker(orders,tasks,tx,new OrderTaskClaimService(tasks),inventory,payments,json,mock(com.tradeflow.messaging.MessageOutbox.class));ReflectionTestUtils.setField(worker,"upgrade",upgrade);
        executeOwnTask(worker,accepted.businessNo());
        assertThat(orders.order(accepted.businessNo()).totalAmountCents()).isEqualTo(405);
        assertThat(count("SELECT SUM(line_amount_cents) FROM order_item WHERE order_no=?",accepted.businessNo())).isEqualTo(405);
        assertThat(count("SELECT SUM(u.paid_amount_cents) FROM order_item_unit u JOIN order_item i ON i.id=u.order_item_id WHERE i.order_no=?",accepted.businessNo())).isEqualTo(405);
        assertThat(jdbc.queryForList("SELECT u.paid_amount_cents FROM order_item_unit u JOIN order_item i ON i.id=u.order_item_id WHERE i.order_no=? ORDER BY i.sku_id,u.unit_no",Long.class,accepted.businessNo())).containsExactly(102L,102L,201L);
        assertThat(count("SELECT COUNT(*) FROM order_address WHERE order_no=?",accepted.businessNo())).isEqualTo(1);
        // 模拟V7既有QUOTING意图：旧请求无merchantId/expectedShipping字段，且没有V8运费快照。
        var legacy=service.place(jwt,"legacy-worker-"+unique,new PlaceOrder(List.of(new Line("103",1,100L)),address()),"request","trace");
        jdbc.update("DELETE FROM order_charge_snapshot WHERE order_no=?",legacy.businessNo());
        var oldPayload=new LinkedHashMap<String,Object>();oldPayload.put("items",List.of(new Line("103",1,100L)));oldPayload.put("address",address());String encoded=json.writeValueAsString(oldPayload),hash=TradingContracts.sha256(encoded);
        jdbc.update("UPDATE order_creation_intent SET request_payload=?,request_hash=? WHERE order_no=?",encoded,hash,legacy.businessNo());jdbc.update("UPDATE idempotency_request SET request_hash=? WHERE business_no=?",hash,legacy.businessNo());
        assertThat(service.place(jwt,"legacy-worker-"+unique,new PlaceOrder(List.of(new Line("103",1,100L)),address()),"request","trace").businessNo()).isEqualTo(legacy.businessNo());
        executeOwnTask(worker,legacy.businessNo());assertThat(orders.order(legacy.businessNo()).merchantId()).isEqualTo("merchant_demo");assertThat(orders.order(legacy.businessNo()).totalAmountCents()).isEqualTo(100);
        assertThat(count("SELECT SUM(u.paid_amount_cents) FROM order_item_unit u JOIN order_item i ON i.id=u.order_item_id WHERE i.order_no=?",legacy.businessNo())).isEqualTo(100);verifyNoInteractions(payments);
    }
    /** 注解领取经真实Spring代理和MySQL提交，调用方返回后可从独立连接看到执行者及新围栏。 */
    @Test void annotatedTaskClaimCommitsBeforeReturning() {
        String taskNo="tsk_"+unique;orders.task(taskNo,"ord_"+unique);
        var service=annotatedClaim(ownTaskMapper(taskNo));
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
        var claimed=service.claimNext("annotation-fixture");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(claimed.taskNo()).isEqualTo(taskNo);assertThat(claimed.leaseOwner()).isEqualTo("annotation-fixture");
        var persisted=tasks.byNo(taskNo);assertThat(persisted.state()).isEqualTo("RUNNING");
        assertThat(persisted.claimVersion()).isEqualTo(1);assertThat(persisted.attemptCount()).isEqualTo(1);
    }
    /** claim已执行UPDATE后读回失败，注解必须回滚真实数据库，不能遗留半次领取。 */
    @Test void annotatedTaskClaimFailureRollsBackRealUpdate() {
        String taskNo="tsk_"+unique;orders.task(taskNo,"ord_"+unique);
        var scoped=ownTaskMapper(taskNo);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture readback failed")).when(scoped).byNo(taskNo);
        assertThatThrownBy(()->annotatedClaim(scoped).claimNext("annotation-fixture"))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var persisted=tasks.byNo(taskNo);assertThat(persisted.state()).isEqualTo("READY");
        assertThat(persisted.claimVersion()).isZero();assertThat(persisted.attemptCount()).isZero();assertThat(persisted.leaseOwner()).isNull();
    }
    /** 共享fixture中仅限制next到本测试行；claim/byNo继续调用真实生产Mapper/XML，防止领取其他用例的恢复任务。 */
    private TaskMapper ownTaskMapper(String taskNo) {
        TaskMapper scoped=mock(TaskMapper.class,call->call.getMethod().invoke(tasks,call.getArguments()));
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.queryForObject("SELECT id FROM process_task WHERE task_no=? FOR UPDATE",Long.class,taskNo);
            return tasks.byNo(taskNo);
        }).when(scoped).next();
        return scoped;
    }
    /** 手工组装与Spring容器相同的注解事务拦截器，使用真实订单库事务管理器；不手动调用裸Bean冒充事务生效。 */
    private OrderTaskClaimService annotatedClaim(TaskMapper mapper) {
        var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(jdbc.getDataSource()));
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var proxy=new org.springframework.aop.framework.ProxyFactory(new OrderTaskClaimService(mapper));
        proxy.setProxyTargetClass(true);proxy.addAdvice(interceptor);return (OrderTaskClaimService)proxy.getProxy();
    }
    /** 用真实领取SQL与版本围栏调用本任务执行入口，避免全局scan处理其他测试留下的独立任务。 */
    private void executeOwnTask(CreateOrderWorker worker,String order){var claimed=tx.write(()->{var task=tasks.byOrder(order);tasks.claim(task.taskNo(),"upgrade-fixture");return tasks.byNo(task.taskNo());});ReflectionTestUtils.invokeMethod(worker,"execute",claimed);}
}
