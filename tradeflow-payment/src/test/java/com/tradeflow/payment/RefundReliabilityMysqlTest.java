package com.tradeflow.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.payment.application.RefundService;
import com.tradeflow.payment.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离MySQL上的真实退款用例、Mapper和Spring事务；订单授权与通道网络边界使用Mock，不代表端到端验收。 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_FAULT_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/trade_payment.*")
class RefundReliabilityMysqlTest {
    private JdbcTemplate jdbc;
    private RefundService service;
    private final PaymentRemote remote=mock(PaymentRemote.class);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private String payment,order;

    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("TRADEFLOW_FAULT_MYSQL_URL"),"root","");
        jdbc=new JdbcTemplate(source);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var config=new org.apache.ibatis.session.Configuration(new Environment("refund-fault",new SpringManagedTransactionFactory(),source));
        config.addMapper(PaymentMapper.class);config.addMapper(RefundMapper.class);
        for(String name:List.of("PaymentMapper","RefundMapper")){
            String resource="mapper/payment/"+name+".xml";
            try(var input=org.apache.ibatis.io.Resources.getResourceAsStream(resource)){
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
            }
        }
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        service=new RefundService(session.getMapper(RefundMapper.class),session.getMapper(PaymentMapper.class),remote,tx,json,
                new MockEnvironment().withProperty("tradeflow.internal.order-token","test-order")
                        .withProperty("tradeflow.internal.aftersale-token","test-aftersale").withProperty("tradeflow.callback-secret","test-secret"));
        payment=id("pay_");order=id("ord_");
        jdbc.update("INSERT INTO payment_order(payment_no,order_no,user_id,amount_cents,currency,status) VALUES(?,?,?,100,'CNY','SUCCEEDED')",payment,order,"1");
        jdbc.update("INSERT INTO refund_account(payment_no,paid_amount_cents) VALUES(?,100)",payment);
    }

    /** 仅停用本用例的恢复任务，保留业务行作为证据，避免后续用例领取先前未决退款。 */
    @AfterEach void stopOwnTasks(){
        if(jdbc!=null&&payment!=null)jdbc.update("UPDATE process_task t JOIN refund_order r ON r.refund_no=t.task_no SET t.state='MANUAL_REVIEW' WHERE r.payment_no=?",payment);
    }

    /** 两个不同来源同时争抢同一支付本金，真实行锁使超额请求整体回滚，不残留来源或任务。 */
    @Test void concurrentSourcesCannotOverRefundPayment() throws Exception {
        String a=authorize(80),b=authorize(80);
        List<Object> results=race(List.of(new Submit(id("rfd_"),a,order),new Submit(id("rfd_"),b,order)));
        assertThat(results.stream().filter(View.class::isInstance).count()).isOne();
        assertThat(results.stream().filter(x->isCode(x,"REFUND_QUOTA_EXCEEDED")).count()).isOne();
        assertAccount(80,0);
        assertThat(count("refund_order","payment_no",payment)).isOne();
        assertThat(count("refund_source","payment_no",payment)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_task t JOIN refund_order r ON r.refund_no=t.task_no WHERE r.payment_no=?",Long.class,payment)).isOne();
    }

    /** 同退款号八线程重放返回同一持久结果，支付和来源只预留一次，任务及审计不重复。 */
    @Test void concurrentSameRefundNumberReservesOnlyOnce() throws Exception {
        String source=authorize(40),refund=id("rfd_");
        List<Object> results=race(Collections.nCopies(8,new Submit(refund,source,order)));
        assertThat(results).allMatch(View.class::isInstance);
        assertThat(results.stream().map(x->((View)x).refundNo()).distinct().toList()).containsExactly(refund);
        assertAccount(40,0);assertSource(source,40,0,refund);
        assertThat(count("refund_order","payment_no",payment)).isOne();
        assertThat(count("process_task","task_no",refund)).isOne();
        assertThat(count("audit_log","business_no",refund)).isOne();
    }

    /** 同业务来源换退款号不能并行执行，失败的来源占用使先前支付占用同事务回滚。 */
    @Test void concurrentSameSourceAllowsOnlyOneActiveRefund() throws Exception {
        String source=authorize(40);
        List<Object> results=race(List.of(new Submit(id("rfd_"),source,order),new Submit(id("rfd_"),source,order)));
        assertThat(results.stream().filter(View.class::isInstance).count()).isOne();
        assertThat(results.stream().filter(x->isCode(x,"REFUND_QUOTA_EXCEEDED")).count()).isOne();
        assertAccount(40,0);assertThat(count("refund_order","source_no",source)).isOne();
        assertSource(source,40,0,((View)results.stream().filter(View.class::isInstance).findFirst().orElseThrow()).refundNo());
    }

    /** 写通道超时结果未知必须保留双额度；恢复查询原号，RPC执行时没有本地事务。 */
    @Test void unknownOutcomeKeepsReservationAndQueriesOriginalNumber() {
        String source=authorize(40),refund=id("rfd_");submit(refund,source);
        when(remote.refund(any())).thenAnswer(i->{assertNoTransaction();throw new IllegalStateException("模拟通道已受理但响应丢失");});
        service.recover();
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE refund_no=?",String.class,refund)).isEqualTo("UNKNOWN");
        assertAccount(40,0);assertSource(source,40,0,refund);
        when(remote.refundQuery(refund)).thenAnswer(i->{assertNoTransaction();return fact(refund,source,40,"UNKNOWN");});
        jdbc.update("UPDATE process_task SET next_retry_at=UTC_TIMESTAMP(3) WHERE task_no=?",refund);
        service.recover();
        verify(remote,times(1)).refund(any());verify(remote).refundQuery(refund);
        assertAccount(40,0);assertThat(count("payment_ledger","refund_no",refund)).isZero();
        assertThat(count("outbox_event","aggregate_id",refund)).isZero();
    }

    /** 不同通知号并发重复成功及同通知重放，只结算一次额度、成功流水及Outbox。 */
    @Test void concurrentSuccessCallbacksSettleAndRecordOnlyOnce() throws Exception {
        String source=authorize(40),refund=id("rfd_");submit(refund,source);
        ChannelFact fact=fact(refund,source,40,"SUCCEEDED");
        runTogether(List.of(()->{callback("notice_a_"+refund,fact);return null;},()->{callback("notice_b_"+refund,fact);return null;}));
        callback("notice_a_"+refund,fact);
        assertAccount(0,40);assertSource(source,0,40,null);
        assertThat(count("payment_ledger","refund_no",refund)).isOne();
        assertThat(jdbc.queryForObject("SELECT SUM(amount_cents) FROM payment_ledger WHERE refund_no=?",Long.class,refund)).isEqualTo(40);
        assertThat(count("outbox_event","aggregate_id",refund)).isOne();
    }

    /** 可信失败释放执行占用但来源授权保留；同源使用新退款号重试可重新占用且没有成功流水。 */
    @Test void definitiveFailureReleasesQuotaForNewAttempt() throws Exception {
        String source=authorize(40),refund=id("rfd_");submit(refund,source);
        callback("fail_"+refund,fact(refund,source,40,"FAILED"));
        assertAccount(0,0);assertSource(source,0,0,null);
        assertThat(count("payment_ledger","refund_no",refund)).isZero();
        String retry=id("rfd_");submit(retry,source);
        assertAccount(40,0);assertSource(source,40,0,retry);
        assertThat(count("refund_order","source_no",source)).isEqualTo(2);
    }

    /** 既有退款号不能改来源；既有业务来源不能改授权金额；同通知号也不能换终态正文。 */
    @Test void sameKeysWithDifferentBindingsAreRejected() throws Exception {
        String source=authorize(40),refund=id("rfd_");submit(refund,source);
        assertThatThrownBy(()->submit(refund,id("as_"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("REFUND_BINDING_CONFLICT"));
        when(remote.authorization(source)).thenReturn(authorization(source,50));
        assertThatThrownBy(()->submit(id("rfd_"),source)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("REFUND_SOURCE_CONFLICT"));
        callback("binding_"+refund,fact(refund,source,40,"UNKNOWN"));
        assertThatThrownBy(()->callback("binding_"+refund,fact(refund,source,40,"FAILED"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("NOTIFICATION_CONFLICT"));
        assertAccount(40,0);assertSource(source,40,0,refund);
        assertThat(count("refund_order","source_no",source)).isOne();
    }

    private String authorize(long amount){String source=id("as_");when(remote.authorization(source)).thenAnswer(i->{assertNoTransaction();return authorization(source,amount);});return source;}
    private Authorization authorization(String source,long amount){return new Authorization(source,"REFUND_ONLY",order,payment,"1","2",amount,"CNY","EXECUTING",1);}
    private View submit(String refund,String source){return service.submit("aftersale","test-aftersale",new Submit(refund,source,order));}
    private ChannelFact fact(String refund,String source,long amount,String status){Instant time=Instant.parse("2026-10-25T00:00:00Z");return new ChannelFact(refund,payment,order,source,amount,"CNY",status,"channel_"+refund,1,time,"SUCCEEDED".equals(status)?time:null);}
    private void callback(String notification,ChannelFact fact) throws Exception {
        byte[] body=json.writeValueAsBytes(new Callback(notification,fact));String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce="test_nonce";
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));
        service.callback(body,timestamp,nonce,HexFormat.of().formatHex(mac.doFinal(body)));
    }
    private List<Object> race(List<Submit> commands) throws Exception {
        List<Callable<Object>> jobs=new ArrayList<>();
        for(Submit command:commands)jobs.add(()->{try{return service.submit("aftersale","test-aftersale",command);}catch(BusinessException e){return e;}});
        return runTogether(jobs);
    }
    /** 同时释放线程起跑屏障，每个线程通过Spring线程绑定连接开启自己的真实事务；超时直接失败。 */
    private List<Object> runTogether(List<Callable<Object>> jobs) throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(jobs.size());CountDownLatch ready=new CountDownLatch(jobs.size()),start=new CountDownLatch(1);
        try{
            List<Future<Object>> futures=new ArrayList<>();
            for(Callable<Object> job:jobs)futures.add(workers.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("并发起跑超时");return job.call();}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            List<Object> results=new ArrayList<>();for(Future<Object> future:futures)results.add(future.get(30,TimeUnit.SECONDS));return results;
        }finally{start.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    private void assertAccount(long reserved,long succeeded){assertThat(jdbc.queryForMap("SELECT reserved_refund_cents,succeeded_refund_cents FROM refund_account WHERE payment_no=?",payment)).containsEntry("reserved_refund_cents",reserved).containsEntry("succeeded_refund_cents",succeeded);}
    private void assertSource(String source,long reserved,long succeeded,String active){assertThat(jdbc.queryForMap("SELECT reserved_cents,succeeded_cents,active_refund_no FROM refund_source WHERE source_no=?",source)).containsEntry("reserved_cents",reserved).containsEntry("succeeded_cents",succeeded).containsEntry("active_refund_no",active);}
    private long count(String table,String column,String value){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?",Long.class,value);}
    private static boolean isCode(Object value,String code){return value instanceof BusinessException e&&code.equals(e.code());}
    private static void assertNoTransaction(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("远程边界不能占用本地事务").isFalse();}
    private static String id(String prefix){return prefix+UUID.randomUUID().toString().replace("-","");}
}
