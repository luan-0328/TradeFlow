package com.tradeflow.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.product.application.*;
import com.tradeflow.product.api.ProductModels.*;
import com.tradeflow.product.infrastructure.*;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅return-mysql.py创建的新实例；真实Mapper及Spring事务，权威收据/身份RPC仅Mock边界。 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_RETURN_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/trade_product.*")
class ReturnDispositionMysqlTest {
    private JdbcTemplate jdbc;
    private ReturnRestockService returns;
    private InventoryConditionService conditions;
    private InventoryConditionMapper conditionMapper;
    private ProductMapper productMapper;
    private ReturnReceiptClient receiptClient;
    private String sku,order,reservation,source,receipt;
    private Jwt jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("merchant_admin").build();

    @BeforeEach void setup() throws Exception {
        var data=new DriverManagerDataSource(System.getenv("TRADEFLOW_RETURN_MYSQL_URL"),"root","");
        jdbc=new JdbcTemplate(data);
        var manager=new DataSourceTransactionManager(data);
        var config=new org.apache.ibatis.session.Configuration(new Environment("return-mysql",new SpringManagedTransactionFactory(),data));
        config.setMapUnderscoreToCamelCase(true);config.setArgNameBasedConstructorAutoMapping(true);
        // 手工Factory与应用加载同一份XML；解析时同时注册Mapper，避免只注册接口却遗漏SQL。
        for(Class<?> mapper:List.of(ProductMapper.class,ReturnRestockMapper.class,RestockMapper.class,ReservationMapper.class,InventoryConditionMapper.class)){
            String resource="mapper/product/"+mapper.getSimpleName()+".xml";
            try(var input=Resources.getResourceAsStream(resource)){
                new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
            }
        }
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        ProductMapper products=session.getMapper(ProductMapper.class);
        productMapper=products;
        conditionMapper=session.getMapper(InventoryConditionMapper.class);
        receiptClient=mock(ReturnReceiptClient.class);
        returns=new ReturnRestockService(receiptClient,session.getMapper(ReturnRestockMapper.class),session.getMapper(RestockMapper.class),
                session.getMapper(ReservationMapper.class),products,manager,conditionMapper);
        var authority=mock(AuthorityClient.class);
        when(authority.merchant(any(),anyBoolean())).thenAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new AuthContext("merchant_admin","ACTIVE",List.of("MERCHANT_ADMIN"),List.of("merchant_demo"),1,java.util.Map.of("merchant_demo","ADMIN"));
        });
        when(authority.selectedMerchant(any())).thenReturn("merchant_demo");
        conditions=new InventoryConditionService(authority,products,conditionMapper,new IdempotentWork(products,new ObjectMapper(),manager));
        String unique=UUID.randomUUID().toString().replace("-","");order="ord_"+unique;reservation="res_"+unique;source="as_"+unique;receipt="RET:"+source;
        long product=insert("INSERT INTO product(merchant_id,name,description,status) VALUES('merchant_demo','隔离退货测试','合成测试商品','ON_SALE')");
        sku=Long.toString(insert("INSERT INTO sku(product_id,sku_code,specification,price_cents) VALUES("+product+",'"+unique+"','测试规格',100)"));
        // 仅构造已售出、库存为零的前置fixture；待测余额全部由真实业务用例产生。
        jdbc.update("INSERT INTO inventory(warehouse_id,sku_id) VALUES('warehouse_main',?)",sku);
        jdbc.update("INSERT INTO reservation_header(reservation_no,order_no,status,decision) VALUES(?,?,'CONFIRMED','COMMIT')",reservation,order);
    }
    private long insert(String sql){
        var key=new GeneratedKeyHolder();jdbc.update(connection->connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS),key);return key.getKey().longValue();
    }
    private List<ReturnLine> seedReceipt(String... dispositions){
        jdbc.update("INSERT INTO reservation_item(reservation_no,warehouse_id,sku_id,quantity) VALUES(?,'warehouse_main',?,?)",reservation,sku,dispositions.length);
        var lines=new ArrayList<ReturnLine>();for(int i=0;i<dispositions.length;i++)lines.add(new ReturnLine(sku,i+1,sku,dispositions[i]));
        useReceipt(lines);return lines;
    }
    private void useReceipt(List<ReturnLine> lines){
        when(receiptClient.load(receipt)).thenAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ReturnReceipt(receipt,source,order,reservation,"RECEIVED",lines);
        });
    }
    private ReturnRestock command(){return new ReturnRestock(receipt,source,order,reservation);}
    private long available(){return jdbc.queryForObject("SELECT available_qty FROM inventory WHERE sku_id=?",Long.class,sku);}
    private long bucket(String condition){return jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM inventory_condition WHERE sku_id=? AND stock_condition=?",Long.class,sku,condition);}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE sku_id=?",Long.class,sku);}
    private InventoryConditionService.Change change(String from,String to,long quantity){return new InventoryConditionService.Change(sku,from,to,quantity,"真实隔离验收及处置");}

    @Test void mixedReceiptOnlyRestoresSaleableAndBindsFullDisposition(){
        var lines=seedReceipt("SALEABLE","QUARANTINE","DAMAGED","SCRAPPED");
        assertThat(returns.restock(command()).status()).isEqualTo("APPLIED");
        assertThat(available()).isEqualTo(1);assertThat(bucket("QUARANTINE")).isEqualTo(1);assertThat(bucket("DAMAGED")).isEqualTo(1);
        assertThat(count("return_restock_item")).isEqualTo(4);assertThat(count("inventory_ledger")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT disposition FROM return_restock_item WHERE receipt_no=? AND unit_no=4",String.class,receipt)).isEqualTo("SCRAPPED");
        returns.restock(command());
        assertThat(available()).isEqualTo(1);assertThat(count("inventory_ledger")).isEqualTo(1);
        var changed=new ArrayList<>(lines);changed.set(0,new ReturnLine(sku,1,sku,"DAMAGED"));useReceipt(changed);
        assertThatThrownBy(()->returns.restock(command())).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("RETURN_RECEIPT_CONFLICT"));
        assertThat(available()).isEqualTo(1);assertThat(bucket("DAMAGED")).isEqualTo(1);
    }
    @Test void conditionTransitionsAreAtomicIdempotentAndPreserveOriginalReceipt(){
        seedReceipt("QUARANTINE","QUARANTINE","DAMAGED","SCRAPPED");returns.restock(command());
        var result=conditions.change(jwt,"inspect-"+sku,change("QUARANTINE","SALEABLE",1));
        assertThat(conditions.lookup(jwt,"inspect-"+sku,sku)).isEqualTo(result);
        assertThat(conditions.lookup(jwt,"unconfirmed-key",sku)).isNull();
        assertThat(conditionMapper.lookup("other_actor","inspect-"+sku,sku)).isNull();
        assertThat(conditionMapper.lookup("merchant_admin","inspect-"+sku,"999999999")).isNull();
        assertThat(conditions.change(jwt,"inspect-"+sku,change("QUARANTINE","SALEABLE",1))).isEqualTo(result);
        assertThatThrownBy(()->conditions.change(jwt,"inspect-"+sku,change("QUARANTINE","DAMAGED",1))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        conditions.change(jwt,"damage-"+sku,change("QUARANTINE","DAMAGED",1));
        conditions.change(jwt,"scrap-"+sku,change("DAMAGED","SCRAPPED",2));
        assertThat(available()).isEqualTo(1);assertThat(bucket("QUARANTINE")).isZero();assertThat(bucket("DAMAGED")).isZero();
        assertThat(count("inventory_condition_operation")).isEqualTo(3);assertThat(count("return_restock_item")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT disposition FROM return_restock_item WHERE receipt_no=? AND unit_no=1",String.class,receipt)).isEqualTo("QUARANTINE");
    }
    @Test void concurrentScrapCannotSpendMorePhysicalUnitsThanExist() throws Exception {
        seedReceipt("QUARANTINE","QUARANTINE","QUARANTINE","QUARANTINE");returns.restock(command());
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
        try{
            for(int i=0;i<8;i++){String key="concurrent-"+sku+"-"+i;futures.add(pool.submit(()->{
                start.await();try{conditions.change(jwt,key,change("QUARANTINE","SCRAPPED",1));return true;}
                catch(BusinessException failure){assertThat(failure.code()).isEqualTo("CONDITION_STOCK_INSUFFICIENT");return false;}
            }));}
            start.countDown();int succeeded=0;for(var future:futures)if(future.get(20,TimeUnit.SECONDS))succeeded++;
            assertThat(succeeded).isEqualTo(4);assertThat(bucket("QUARANTINE")).isZero();assertThat(available()).isZero();
            assertThat(count("inventory_condition_operation")).isEqualTo(4);
        }finally{pool.shutdownNow();}
    }
    /** 覆盖XML生成键及内部Map改名后的真实商品闭环；Redis不可用不影响权威商品写入。 */
    @Test void catalogCrudRetainsGeneratedKeysVersioningAndHiddenDrafts(){
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("fixture redis unavailable"));
        var cache=new CatalogCache(redis,new ObjectMapper());
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        var authority=mock(AuthorityClient.class);
        when(authority.selectedMerchant(any())).thenReturn("merchant_demo");
        var service=new ProductService(productMapper,authority,new IdempotentWork(productMapper,new ObjectMapper(),manager),manager,cache);
        String key="create-"+sku;
        var input=new ProductWrite("XML商品","规格映射回归",ProductStatus.ON_SALE,
                List.of(new WriteSku("XML-"+sku,"规格",100L)));
        var created=service.create(jwt,key,input,"request-fixture","trace-fixture");
        assertThat(created.productId()).matches("[1-9][0-9]*");
        assertThat(created.skus().getFirst().skuId()).matches("[1-9][0-9]*");
        assertThat(created.skus().getFirst().availableQty()).isZero();
        assertThat(service.create(jwt,key,input,"request-fixture","trace-fixture")).isEqualTo(created);
        assertThat(service.detail(created.productId())).isEqualTo(created);
        service.editSku(jwt,created.skus().getFirst().skuId(),new SkuEdit(0L,200L,SkuStatus.ACTIVE),"request-fixture","trace-fixture");
        assertThat(service.detail(created.productId()).skus().getFirst().priceCents()).isEqualTo(200);
        service.edit(jwt,created.productId(),new ProductEdit(0L,"草稿","已下架",ProductStatus.OFF_SALE),"request-fixture","trace-fixture");
        assertThatThrownBy(()->service.detail(created.productId())).isInstanceOfSatisfying(BusinessException.class,
                error->assertThat(error.code()).isEqualTo("PRODUCT_NOT_FOUND"));
    }

    @Test void lateAuditFailureRollsBackBalancesLedgerOperationAndIdempotency(){
        seedReceipt("QUARANTINE");returns.restock(command());
        String key="rollback-"+sku;
        jdbc.execute("CREATE TRIGGER fixture_reject_condition_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture audit failure'");
        try{
            assertThatThrownBy(()->conditions.change(jwt,key,change("QUARANTINE","SALEABLE",1))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }finally{jdbc.execute("DROP TRIGGER fixture_reject_condition_audit");}
        assertThat(available()).isZero();assertThat(bucket("QUARANTINE")).isEqualTo(1);
        assertThat(count("inventory_ledger")).isZero();assertThat(count("inventory_condition_operation")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request WHERE idempotency_key=?",Long.class,key)).isZero();
        assertThat(conditions.lookup(jwt,key,sku)).isNull();
        conditions.change(jwt,key,change("QUARANTINE","SALEABLE",1));
        assertThat(available()).isEqualTo(1);assertThat(bucket("QUARANTINE")).isZero();
    }
}
