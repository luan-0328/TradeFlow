-- B3全新模拟器库基线；完整目标模型归档于docs/database-target，不在本批伪造退款或账单能力。
SET NAMES utf8mb4;
SET time_zone = '+00:00';
CREATE TABLE channel_transaction (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单预先分配的稳定支付号及关闭屏障编号',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定的权威订单编号',
 amount_cents BIGINT NOT NULL COMMENT '绑定的支付金额，正整数分',
 currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付币种，固定人民币CNY',
 status ENUM('PENDING','PROCESSING','SUCCEEDED','FAILED','CLOSED') NOT NULL COMMENT '支付事实状态，终态不可反转',
 channel_transaction_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本模拟通道的唯一交易号',
 channel_version BIGINT NOT NULL DEFAULT 0 COMMENT '同号事实单调递增版本',
 succeeded_at DATETIME(3) NULL COMMENT '通道支付成功时间，UTC',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次创建时间，UTC',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '通道事实最后更新时间，UTC',
 PRIMARY KEY(id),UNIQUE KEY uk_channel_payment(payment_no),UNIQUE KEY uk_channel_order(order_no),UNIQUE KEY uk_channel_no(channel_transaction_no),
 CONSTRAINT ck_channel_amount CHECK(amount_cents>0),CONSTRAINT ck_channel_currency CHECK(currency='CNY'),
 CONSTRAINT ck_channel_success CHECK((status='SUCCEEDED' AND succeeded_at IS NOT NULL) OR (status<>'SUCCEEDED' AND succeeded_at IS NULL)),
 CONSTRAINT ck_channel_version CHECK(channel_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立模拟支付事实，关闭先到也保留完整绑定墓碑';
CREATE TABLE fault_scenario (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一次绑定的稳定支付编号',
 mode ENUM('SUCCESS','REJECT','PROCESSING','RESPONSE_TIMEOUT','CALLBACK_LOST','CALLBACK_DUPLICATE') NOT NULL COMMENT '确定性故障模式，支付首次受理后不可变',
 actor_id VARCHAR(64) NOT NULL COMMENT '配置操作人标识或默认SYSTEM',
 reason VARCHAR(500) NOT NULL COMMENT '场景配置原因',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '配置时间，UTC',
 PRIMARY KEY(id),UNIQUE KEY uk_scenario_payment(payment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按支付号一次固定的开发故障场景，无全局随机开关';
CREATE TABLE process_task (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 task_no VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定任务编号，回调任务即稳定通知编号',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定的支付编号',
 payload MEDIUMTEXT NULL COMMENT '首次创建冻结的UTF8回调JSON，支付推进任务为空',
 state ENUM('READY','RUNNING','SUCCEEDED','MANUAL_REVIEW') NOT NULL DEFAULT 'READY' COMMENT '持久任务状态，超限保留人工处理',
 attempt_count INT NOT NULL DEFAULT 0 COMMENT '累计领取次数，最多自动尝试三十次',
 claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增的执行者栅栏版本',
 lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前领取进程随机标识',
 lease_until DATETIME(3) NULL COMMENT '领取租约到期时间，UTC',
 next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最早下次执行时间，UTC',
 deliveries_required INT NOT NULL DEFAULT 1 COMMENT '要求确认次数，重复回调模式为二',
 deliveries_done INT NOT NULL DEFAULT 0 COMMENT '收到合法业务ACK的确认次数',
 last_error VARCHAR(100) NULL COMMENT '脱敏稳定错误码，禁止存储凭据或签名',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '任务创建时间，UTC',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '任务最后更新时间，UTC',
 PRIMARY KEY(id),UNIQUE KEY uk_process_task_no(task_no),KEY idx_process_task_ready(state,next_retry_at,id),KEY idx_process_task_lease(state,lease_until,id),
 CONSTRAINT ck_process_task_counts CHECK(attempt_count>=0 AND claim_version>=0 AND deliveries_required BETWEEN 1 AND 2 AND deliveries_done>=0 AND deliveries_done<=deliveries_required)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步支付推进持久任务，关闭墓碑优先且旧租约不可回写';
CREATE TABLE callback_task (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 task_no VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定任务编号，回调任务即稳定通知编号',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定的支付编号',
 payload MEDIUMTEXT NULL COMMENT '首次创建冻结的UTF8回调JSON，支付推进任务为空',
 state ENUM('READY','RUNNING','SUCCEEDED','MANUAL_REVIEW') NOT NULL DEFAULT 'READY' COMMENT '持久任务状态，超限保留人工处理',
 attempt_count INT NOT NULL DEFAULT 0 COMMENT '累计领取次数，最多自动尝试三十次',
 claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增的执行者栅栏版本',
 lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前领取进程随机标识',
 lease_until DATETIME(3) NULL COMMENT '领取租约到期时间，UTC',
 next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最早下次执行时间，UTC',
 deliveries_required INT NOT NULL DEFAULT 1 COMMENT '要求确认次数，重复回调模式为二',
 deliveries_done INT NOT NULL DEFAULT 0 COMMENT '收到合法业务ACK的确认次数',
 last_error VARCHAR(100) NULL COMMENT '脱敏稳定错误码，禁止存储凭据或签名',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '任务创建时间，UTC',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '任务最后更新时间，UTC',
 PRIMARY KEY(id),UNIQUE KEY uk_callback_task_no(task_no),KEY idx_callback_task_ready(state,next_retry_at,id),KEY idx_callback_task_lease(state,lease_until,id),
 CONSTRAINT ck_callback_task_counts CHECK(attempt_count>=0 AND claim_version>=0 AND deliveries_required BETWEEN 1 AND 2 AND deliveries_done>=0 AND deliveries_done<=deliveries_required)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定通知号和不可变原始JSON回调任务，业务确认后完成';
CREATE TABLE audit_log (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '追加审计主键',
 actor_id VARCHAR(64) NOT NULL COMMENT '操作人或内部服务标识',
 action VARCHAR(64) NOT NULL COMMENT '支付受理、关闭、异步成功或场景配置动作',
 business_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联的稳定支付编号',
 request_id VARCHAR(64) NULL COMMENT '场景管理请求追踪编号',
 reason VARCHAR(500) NOT NULL COMMENT '业务目的或人工操作原因',
 result VARCHAR(64) NOT NULL COMMENT '操作结果或场景模式',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审计生成时间，UTC',
 PRIMARY KEY(id),KEY idx_simulator_audit_business(business_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付模拟器只追加操作证据，禁止运行账号修改和删除';
