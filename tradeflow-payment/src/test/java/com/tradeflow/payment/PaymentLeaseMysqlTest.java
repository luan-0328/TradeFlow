package com.tradeflow.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.contracts.PaymentContracts.*;
import com.tradeflow.payment.application.PaymentService;
import com.tradeflow.payment.infrastructure.*;
import java.time.Instant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只由 fault-mysql.py 注入全新实例地址；真实 MySQL、MyBatis、Spring 事务，RPC 仍是 Mock。 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_FAULT_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/trade_payment.*")
class PaymentLeaseMysqlTest {
    private JdbcTemplate jdbc;
    private PaymentMapper mapper;
    private PaymentMapper realMapper;
    private TransactionTemplate tx;
    private final PaymentRemote remote=mock(PaymentRemote.class);
    private PaymentService service;
    private String no,order;

    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("TRADEFLOW_FAULT_MYSQL_URL"),"root","");
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var config=new org.apache.ibatis.session.Configuration(new Environment("fault",new SpringManagedTransactionFactory(),source));
        config.addMapper(PaymentMapper.class);
        // 手工构建MyBatis配置时显式加载XML，保持真实租约与事务SQL进入测试。
        String mapperResource = "mapper/payment/PaymentMapper.xml";
        try (var input = org.apache.ibatis.io.Resources.getResourceAsStream(mapperResource)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, config, mapperResource, config.getSqlFragments()).parse();
        }
        realMapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(PaymentMapper.class);
        mapper=spy(realMapper);
        service=new PaymentService(mapper,remote,tx,new ObjectMapper().findAndRegisterModules(),new MockEnvironment()
                .withProperty("tradeflow.internal.order-token","test-order").withProperty("tradeflow.callback-secret","test-secret"));
        String suffix=java.util.UUID.randomUUID().toString().replace("-","");no="pay_"+suffix;order="ord_"+suffix;
        mapper.ensure(new Intent(order,no,"1",100,"CNY",Instant.now().plusSeconds(600),"PENDING_PAYMENT"));
        mapper.ensureTask(no);
        jdbc.update("UPDATE process_task SET step='QUERY' WHERE task_no=?",no);
    }

    /** 新 owner 接管后旧 owner/claim 均不能完成任务；同版本但错误 owner 也被拒绝。 */
    @Test void mysqlRejectsExpiredWrongOwnerAndSupersededClaims() {
        tx.executeWithoutResult(t->{mapper.claimTask(no,"owner_old");assertThat(mapper.fenceTask(no,1,"owner_old")).isNotNull();});
        assertThat(mapper.finishTask(no,1,"wrong_owner","SUCCEEDED","QUERY",null)).isZero();
        assertThat(mapper.closeOwnedTask(no,1,"wrong_owner")).isZero();
        jdbc.update("UPDATE process_task SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE task_no=?",no);
        tx.executeWithoutResult(t->{assertThat(mapper.fenceTask(no,1,"owner_old")).isNull();
            assertThat(mapper.finishTask(no,1,"owner_old","SUCCEEDED","QUERY",null)).isZero();mapper.claimTask(no,"owner_new");});
        assertThat(mapper.finishTask(no,1,"owner_old","SUCCEEDED","QUERY",null)).isZero();
        assertThat(mapper.closeOwnedTask(no,1,"owner_old")).isZero();
        assertThat(mapper.finishTask(no,2,"owner_new","SUCCEEDED","QUERY",null)).isOne();
    }

    /** 围栏通过后本地事务跨过租约：完成零行必须回滚已写入的成功事实、流水、额度和 Outbox。 */
    @Test void expiryDuringLocalApplicationRollsBackAllFinancialEffects() {
        when(remote.query(no)).thenAnswer(i->{jdbc.update("UPDATE process_task SET lease_until=UTC_TIMESTAMP(3)+INTERVAL 1 SECOND WHERE task_no=?",no);return success();});
        doAnswer(i->{int changed=realMapper.fact(i.getArgument(0));
            jdbc.execute("DO SLEEP(1.2)");return changed;
        }).when(mapper).fact(any());
        service.recover();
        verify(mapper).ledger(any());verify(mapper).refundAccount(any());
        verify(mapper).outbox(anyString(),eq(no),eq(1L),eq("PaymentSucceeded"),eq(order),anyString());
        verify(mapper).finishTask(eq(no),eq(1L),anyString(),eq("SUCCEEDED"),eq("QUERY"),isNull());
        assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE payment_no=?",String.class,no)).isEqualTo("PENDING");
        for(String table:new String[]{"payment_ledger","refund_account"})
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE payment_no=?",Long.class,no)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Long.class,no)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM process_task WHERE task_no=?",String.class,no)).isEqualTo("RUNNING");
    }

    private ChannelFact success(){Instant at=Instant.parse("2026-10-25T00:00:00Z");return new ChannelFact(no,order,100,"CNY","SUCCEEDED","channel_"+no,1,at,at);}
}
