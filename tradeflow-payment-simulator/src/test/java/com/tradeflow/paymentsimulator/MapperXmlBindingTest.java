package com.tradeflow.paymentsimulator;

import com.tradeflow.paymentsimulator.infrastructure.*;
import com.tradeflow.paymentsimulator.domain.SimulatorRows;
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
        for (Class<?> mapper : List.of(SimulatorMapper.class, SimulatorRefundMapper.class, SimulatorTaskMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                String id = mapper.getName() + "." + method.getName();
                assertTrue(configuration.hasStatement(id), id);
                Object[] arguments = Arrays.stream(method.getParameterTypes()).map(MapperXmlBindingTest::sample).toArray();
                if (mapper == SimulatorTaskMapper.class) arguments[0] = "process_task";
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
        assertEquals(26, count);
        assertEquals(26, configuration.getMappedStatementNames().stream().filter(id -> id.contains(".")).count());
    }

    private static Configuration load() throws Exception {
        Configuration configuration = new Configuration();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/simulator/*.xml")) {
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

    /** 四个队列固定调用保持原表名范围；只生成SQL，不执行数据库访问或允许外部输入表名。 */
    @Test void fixedRecoveryQueuesGenerateBoundSql() throws Exception {
        Configuration configuration = load();
        for (String table : List.of("process_task", "callback_task", "refund_process_task", "refund_callback_task")) {
            for (Method method : SimulatorTaskMapper.class.getDeclaredMethods()) {
                Object[] arguments = Arrays.stream(method.getParameterTypes()).map(MapperXmlBindingTest::sample).toArray();
                arguments[0] = table;
                Object parameters = new ParamNameResolver(configuration, method).getNamedParams(arguments);
                BoundSql sql = configuration.getMappedStatement(SimulatorTaskMapper.class.getName() + "." + method.getName())
                        .getBoundSql(parameters);
                assertTrue(sql.getSql().matches("(?s).*\\b" + table + "\\b.*"), method.getName() + ":" + table);
                assertFalse(sql.getSql().contains("${"));
            }
        }
    }

    /** record显式按列绑定构造器，不依赖SELECT列顺序、全局驼峰或自动构造器推断。 */
    @Test void recordsUseExplicitConstructorMappings() throws Exception {
        Configuration configuration = load();
        assertConstructor(configuration, SimulatorMapper.class, "channelResult", SimulatorRows.Channel.class);
        assertConstructor(configuration, SimulatorMapper.class, "scenarioResult", SimulatorRows.Scenario.class);
        assertConstructor(configuration, SimulatorTaskMapper.class, "taskResult", SimulatorRows.Task.class);
        assertEquals("channelResult", configuration.getMappedStatement(SimulatorMapper.class.getName() + ".lock")
                .getResultMaps().getFirst().getId().substring(SimulatorMapper.class.getName().length() + 1));
        assertEquals("taskResult", configuration.getMappedStatement(SimulatorTaskMapper.class.getName() + ".fence")
                .getResultMaps().getFirst().getId().substring(SimulatorTaskMapper.class.getName().length() + 1));
    }

    private static void assertConstructor(Configuration configuration, Class<?> mapper, String id, Class<?> record) {
        var result = configuration.getResultMap(mapper.getName() + "." + id);
        assertEquals(record, result.getType());
        var mappings = result.getConstructorResultMappings();
        var components = record.getRecordComponents();
        assertEquals(components.length, mappings.size());
        for (int index = 0; index < components.length; index++) {
            var component = components[index];
            var mapping = mappings.get(index);
            assertEquals(component.getName(), mapping.getProperty());
            assertEquals(component.getType(), mapping.getJavaType());
            String column = component.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            assertEquals(column, mapping.getColumn());
        }
    }

}
