package com.tradeflow.order;

import com.tradeflow.order.infrastructure.*;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.junit.jupiter.api.Assertions.*;

/** 仅验证原生MyBatis资源、构造器与参数绑定；不连接数据库，不声称验证事务或MySQL并发。 */
class MapperXmlBindingTest {
    /** 手工Configuration显式加载XML，避免仅addMapper遗漏外置SQL；遍历每个业务入口验证绑定契约。 */
    @Test void allStatementsLoadAndBindNamedParameters() throws Exception {
        Configuration configuration = load();
        int count = 0;
        for (Class<?> mapper : List.of(OrderMapper.class, TaskMapper.class, FulfillmentMapper.class, EntitlementMapper.class, OrderUpgradeMapper.class, OrderNotificationMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                String id = mapper.getName() + "." + method.getName();
                assertTrue(configuration.hasStatement(id), id);
                Object[] arguments = Arrays.stream(method.getParameterTypes()).map(MapperXmlBindingTest::sample).toArray();
                Object parameters = new ParamNameResolver(configuration, method).getNamedParams(arguments);
                BoundSql sql = configuration.getMappedStatement(id).getBoundSql(parameters);
                assertFalse(sql.getSql().isBlank(), id);
                for (var mapping : sql.getParameterMappings()) {
                    String property = mapping.getProperty();
                    Object value = sql.hasAdditionalParameter(property) ? sql.getAdditionalParameter(property)
                            : configuration.newMetaObject(parameters).getValue(property);
                    assertNotNull(value, id + ":" + property);
                }
                count++;
            }
        }
        assertTrue(count>111);
        assertEquals(count, configuration.getMappedStatementNames().stream().filter(id -> id.contains(".")).count());
    }

    /** 商品行自增主键必须写回原Map，逐件批量插入必须展开完整的购买件和成交金额。 */
    @Test void generatedKeysAndUnitForeachRemainAvailable() throws Exception {
        Configuration configuration = load();
        var item = configuration.getMappedStatement(OrderMapper.class.getName() + ".item");
        assertInstanceOf(Jdbc3KeyGenerator.class, item.getKeyGenerator());
        assertArrayEquals(new String[]{"id"}, item.getKeyProperties());
        var units = configuration.getMappedStatement(OrderMapper.class.getName() + ".units")
                .getBoundSql(Map.of("orderItemId", "1", "paidAmountCents", 100L, "units", List.of(1, 2)));
        assertEquals(6, units.getParameterMappings().size());
    }

    private static Configuration load() throws Exception {
        Configuration configuration = new Configuration();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/order/*.xml")) {
            try (var input = resource.getInputStream()) {
                new XMLMapperBuilder(input, configuration, resource.toString(), configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    private static Object sample(Class<?> type) {
        if (type == String.class) return "sample";
        if (type == long.class) return 1L;
        if (type == int.class) return 1;
        if (type == Instant.class) return Instant.EPOCH;
        if (type == List.class) return List.of(1, 2);
        if (type == Map.class) {
            Map<String,Object> row = new HashMap<>();
            for (String key : List.of("merchantId", "orderNo", "userId", "paymentNo", "reservationNo", "quoteNo", "totalAmountCents",
                    "payDeadline", "productId", "skuId", "productNameSnapshot", "specSnapshot", "unitPriceCents", "quantity", "lineAmountCents")) row.put(key, "sample");
            return row;
        }
        if (type.isRecord()) {
            var components = type.getRecordComponents();
            try {
                return type.getDeclaredConstructor(Arrays.stream(components).map(c -> c.getType()).toArray(Class<?>[]::new))
                        .newInstance(Arrays.stream(components).map(c -> sample(c.getType())).toArray());
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        throw new AssertionError(type);
    }
}
