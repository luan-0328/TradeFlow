package com.tradeflow.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 专用测试库真实Service/XML/事务；仅模拟远端事实，不能代替渠道HTTP或Nacos验收。 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_SETTLEMENT_TEST_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/trade_settlement_test.*")
class SettlementBusinessMySqlTest {
    private JdbcTemplate jdbc;private SettlementService service;private SettlementRemote remote;
    private List<SettlementRemote.Fact> facts;private final String merchant="merchant_settlement_fixture";
    private final Instant occurred=Instant.now().truncatedTo(ChronoUnit.MILLIS);
    @BeforeEach void setup()throws Exception {
        String url=System.getenv("TRADEFLOW_SETTLEMENT_TEST_DB_URL");
        var data=new DriverManagerDataSource(url,System.getenv("TRADEFLOW_SETTLEMENT_TEST_DB_USER"),System.getenv("TRADEFLOW_SETTLEMENT_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(data);
        // 仅本脚本新建、明确命名的_test库；测试类串行运行，不接触开发或服务器业务数据。
        for(String table:List.of("settlement_ledger","settlement_fact","settlement_batch","settlement_item","settlement_account","settlement_task","settlement_reconciliation","settlement_audit"))jdbc.update("DELETE FROM "+table);
        var config=new org.apache.ibatis.session.Configuration(new Environment("settlement-fixture",new SpringManagedTransactionFactory(),data));
        try(var input=Resources.getResourceAsStream("mapper/SettlementMapper.xml")){new XMLMapperBuilder(input,config,"mapper/SettlementMapper.xml",config.getSqlFragments()).parse();}
        var mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(SettlementMapper.class);
        remote=mock(SettlementRemote.class);facts=new ArrayList<>();
        when(remote.facts(anyLong())).thenAnswer(c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new SettlementRemote.Page(List.copyOf(facts),facts.isEmpty()?0:facts.get(facts.size()-1).factId(),false);});
        when(remote.order(anyString())).thenAnswer(c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new SettlementRemote.Order(c.getArgument(0),"7001",merchant,"COMPLETED",occurred.minus(9,ChronoUnit.DAYS),false);});
        var manager=new DataSourceTransactionManager(data);
        var target=new SettlementService(mapper,remote,manager,new ObjectMapper(),new MockEnvironment().withProperty("tradeflow.settlement.fee-bps","1000").withProperty("tradeflow.settlement.freeze-days","7"));
        // 真实MySQL测试也经过Spring注解代理；run中的编程式短事务保持原有边界。
        var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var proxy=new org.springframework.aop.framework.ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(interceptor);
        service=(SettlementService)proxy.getProxy();
    }
    private SettlementRemote.Fact payment(long id,String payment,String order,long amount){return new SettlementRemote.Fact(id,payment,payment,order,"PAYMENT_SUCCEEDED",amount,"CNY",occurred);}
    private SettlementRemote.Fact refund(long id,String refund,String payment,String order,long amount){return new SettlementRemote.Fact(id,refund,payment,order,"REFUND_SUCCEEDED",amount,"CNY",occurred);}
    private long amount(String sql,Object... params){return jdbc.queryForObject(sql,Long.class,params);}
    /** 真正经过业务Service记账，结算后退款形成欠款，后批抵扣；重复事实不增加任何账本。 */
    @Test void settlementRefundDebtAndNextBatchOffsetRemainBalanced(){
        facts.add(payment(1,"pay_fixture_a","ord_fixture_a",10000));service.run();
        assertThat(amount("SELECT simulated_payout_cents FROM settlement_batch WHERE payment_no='pay_fixture_a'")).isEqualTo(9000);
        facts.add(refund(2,"rfd_fixture_a","pay_fixture_a","ord_fixture_a",2000));service.run();
        assertThat(amount("SELECT debt_cents FROM settlement_account WHERE merchant_id=?",merchant)).isEqualTo(1800);
        assertThat(amount("SELECT fee_cents FROM settlement_item WHERE payment_no='pay_fixture_a'")).isEqualTo(800);
        facts.add(payment(3,"pay_fixture_b","ord_fixture_b",10000));service.run();
        assertThat(amount("SELECT debt_offset_cents FROM settlement_batch WHERE payment_no='pay_fixture_b'")).isEqualTo(1800);
        assertThat(amount("SELECT simulated_payout_cents FROM settlement_batch WHERE payment_no='pay_fixture_b'")).isEqualTo(7200);
        assertThat(amount("SELECT debt_cents FROM settlement_account WHERE merchant_id=?",merchant)).isZero();
        long ledger=amount("SELECT COUNT(*) FROM settlement_ledger");service.run();
        assertThat(amount("SELECT COUNT(*) FROM settlement_ledger")).isEqualTo(ledger);
        assertThat(service.reconcile().get("result")).isEqualTo("PASS");
        assertThat(amount("SELECT COUNT(*) FROM settlement_ledger WHERE principal_delta-fee_delta<>payable_delta+simulated_delta")).isZero();
    }
    /** 流水末步失败必须回滚原事实和单据；恢复仍使用同事实号，不靠补写余额掩盖中间态。 */
    @Test void ledgerFailureRollsBackBusinessAndOriginalFactCanRecover(){
        facts.add(payment(1,"pay_fixture_rollback","ord_fixture_rollback",10000));
        jdbc.execute("CREATE TRIGGER reject_settlement_fixture BEFORE INSERT ON settlement_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture ledger failure'");
        try{assertThatThrownBy(()->service.run()).isInstanceOf(RuntimeException.class);assertThat(amount("SELECT COUNT(*) FROM settlement_fact")).isZero();assertThat(amount("SELECT COUNT(*) FROM settlement_item")).isZero();}
        finally{jdbc.execute("DROP TRIGGER reject_settlement_fixture");}
        service.run();assertThat(amount("SELECT COUNT(*) FROM settlement_fact")).isEqualTo(1);assertThat(amount("SELECT COUNT(*) FROM settlement_batch")).isEqualTo(1);
    }
    /** 注解入口经真实代理提交审计与本地核对，不调用资金或订单远端。 */
    @Test void annotatedAuditAndReconcileCommitLocalRecords() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
        service.audit("fixture-admin","RECONCILE");
        assertThat(amount("SELECT COUNT(*) FROM settlement_audit")).isEqualTo(1);
        assertThat(service.reconcile().get("result")).isEqualTo("PASS");
        assertThat(amount("SELECT COUNT(*) FROM settlement_reconciliation")).isEqualTo(1);
        verifyNoInteractions(remote);
    }
    /** 核对记录末步写失败时，前面的扫描任务初始化也必须由注解事务回滚。 */
    @Test void annotatedReconcileFailureRollsBackTaskInitialization() {
        jdbc.execute("CREATE TRIGGER reject_reconcile_fixture BEFORE INSERT ON settlement_reconciliation FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture reconciliation failure'");
        try {
            assertThatThrownBy(()->service.reconcile()).isInstanceOf(RuntimeException.class);
            assertThat(amount("SELECT COUNT(*) FROM settlement_task")).isZero();
            assertThat(amount("SELECT COUNT(*) FROM settlement_reconciliation")).isZero();
            verifyNoInteractions(remote);
        } finally {jdbc.execute("DROP TRIGGER reject_reconcile_fixture");}
    }
    /** 原事实异参不能改写既有账；失败保留恢复任务，原本金、退款和账本保持不变。 */
    @Test void originalFactConflictDoesNotChangeExistingBalances(){
        facts.add(payment(1,"pay_fixture_conflict","ord_fixture_conflict",10000));service.run();
        facts.set(0,payment(1,"pay_fixture_conflict","ord_fixture_conflict",10001));
        assertThatThrownBy(()->service.run()).isInstanceOf(com.tradeflow.common.BusinessException.class);
        assertThat(amount("SELECT paid_cents FROM settlement_item WHERE payment_no='pay_fixture_conflict'")).isEqualTo(10000);
        assertThat(amount("SELECT COUNT(*) FROM settlement_fact")).isEqualTo(1);
        assertThat(amount("SELECT COUNT(*) FROM settlement_batch")).isEqualTo(1);
    }
}
