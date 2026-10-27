package com.tradeflow.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.product.api.ProductModels.*;
import com.tradeflow.product.application.*;
import com.tradeflow.product.infrastructure.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
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
import org.springframework.mock.web.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅upgrade-mysql.py全新商品V1-V6库：实际Mapper/Spring事务，权限RPC模拟边界，商家选择执行真实helper。 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_RETURN_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/trade_product.*")
class ProductUpgradeMysqlTest {
    private JdbcTemplate jdbc;private ProductService products;private ProductImageService images;private IdempotentWork work;
    private AuthorityClient authority;private ProductMapper mapper;private String unique;
    private final Jwt jwt=Jwt.withTokenValue("fixture").header("alg","RS256").subject("9001").build();
    @BeforeEach void setup()throws Exception {
        unique=UUID.randomUUID().toString().replace("-","");
        var data=new DriverManagerDataSource(System.getenv("TRADEFLOW_RETURN_MYSQL_URL"),"root","");jdbc=new JdbcTemplate(data);
        var manager=new DataSourceTransactionManager(data);
        var config=new org.apache.ibatis.session.Configuration(new Environment("product-upgrade",new SpringManagedTransactionFactory(),data));
        config.setMapUnderscoreToCamelCase(true);config.setArgNameBasedConstructorAutoMapping(true);
        for(Class<?> type:List.of(ProductMapper.class,ProductImageMapper.class)) {
            String resource="mapper/product/"+type.getSimpleName()+".xml";
            try(var input=Resources.getResourceAsStream(resource)){new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();}
        }
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));mapper=session.getMapper(ProductMapper.class);
        authority=mock(AuthorityClient.class);
        when(authority.merchant(any(),anyBoolean())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new AuthContext("9001","ACTIVE",List.of("MERCHANT_ADMIN"),List.of("merchant_second","merchant_other"),1,java.util.Map.of("merchant_second","ADMIN","merchant_other","ADMIN"));});
        when(authority.selectedMerchant(any())).thenCallRealMethod();
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return null;}).when(authority).requireActive(anyString());
        work=new IdempotentWork(mapper,new ObjectMapper(),manager);
        products=new ProductService(mapper,authority,work,manager,mock(CatalogCache.class));
        images=new ProductImageService(mapper,session.getMapper(ProductImageMapper.class),authority,work,manager);select("merchant_second");
    }
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
    private void select(String id){var request=new MockHttpServletRequest();request.addHeader("X-Merchant-Id",id);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
    private ProductWrite input(){return new ProductWrite("升级商品","实际事务",ProductStatus.ON_SALE,List.of(new WriteSku("FIRST-"+unique,"首规格",100L)));}
    private ProductView create(){return products.create(jwt,"create-"+unique,input(),"request","trace");}
    private MockMultipartFile picture()throws Exception {var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB),"png",output);return new MockMultipartFile("file","../unsafe.svg","image/svg+xml",output.toByteArray());}
    @Test void addSkuInitializesZeroAndReplaysOnceWhileDuplicateRollsBack() {
        var product=create();var input=new WriteSku("SECOND-"+unique,"第二规格",200L);
        var added=products.addSku(jwt,product.productId(),"add-"+unique,input,"request","trace");
        assertThat(added.availableQty()).isZero();assertThat(added.reservedQty()).isZero();
        assertThat(products.addSku(jwt,product.productId(),"add-"+unique,input,"request","trace")).isEqualTo(added);
        assertThat(mapper.skus(product.productId())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger WHERE sku_id=?",Long.class,added.skuId())).isZero();
        assertThatThrownBy(()->products.addSku(jwt,product.productId(),"duplicate-"+unique,input,"request","trace")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(mapper.skus(product.productId())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request WHERE idempotency_key=?",Long.class,"duplicate-"+unique)).isZero();
        select("merchant_other");assertThatThrownBy(()->products.addSku(jwt,product.productId(),"foreign-"+unique,new WriteSku("FOREIGN-"+unique,"外店",100L),"request","trace")).isInstanceOf(BusinessException.class);
        assertThat(mapper.skus(product.productId())).hasSize(2);
    }
    @Test void normalizedImageIsAtomicIdempotentBoundToStoreAndHiddenAfterOffSaleOrDelete()throws Exception {
        var product=create();var image=images.upload(jwt,product.productId(),"image-"+unique,picture(),"request","trace");
        assertThat(images.upload(jwt,product.productId(),"image-"+unique,picture(),"request","trace")).isEqualTo(image);
        assertThat(images.list(product.productId(),null,false)).hasSize(1);
        assertThat(images.content(image.imageNo())).startsWith((byte)137,(byte)80,(byte)78,(byte)71);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=?",Long.class,product.productId())).isEqualTo(1);
        select("merchant_other");assertThatThrownBy(()->images.merchantContent(jwt,image.imageNo())).isInstanceOf(BusinessException.class);
        select("merchant_second");products.edit(jwt,product.productId(),new ProductEdit(0L,"已下架","历史图片",ProductStatus.OFF_SALE),"request","trace");
        assertThatThrownBy(()->images.content(image.imageNo())).isInstanceOf(BusinessException.class);
        assertThat(images.merchantContent(jwt,image.imageNo())).isNotEmpty();
        images.remove(jwt,product.productId(),image.imageNo(),"request","trace");images.remove(jwt,product.productId(),image.imageNo(),"request","trace");
        assertThat(images.list(product.productId(),jwt,true)).isEmpty();
        assertThatThrownBy(()->images.merchantContent(jwt,image.imageNo())).isInstanceOf(BusinessException.class);
    }
    @Test void auditFailureRollsBackImageBlobAndOriginalKeyCanRecover()throws Exception {
        var product=create();var file=picture();String key="rollback-image-"+unique;
        jdbc.execute("CREATE TRIGGER fixture_upgrade_image_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture image audit failure'");
        try{assertThatThrownBy(()->images.upload(jwt,product.productId(),key,file,"request","trace")).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{jdbc.execute("DROP TRIGGER fixture_upgrade_image_audit");}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id=?",Long.class,product.productId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request WHERE idempotency_key=?",Long.class,key)).isZero();
        assertThat(images.upload(jwt,product.productId(),key,file,"request","trace").imageNo()).startsWith("img_");
    }
    @Test void legacySuccessReplaysOnlyForOriginalMerchantWithoutRewritingDigest() {
        var product=create();String oldHash=work.hash(input()),key="create-"+unique;
        jdbc.update("UPDATE idempotency_request SET request_hash=? WHERE actor_type='MERCHANT' AND actor_id='9001' AND operation='CREATE_PRODUCT' AND idempotency_key=?",oldHash,key);
        assertThat(products.create(jwt,key,input(),"request","trace")).isEqualTo(product);
        assertThat(jdbc.queryForObject("SELECT request_hash FROM idempotency_request WHERE idempotency_key=?",String.class,key)).isEqualTo(oldHash);
        select("merchant_other");assertThatThrownBy(()->products.create(jwt,key,input(),"request","trace"))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE merchant_id='merchant_other' AND name='升级商品'",Long.class)).isZero();
    }
}
