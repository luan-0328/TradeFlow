package com.tradeflow.payment;

import com.tradeflow.payment.infrastructure.*;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.junit.jupiter.api.Assertions.*;

/** 原生MyBatis离线契约验证；不连接数据库，不代表事务、并发或通道验证通过。 */
class MapperXmlBindingTest {
    /** 离线加载全部XML并逐入口解析参数，缺少statement、DTO属性或命名参数即失败。 */
    @Test void allStatementsLoadAndBindNamedParameters() throws Exception {
        Configuration configuration = load();
        int count = 0;
        for (Class<?> mapper : List.of(PaymentMapper.class, RefundMapper.class, SettlementFactsMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                String id = mapper.getName() + "." + method.getName();
                assertTrue(configuration.hasStatement(id), id);
                Object[] arguments = Arrays.stream(method.getParameterTypes()).map(MapperXmlBindingTest::sample).toArray();
                Object parameters = new ParamNameResolver(configuration, method).getNamedParams(arguments);
                BoundSql sql = configuration.getMappedStatement(id).getBoundSql(parameters);
                assertFalse(sql.getSql().isBlank(), id);
                assertFalse(sql.getSql().contains("${"), id);
                for (var mapping : sql.getParameterMappings()) {
                    String property = mapping.getProperty();
                    Object value = sql.hasAdditionalParameter(property) ? sql.getAdditionalParameter(property)
                            : configuration.newMetaObject(parameters).getValue(property);
                    assertNotNull(value, id + ":" + property);
                }
                count++;
            }
        }
        assertTrue(count>55);
        assertEquals(count, configuration.getMappedStatementNames().stream().filter(id -> id.contains(".")).count());
    }

    private static Configuration load() throws Exception {
        Configuration configuration = new Configuration();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/payment/*.xml")) {
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
