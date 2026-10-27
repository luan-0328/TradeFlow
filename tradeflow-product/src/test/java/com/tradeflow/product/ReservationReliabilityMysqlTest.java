package com.tradeflow.product;

import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.TradingContracts;
import com.tradeflow.contracts.TradingContracts.*;
import com.tradeflow.product.application.ReservationService;
import com.tradeflow.product.infrastructure.ProductMapper;
import com.tradeflow.product.infrastructure.ReservationMapper;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import static org.assertj.core.api.Assertions.*;

/**
 * 仅供return-mysql.py创建并迁移商品V1-V5的新实例；真实XML、Spring事务和库存服务。
 * 商品、有效报价及INITIAL余额/流水为SQL合成fixture，不代表已验证商品创建、报价或入库API。
 * 并发批次只验证正确性，不据此给出吞吐量或生产容量结论。
 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_RETURN_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/trade_product.*")
class ReservationReliabilityMysqlTest {
    private JdbcTemplate jdbc;
    private ReservationService service;

    @BeforeEach void setup() throws Exception {
        var data=new DriverManagerDataSource(System.getenv("TRADEFLOW_RETURN_MYSQL_URL"),"root","");
        jdbc=new JdbcTemplate(data);
        // 手工连接必须与应用隔离级别一致；配置错误直接失败，不将其他隔离模式当作验收证据。
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("READ-COMMITTED");
        var config=new org.apache.ibatis.session.Configuration(new Environment("reservation-reliability",new SpringManagedTransactionFactory(),data));
        config.setMapUnderscoreToCamelCase(true);
        config.setArgNameBasedConstructorAutoMapping(true);
        for(Class<?> mapper:List.of(ProductMapper.class,ReservationMapper.class)) {
            String resource="mapper/product/"+mapper.getSimpleName()+".xml";
            try(var input=Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
            }
        }
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        service=new ReservationService(session.getMapper(ReservationMapper.class),session.getMapper(ProductMapper.class),new DataSourceTransactionManager(data));
    }

    /** 非演示商家使用报价归属预占；伪造另店SKU整体拒绝且无余额副作用，历史释放不要求远端商家经营状态。 */
    @Test void reservationUsesQuoteMerchantAndRejectsForeignSku() {
        String sku=stock(3);var command=command(new ReservationLine(sku,2));
        jdbc.update("UPDATE product SET merchant_id='merchant_second' WHERE id=(SELECT product_id FROM sku WHERE id=?)",sku);
        jdbc.update("UPDATE product_quote SET merchant_id='merchant_second' WHERE quote_no=?",command.quoteNo());
        assertThat(service.reserve(command).status()).isEqualTo("RESERVED");balance(sku,1,2);
        assertThat(service.release(command.reservationNo(),new ReleaseCommand(command.orderNo(),"RELEASE")).status()).isEqualTo("RELEASED");balance(sku,3,0);
        var foreign=command(new ReservationLine(sku,1));
        var rejected=service.reserve(foreign);assertThat(rejected.status()).isEqualTo("REJECTED");assertThat(rejected.rejectCode()).isEqualTo("SKU_NOT_SALEABLE");
        balance(sku,3,0);assertUnbound(foreign);reconcile(sku);
    }

    /** 多个不同订单争用同一有限余额；拒绝必须成为终态，不能留下部分明细或重复流水。 */
    @Test void distinctReservationsCannotOversellAndKeepQuantityConserved() throws Exception {
        String sku=stock(5);
        var commands=new ArrayList<ReservationCommand>();
        for(int i=0;i<12;i++)commands.add(command(new ReservationLine(sku,1)));
        var results=concurrently(12,i->()->service.reserve(commands.get(i)));
        assertThat(results).filteredOn(r->r.status().equals("RESERVED")).hasSize(5);
        assertThat(results).filteredOn(r->r.status().equals("REJECTED")).hasSize(7)
                .allSatisfy(r->assertThat(r.rejectCode()).isEqualTo("INSUFFICIENT_STOCK"));
        balance(sku,0,5);
        assertThat(number("SELECT COUNT(*) FROM reservation_item WHERE sku_id=?",sku)).isEqualTo(5);
        assertThat(number("SELECT COUNT(*) FROM inventory_ledger WHERE sku_id=? AND operation_type='RESERVE'",sku)).isEqualTo(5);
        for(var command:commands) {
            assertThat(service.reserve(command)).isEqualTo(service.query(command.reservationNo()));
            if(service.query(command.reservationNo()).status().equals("REJECTED"))assertUnbound(command);
        }
        reconcile(sku);
    }

    /** 同号并发重放必须返回同一事实；摘要真实计算，随后同号异参也不得改变既有库存。 */
    @Test void concurrentReplayDeductsOnlyOnceAndRejectsChangedFingerprint() throws Exception {
        String sku=stock(10);
        var command=command(new ReservationLine(sku,3));
        var results=concurrently(12,i->()->service.reserve(command));
        assertThat(results).allSatisfy(result->assertThat(result.status()).isEqualTo("RESERVED"));
        assertThat(results).allSatisfy(result->assertThat(result).isEqualTo(results.getFirst()));
        balance(sku,7,3);
        assertThat(number("SELECT COUNT(*) FROM reservation_item WHERE reservation_no=?",command.reservationNo())).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM inventory_ledger WHERE business_no=?",command.reservationNo())).isEqualTo(1);
        var changed=hashed(new ReservationCommand(command.reservationNo(),command.orderNo(),command.quoteNo(),command.userId(),null,
                command.payDeadline(),List.of(new ReservationLine(sku,4))));
        assertCode(()->service.reserve(changed),"IDEMPOTENCY_CONFLICT");
        balance(sku,7,3);reconcile(sku);
    }

    /** 先到的释放留下墓碑；迟到预占与确认都不能恢复已终止的购买意图。 */
    @Test void releaseTombstoneBlocksLateReserveAndConfirm() {
        String sku=stock(4);var command=command(new ReservationLine(sku,2));
        var released=service.release(command.reservationNo(),new ReleaseCommand(command.orderNo(),"RELEASE"));
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(service.reserve(command)).isEqualTo(released);
        assertThat(service.release(command.reservationNo(),new ReleaseCommand(command.orderNo(),"RELEASE"))).isEqualTo(released);
        assertCode(()->service.confirm(command.reservationNo(),new ReleaseCommand(command.orderNo(),"COMMIT")),"STOCK_ALREADY_RELEASED");
        assertThat(number("SELECT COUNT(*) FROM reservation_item WHERE reservation_no=?",command.reservationNo())).isZero();
        assertThat(number("SELECT COUNT(*) FROM inventory_ledger WHERE business_no=?",command.reservationNo())).isZero();
        assertUnbound(command);balance(sku,4,0);reconcile(sku);
    }

    /** 较大SKU不足时，较小SKU已执行的余额、明细、流水和报价绑定必须全部回滚。 */
    @Test void laterSkuInsufficientRollsBackEntireReservationBeforePersistingRejection() {
        String first=stock(5),second=stock(0);
        assertThat(Long.parseLong(first)).isLessThan(Long.parseLong(second));
        var command=command(new ReservationLine(second,1),new ReservationLine(first,2));
        var result=service.reserve(command);
        assertThat(result.status()).isEqualTo("REJECTED");assertThat(result.rejectCode()).isEqualTo("INSUFFICIENT_STOCK");
        balance(first,5,0);balance(second,0,0);assertUnbound(command);
        assertThat(number("SELECT COUNT(*) FROM reservation_item WHERE reservation_no=?",command.reservationNo())).isZero();
        assertThat(number("SELECT COUNT(*) FROM inventory_ledger WHERE business_no=?",command.reservationNo())).isZero();
        assertThat(service.reserve(command)).isEqualTo(result);reconcile(first);reconcile(second);
    }

    /** COMMIT与RELEASE同时争同一头锁；只允许一个决策，并检查重放和余额/流水终态一致。 */
    @Test void confirmAndReleaseRaceHasExactlyOneDecision() throws Exception {
        for(int round=0;round<12;round++) {
            String sku=stock(3);var command=command(new ReservationLine(sku,2));
            assertThat(service.reserve(command).status()).isEqualTo("RESERVED");
            var results=concurrently(2,i->()->{
                try {
                    return i==0?service.confirm(command.reservationNo(),new ReleaseCommand(command.orderNo(),"COMMIT")).status()
                            :service.release(command.reservationNo(),new ReleaseCommand(command.orderNo(),"RELEASE")).status();
                } catch(BusinessException failure) {
                    assertThat(failure.code()).isIn("STOCK_ALREADY_COMMITTED","STOCK_ALREADY_RELEASED");
                    return failure.code();
                }
            });
            String status=service.query(command.reservationNo()).status();
            assertThat(results).contains(status);
            assertThat(results.stream().filter(s->s.startsWith("STOCK_ALREADY_")).count()).isEqualTo(1);
            boolean committed=status.equals("CONFIRMED");assertThat(status).isIn("CONFIRMED","RELEASED");
            assertThat(jdbc.queryForObject("SELECT decision FROM reservation_header WHERE reservation_no=?",String.class,command.reservationNo()))
                    .isEqualTo(committed?"COMMIT":"RELEASE");
            balance(sku,committed?1:3,0);
            assertThat(number("SELECT COUNT(*) FROM inventory_ledger WHERE business_no=?",command.reservationNo())).isEqualTo(2);
            assertThat(number("SELECT COUNT(*) FROM inventory_ledger WHERE business_no=? AND operation_type=?",command.reservationNo(),committed?"CONFIRM":"RELEASE")).isEqualTo(1);
            if(committed)service.confirm(command.reservationNo(),new ReleaseCommand(command.orderNo(),"COMMIT"));
            else service.release(command.reservationNo(),new ReleaseCommand(command.orderNo(),"RELEASE"));
            assertThat(number("SELECT COUNT(*) FROM inventory_ledger WHERE business_no=?",command.reservationNo())).isEqualTo(2);
            reconcile(sku);
        }
    }

    /** 流水晚失败必须撤销头、明细、余额和绑定；去除故障后原业务号可安全恢复。 */
    @Test void reserveLedgerFailureRollsBackAllEffectsAndOriginalCommandCanRecover() {
        String sku=stock(4);var command=command(new ReservationLine(sku,2));
        failLedger(command,"RESERVE",()->service.reserve(command));
        balance(sku,4,0);assertUnbound(command);
        assertThat(number("SELECT COUNT(*) FROM reservation_header WHERE reservation_no=?",command.reservationNo())).isZero();
        assertThat(number("SELECT COUNT(*) FROM reservation_item WHERE reservation_no=?",command.reservationNo())).isZero();
        assertThat(number("SELECT COUNT(*) FROM inventory_ledger WHERE business_no=?",command.reservationNo())).isZero();
        assertThat(service.reserve(command).status()).isEqualTo("RESERVED");balance(sku,2,2);reconcile(sku);
    }

    /** 确认/释放已改余额后流水失败，原预占与未决状态必须保留，原号重试才能推进终态。 */
    @Test void decisionLedgerFailureRollsBackBalanceAndDecisionThenRecovers() {
        for(String operation:List.of("CONFIRM","RELEASE")) {
            String sku=stock(4);var command=command(new ReservationLine(sku,2));service.reserve(command);
            Runnable decision=()->{
                if(operation.equals("CONFIRM"))service.confirm(command.reservationNo(),new ReleaseCommand(command.orderNo(),"COMMIT"));
                else service.release(command.reservationNo(),new ReleaseCommand(command.orderNo(),"RELEASE"));
            };
            failLedger(command,operation,decision);
            balance(sku,2,2);assertThat(service.query(command.reservationNo()).status()).isEqualTo("RESERVED");
            assertThat(jdbc.queryForObject("SELECT decision FROM reservation_header WHERE reservation_no=?",String.class,command.reservationNo())).isEqualTo("UNDECIDED");
            assertThat(number("SELECT COUNT(*) FROM inventory_ledger WHERE business_no=?",command.reservationNo())).isEqualTo(1);
            reconcile(sku);decision.run();balance(sku,operation.equals("CONFIRM")?2:4,0);reconcile(sku);
        }
    }

    /** 触发器只拦当前业务号的指定流水，在隔离库注入持久化错误；finally保证撤除。 */
    private void failLedger(ReservationCommand command,String operation,Runnable action) {
        String trigger="fixture_ledger_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON inventory_ledger FOR EACH ROW BEGIN IF NEW.business_no='"+
                command.reservationNo()+"' AND NEW.operation_type='"+operation+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture ledger failure'; END IF; END");
        try {assertThatThrownBy(action::run).isInstanceOf(DataAccessException.class);}
        finally {jdbc.execute("DROP TRIGGER "+trigger);}
    }

    /** 合成前置商品和INITIAL流水，余额有明确测试来源；零库存不制造零delta流水。 */
    private String stock(long quantity) {
        long product=insert("INSERT INTO product(merchant_id,name,description,status) VALUES('merchant_demo','库存可靠性测试','SQL合成fixture','ON_SALE')");
        String unique=UUID.randomUUID().toString().replace("-","");
        String sku=Long.toString(insert("INSERT INTO sku(product_id,sku_code,specification,price_cents) VALUES("+product+",'reliability-"+unique+"','合成规格',100)"));
        jdbc.update("INSERT INTO inventory(warehouse_id,sku_id,available_qty) VALUES('warehouse_main',?,?)",sku,quantity);
        if(quantity>0)jdbc.update("INSERT INTO inventory_ledger(business_no,operation_type,warehouse_id,sku_id,available_delta,reserved_delta,available_after,reserved_after) VALUES(?,'INITIAL','warehouse_main',?,?,0,?,0)","fixture_"+unique,sku,quantity,quantity);
        return sku;
    }

    /** 合成未绑定有效报价，业务号严格遵循协议格式，待测预占摘要由真实契约算法生成。 */
    private ReservationCommand command(ReservationLine... lines) {
        String unique=UUID.randomUUID().toString().replace("-","");
        var command=hashed(new ReservationCommand("res_"+unique,"ord_"+unique,"quo_"+unique,"1",null,Instant.now().plusSeconds(600),List.of(lines)));
        jdbc.update("INSERT INTO product_quote(quote_no,user_id,merchant_id,source_order_no,request_hash,expires_at) VALUES(?,'1','merchant_demo',?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE))",command.quoteNo(),command.orderNo(),"0".repeat(64));
        for(var line:lines)jdbc.update("INSERT INTO quote_item(quote_no,sku_id,quantity,unit_price_cents,price_version) VALUES(?,?,?,100,0)",command.quoteNo(),line.skuId(),line.quantity());
        return command;
    }
    private ReservationCommand hashed(ReservationCommand c) {
        return new ReservationCommand(c.reservationNo(),c.orderNo(),c.quoteNo(),c.userId(),TradingContracts.reservationHash(c),c.payDeadline(),c.items());
    }
    private long insert(String sql) {
        var key=new GeneratedKeyHolder();jdbc.update(connection->connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS),key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private long number(String sql,Object... args) {return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args));}
    private void balance(String sku,long available,long reserved) {
        assertThat(number("SELECT available_qty FROM inventory WHERE sku_id=?",sku)).isEqualTo(available);
        assertThat(number("SELECT reserved_qty FROM inventory WHERE sku_id=?",sku)).isEqualTo(reserved);
    }
    private void assertUnbound(ReservationCommand command) {
        assertThat(jdbc.queryForObject("SELECT status FROM product_quote WHERE quote_no=?",String.class,command.quoteNo())).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("SELECT bound_order_no FROM product_quote WHERE quote_no=?",String.class,command.quoteNo())).isNull();
    }
    /** 从INITIAL起逐笔重放双余额，既核验累计delta也核验每条after快照，不只比较最终非负。 */
    private void reconcile(String sku) {
        long available=0,reserved=0;
        for(var row:jdbc.queryForList("SELECT available_delta,reserved_delta,available_after,reserved_after FROM inventory_ledger WHERE sku_id=? ORDER BY id",sku)) {
            available+=((Number)row.get("available_delta")).longValue();reserved+=((Number)row.get("reserved_delta")).longValue();
            assertThat(available).isGreaterThanOrEqualTo(0);assertThat(reserved).isGreaterThanOrEqualTo(0);
            assertThat(((Number)row.get("available_after")).longValue()).isEqualTo(available);
            assertThat(((Number)row.get("reserved_after")).longValue()).isEqualTo(reserved);
        }
        balance(sku,available,reserved);
    }
    private void assertCode(Runnable action,String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(code));
    }
    /** 屏障同时放行真实独立连接；任务异常和超时必须传播，不能当作业务拒绝计入成功证据。 */
    private <T> List<T> concurrently(int count,IntFunction<Callable<T>> tasks) throws Exception {
        var pool=Executors.newFixedThreadPool(count);var ready=new CountDownLatch(count);var start=new CountDownLatch(1);
        var futures=new ArrayList<Future<T>>();
        try {
            for(int i=0;i<count;i++) {
                var task=tasks.apply(i);futures.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new TimeoutException("启动屏障超时");return task.call();}));
            }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            var results=new ArrayList<T>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));return results;
        } finally {start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
}
