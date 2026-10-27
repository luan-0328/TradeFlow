package com.tradeflow.user.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 唯一键原子更新在同一短事务内读取；所有用户实例共享数据库时间，不依赖进程计数或Redis。 */
@Mapper
public interface LoginEntryMapper {
    /** 按数据库时钟切窗；窗口编号单调，等待行锁跨窗的旧语句只能计入当前窗口，不能倒退重置。 */
    void reserve(@Param("attemptKey") String attemptKey, @Param("windowSeconds") int windowSeconds, @Param("maximumAttempts") int maximumAttempts);
    /** 本事务已通过UPSERT持有行锁；剩余时间向上取整，边界至少退避一秒。 */
    Window current(@Param("attemptKey") String attemptKey, @Param("windowSeconds") int windowSeconds);
    /** 成功只释放本次账号占用；窗口版本条件避免迟到成功清掉新窗口失败，其他并发失败永不删除。 */
    int release(@Param("attemptKey") String attemptKey, @Param("windowId") long windowId);
    /** 七天后的小批清理不改变活动窗口，任一实例可重复执行且无RPC。 */
    int cleanup();
    record Window(int attempts,long windowId,int retryAfterSeconds){}
}
