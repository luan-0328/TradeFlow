package com.tradeflow.aftersale.infrastructure;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** 平台争议与待办仅查询售后本库，裁定和原售后任务必须同事务提交。 */
@Mapper
public interface GovernanceMapper {
    List<Map<String,Object>> tasks(@Param("size") int size,@Param("offset") long offset);
    long countTasks();
    Map<String,Object> dispute(@Param("no") String no);
    Map<String,Object> lockDispute(@Param("no") String no);
    int create(@Param("no") String no,@Param("aftersaleNo") String aftersaleNo,@Param("userId") String userId,@Param("key") String key,@Param("hash") String hash,@Param("reason") String reason);
    Map<String,Object> byKey(@Param("userId") String userId,@Param("key") String key);
    List<Map<String,Object>> disputes(@Param("userId") String userId,@Param("size") int size,@Param("offset") long offset);
    long countDisputes(@Param("userId") String userId);
    int decide(@Param("no") String no,@Param("version") long version,@Param("decision") String decision,@Param("reason") String reason,@Param("actor") String actor);
    int approve(@Param("no") String no);
    int reject(@Param("no") String no);
    int audit(@Param("actor") String actor,@Param("no") String no,@Param("operation") String operation,@Param("reason") String reason);
}
