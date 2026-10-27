package com.tradeflow.user.infrastructure;
import java.util.*;
import org.apache.ibatis.annotations.*;
/** 用户扩展数据访问；归属、版本、幂等和并发围栏在SQL中保留，必须由应用本地短事务调用。 */
@Mapper
public interface UserWorkspaceMapper {
    /** 平台商家分页，停用历史商家仍可检索。 */
    List<Map<String,Object>> allMerchants(@Param("size") int size,@Param("offset") long offset);
    /** 平台商家总量，与分页条件相同。 */
    long merchantCount();
    /** 脱敏审计分页按业务号或操作者过滤，不暴露密码材料。 */
    List<Map<String,Object>> audits(@Param("actorId") String actorId,@Param("businessNo") String businessNo,@Param("size") int size,@Param("offset") long offset);
    /** 审计总量用于完整遍历，不能用固定100条截断代替分页。 */
    long auditCount(@Param("actorId") String actorId,@Param("businessNo") String businessNo);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    List<Map<String,Object>> addresses(@Param("userId") String userId);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    Map<String,Object> address(@Param("userId") String userId,@Param("id") String id);
    /** 账号行锁保护默认地址切换；只递增实际被清除行的版本。 */
    int clearDefault(@Param("userId") String userId,@Param("id") String id);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    int insertAddress(Map<String,Object> p);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    int updateAddress(Map<String,Object> p);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    int deleteAddress(@Param("userId") String userId,@Param("id") String id,@Param("version") long version);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    List<Map<String,Object>> notifications(@Param("userId") String userId,@Param("size") int size,@Param("offset") long offset);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    long notificationCount(@Param("userId") String userId);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    int readNotification(@Param("userId") String userId,@Param("id") String id);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    long notificationExists(@Param("userId") String userId,@Param("id") String id);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    int notifyUser(@Param("userId") String userId,@Param("key") String key,@Param("title") String title,@Param("content") String content);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    Map<String,Object> merchant(@Param("id") String id);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    Map<String,Object> lockMerchant(@Param("id") String id);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    int insertMerchant(Map<String,Object> p);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    int profile(@Param("id") String id,@Param("name") String name,@Param("description") String description,@Param("version") long version);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    int merchantStatus(@Param("id") String id,@Param("status") String status,@Param("version") long version);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    List<Map<String,Object>> members(@Param("id") String id);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    int apply(Map<String,Object> p);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    Map<String,Object> application(@Param("id") String id);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    List<Map<String,Object>> applications(@Param("userId") String userId,@Param("size") int size,@Param("offset") long offset);
    /** 按归属和状态读取或条件写入，事务失败由调用用例整体回滚。 */
    long applicationCount(@Param("userId") String userId);
    /** 待审状态与版本双围栏，审核与建商家、成员、通知一起提交。 */
    int review(@Param("id") String id,@Param("status") String status,@Param("reason") String reason,@Param("version") long version);
}
