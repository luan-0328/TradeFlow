package com.tradeflow.order.infrastructure;
import java.util.Map;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 通知持久边界只读取订单/日志，写独立任务；不能把投递失败回滚到原交易。 */
@Mapper public interface OrderNotificationMapper {
 /** 不使用永久自增水位，反连接全量扫描尚未建任务的原日志，唯一约束处理多实例竞争。 */
 int materialize();
 /** 平台分页只读真实任务；ID以字符串输出避免浏览器整数精度丢失。 */
 List<Map<String,Object>> page(@Param("size")int size,@Param("offset")long offset);
 long count();
 /** 人工重试持任务行锁重核状态，不修改原日志或通知业务键。 */
 Map<String,Object> lock(@Param("id")long id);
 /** 仅人工状态可重启，递增版本撤销旧执行者，保持原任务和原通知键。 */
 int restart(@Param("id")long id);
 /** 短事务跳过其他实例持锁任务，过期租约允许重新领取，人工状态不自动重试。 */
 Map<String,Object> due();
 /** 持任务行锁后递增版本和次数，固定60秒租约，领取后必须提交再做RPC。 */
 int claim(@Param("id")long id,@Param("owner")String owner);
 /** 仅有效owner+版本围栏可确认ACK或安排退避；旧请求返回不能修改新租约。 */
 int finish(@Param("id")long id,@Param("owner")String owner,@Param("version")long version,@Param("state")String state,@Param("error")String error);
}
