-- 交易提交后由不可变状态日志恢复通知；不改变订单/资金原事务。
CREATE TABLE order_notification_task (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部任务主键',
 source_log_id BIGINT UNSIGNED NOT NULL COMMENT '原订单状态日志ID，通知事实唯一来源',
 business_key VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原日志派生稳定通知键，未知结果重试不换号',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原订单号，不保存收货隐私',
 user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单权威买家，创建任务时冻结归属',
 to_status VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原日志目标状态快照',
 state ENUM('READY','SUCCEEDED','MANUAL_REVIEW') NOT NULL DEFAULT 'READY' COMMENT '待投递、已确认或人工处理',
 attempt_count INT NOT NULL DEFAULT 0 COMMENT '累计领取次数，宕机也计入恢复预算',
 claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '递增领取版本，阻止旧执行者回写',
 lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前领取实例执行者',
 lease_until DATETIME(3) NULL COMMENT '租约截止UTC，过期只能重新领取',
 next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次重试UTC，失败指数退避',
 last_error VARCHAR(1000) NULL COMMENT '最近失败类别，不保存服务凭据或用户地址',
 delivered_at DATETIME(3) NULL COMMENT '用户服务已持久ACK的时间UTC',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '任务创建时间UTC',
 PRIMARY KEY(id),
 UNIQUE KEY uk_order_notification_source(source_log_id),
 UNIQUE KEY uk_order_notification_business(business_key),
 KEY ix_order_notification_due(state,next_retry_at,lease_until,id),
 CHECK(attempt_count>=0 AND claim_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='从订单状态事实恢复的可靠站内通知投递任务';
