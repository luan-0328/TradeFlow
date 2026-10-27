package com.tradeflow.user.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 登录限额独立短事务持久化；进程重启不会重置计数。 */
@Mapper
public interface LoginRateMapper {
    /** 唯一键串行化同账号尝试；过窗原子重置，先更新次数再更新时间。 */
    void attempt(@Param("attemptKey") String attemptKey);
    /** 同事务读取已被本次UPDATE锁定的计数；超过上限仍提交限额记录。 */
    int attempts(@Param("attemptKey") String attemptKey);
    /** 仅清理七天前的过期限额，不改变当前窗口；按批执行避免长事务。 */
    int cleanup();
}
