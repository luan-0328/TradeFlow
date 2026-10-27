package com.tradeflow.product;

import com.tradeflow.product.infrastructure.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 无需数据库验证XML资源注册、不可变record构造及生成主键，防止迁移后首次业务调用才暴露配置错误。 */
class ProductMapperXmlTest {
    private Configuration configuration() throws Exception {
        var configuration = new Configuration();
        for (Class<?> mapper : List.of(ProductMapper.class, ReservationMapper.class, RestockMapper.class,
                ReturnRestockMapper.class, InventoryConditionMapper.class, ProductImageMapper.class)) {
            String resource = "mapper/product/" + mapper.getSimpleName() + ".xml";
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    @Test void everyMapperMethodHasXmlStatementAndRecordConstructorUsesDeclaredOrder() throws Exception {
        var configuration = configuration();
        for (Class<?> mapper : List.of(ProductMapper.class, ReservationMapper.class, RestockMapper.class,
                ReturnRestockMapper.class, InventoryConditionMapper.class, ProductImageMapper.class)) {
            for (var method : mapper.getDeclaredMethods()) {
                assertThat(configuration.hasStatement(mapper.getName() + "." + method.getName())).isTrue();
            }
        }
        // MyBatis为短名冲突存放Ambiguity哨兵，只遍历完整namespace键。
        for (String name : configuration.getResultMapNames().stream().filter(value -> value.contains(".")).toList()) {
            var resultMap=configuration.getResultMap(name);
            if (!resultMap.getType().isRecord()) continue;
            var expected = Arrays.stream(resultMap.getType().getRecordComponents())
                    .map(component -> component.getType()).toList();
            var actual = resultMap.getConstructorResultMappings().stream()
                    .map(mapping -> mapping.getJavaType()).toList();
            assertThat(actual).isEqualTo(expected);
        }
    }

    /** 店铺过滤不会通过OR分支展示草稿，关键词百分号按普通文本匹配，图片按单行Map读取，避免byte[]被Mapper当作多行数组。 */
    @Test void publicStoreFiltersAlwaysRequireOnSaleAndImageIsReadAsSingleRow() throws Exception {
        var config=configuration();String prefix=ProductMapper.class.getName()+".";
        String sql=config.getMappedStatement(prefix+"searchProducts").getBoundSql(Map.of("merchantId","merchant_second","merchant",false,"keyword","%","size",20,"offset",0)).getSql();
        assertThat(sql).contains("status='ON_SALE'","merchant_id=?","LOCATE").doesNotContain("LIKE");
        String count=config.getMappedStatement(prefix+"countSearchProducts").getBoundSql(Map.of("merchantId","merchant_second","merchant",false,"keyword","%","size",20,"offset",0)).getSql();
        assertThat(count).contains("status='ON_SALE'","merchant_id=?","LOCATE");
        var image=config.getMappedStatement(ProductImageMapper.class.getName()+".content");
        assertThat(java.util.Map.class.isAssignableFrom(image.getResultMaps().getFirst().getType())).isTrue();
        assertThat(image.getBoundSql(Map.of("imageNo","img_"+"a".repeat(32))).getSql()).contains("p.status='ON_SALE'","p.merchant_id=i.merchant_id");
    }

    @Test void productCreationUsesColumnNamesAndRetainsGeneratedKey() throws Exception {
        var configuration = configuration();
        String prefix = ProductMapper.class.getName() + ".";
        var product = configuration.getMappedStatement(prefix + "createProduct");
        var sku = configuration.getMappedStatement(prefix + "createSku");
        assertThat(product.getKeyProperties()).containsExactly("id");
        assertThat(sku.getKeyProperties()).containsExactly("id");
        assertThat(product.getBoundSql(Map.of("merchantId", "merchant_demo", "name", "商品", "description", "说明", "status", "DRAFT"))
                .getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("merchantId", "name", "description", "status");
        assertThat(sku.getBoundSql(Map.of("productId", "1", "skuCode", "TEST", "specification", "规格", "priceCents", 100L))
                .getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("productId", "skuCode", "specification", "priceCents");
        assertThat(configuration.getMappedStatement(prefix + "quoteSku").getBoundSql(Map.of("id", "1")).getSql())
                .endsWith("FOR SHARE");
        assertThat(configuration.getMappedStatement(prefix + "lockInventory")
                .getBoundSql(Map.of("warehouseId", "warehouse_main", "skuId", "1")).getSql()).endsWith("FOR UPDATE");
    }
}
