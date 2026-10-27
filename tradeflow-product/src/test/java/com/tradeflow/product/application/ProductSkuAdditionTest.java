package com.tradeflow.product.application;

import com.tradeflow.common.BusinessException;
import com.tradeflow.product.api.ProductModels.*;
import com.tradeflow.product.domain.ProductRows.*;
import com.tradeflow.product.infrastructure.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 追加SKU用例边界：验证新商家归属、零库存初始化和上限；数据库回滚仍须真实MySQL另行验证。 */
class ProductSkuAdditionTest {
    private ProductMapper mapper;private AuthorityClient authority;private ProductService products;private Jwt jwt;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        mapper=mock(ProductMapper.class);authority=mock(AuthorityClient.class);var idempotency=mock(IdempotentWork.class);
        when(authority.selectedMerchant(any())).thenReturn("merchant_second");
        when(idempotency.run(anyString(),anyString(),anyString(),anyString(),any(),eq(SkuView.class),any()))
                .thenAnswer(call->((Function<String,SkuView>)call.getArgument(6)).apply("req_test"));
        products=new ProductService(mapper,authority,idempotency,mock(PlatformTransactionManager.class),mock(CatalogCache.class));
        jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("1").build();
        when(mapper.lockProduct("9")).thenReturn(new ProductRow("9","merchant_second","商品","描述","DRAFT",0));
        when(mapper.skus("9")).thenReturn(List.of());
        when(mapper.createSku(anyMap())).thenAnswer(call->{Map<String,Object> value=call.getArgument(0);value.put("id",99L);return 1;});
        when(mapper.sku("99")).thenReturn(new SkuRow("99","9","BLUE","蓝色",100,0,"ACTIVE",0,"merchant_second","DRAFT"));
        when(mapper.inventory("warehouse_main","99")).thenReturn(new InventoryRow(0,0));
    }
    @Test void newSkuStartsAtZeroAndRecordsAuditForSelectedMerchant() {
        var result=products.addSku(jwt,"9","stable-key",new WriteSku("BLUE","蓝色",100L),"request","trace");
        assertThat(result.availableQty()).isZero();assertThat(result.reservedQty()).isZero();
        verify(authority).requireActive("merchant_second");verify(mapper).zeroInventory("99");
        verify(mapper).audit(eq("1"),eq("ADD_SKU"),eq("9"),eq("request"),eq("trace"),anyString(),anyString());
    }
    @Test void foreignProductCannotCreateSkuOrInventory() {
        when(mapper.lockProduct("9")).thenReturn(new ProductRow("9","merchant_other","商品","描述","DRAFT",0));
        assertThatThrownBy(()->products.addSku(jwt,"9","stable-key",new WriteSku("BLUE","蓝色",100L),"request","trace")).isInstanceOf(BusinessException.class);
        verify(mapper,never()).createSku(anyMap());verify(mapper,never()).zeroInventory(anyString());
    }
    @Test void disabledSkuStillCountsAgainstProductLimit() {
        var row=new SkuRow("1","9","OLD","旧规格",100,0,"DISABLED",0,"merchant_second","DRAFT");when(mapper.skus("9")).thenReturn(Collections.nCopies(100,row));
        assertThatThrownBy(()->products.addSku(jwt,"9","stable-key",new WriteSku("BLUE","蓝色",100L),"request","trace"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo("SKU_LIMIT"));
        verify(mapper,never()).createSku(anyMap());
    }
}
