package com.tradeflow.user;

import com.tradeflow.user.infrastructure.*;
import java.lang.reflect.Method;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.junit.jupiter.api.Assertions.*;

/** XML映射离线回归，不连接用户库；登录限额与权限并发仍需MySQL集成验证。 */
class MapperXmlBindingTest {
    /** 手工Configuration显式加载XML，验证全部Mapper方法、命名参数和账号自增主键契约。 */
    @Test void allStatementsLoadAndBindNamedParameters() throws Exception {
        Configuration configuration = new Configuration();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/user/*.xml")) {
            try (var input = resource.getInputStream()) {
                new XMLMapperBuilder(input, configuration, resource.toString(), configuration.getSqlFragments()).parse();
            }
        }
        int count = 0;
        for (Class<?> mapper : List.of(AccountMapper.class, LoginEntryMapper.class, LoginRateMapper.class, UserWorkspaceMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                String id = mapper.getName() + "." + method.getName();
                assertTrue(configuration.hasStatement(id), id);
                Object[] arguments = Arrays.stream(method.getParameterTypes()).map(MapperXmlBindingTest::sample).toArray();
                Object parameters = new ParamNameResolver(configuration, method).getNamedParams(arguments);
                var sql = configuration.getMappedStatement(id).getBoundSql(parameters);
                assertFalse(sql.getSql().isBlank(), id);
                for (var mapping : sql.getParameterMappings()) {
                    Object value = configuration.newMetaObject(parameters).getValue(mapping.getProperty());
                    assertNotNull(value, id + ":" + mapping.getProperty());
                }
                count++;
            }
        }
        assertEquals(Arrays.stream(new Class<?>[]{AccountMapper.class,LoginEntryMapper.class,LoginRateMapper.class,UserWorkspaceMapper.class}).mapToInt(c -> c.getDeclaredMethods().length).sum(), count);
        assertEquals(count, configuration.getMappedStatementNames().stream().filter(id -> id.contains(".")).count());
        var insert = configuration.getMappedStatement(AccountMapper.class.getName() + ".insert");
        assertInstanceOf(Jdbc3KeyGenerator.class, insert.getKeyGenerator());
        assertArrayEquals(new String[]{"id"}, insert.getKeyProperties());
    }

    private static Object sample(Class<?> type) {
        if (type == String.class) return "sample";
        if (type == long.class) return 1L;
        if (type == int.class) return 1;
        if (type == Map.class) {
            Map<String,Object> values=new HashMap<>();
            for(String key : List.of("username","passwordHash","displayName","id","userId","merchantId","name","description","recipient","phone","region","address","defaultAddress","version")) values.put(key,"sample");
            return values;
        }
        throw new AssertionError(type);
    }
}
