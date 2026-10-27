-- B4追加独立通道退款，成功与未决预算由原支付行串行。
SET NAMES utf8mb4;
ALTER TABLE channel_transaction
 ADD reserved_refund_cents BIGINT NOT NULL DEFAULT 0 COMMENT '未决通道退款占用，分',
 ADD succeeded_refund_cents BIGINT NOT NULL DEFAULT 0 COMMENT '已成功通道退款金额，分',
 ADD CONSTRAINT ck_channel_refund_budget CHECK(reserved_refund_cents>=0 AND succeeded_refund_cents>=0 AND reserved_refund_cents+succeeded_refund_cents<=amount_cents);
CREATE TABLE channel_refund (
 refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定退款执行编号',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一绑定成功原支付号',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一绑定原订单编号',
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变退款来源编号',
 amount_cents BIGINT NOT NULL COMMENT '本次退款金额，分',
 currency CHAR(3) CHARACTER SET ascii NOT NULL COMMENT '币种固定CNY',
 status ENUM('PROCESSING','SUCCEEDED','FAILED') NOT NULL COMMENT '退款通道事实，终态不可反转',
 channel_refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '通道独立退款交易号',
 channel_version BIGINT NOT NULL DEFAULT 1 COMMENT '单调递增退款事实版本',
 succeeded_at DATETIME(3) NULL COMMENT '成功发生时间UTC',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后事实时间UTC',
 PRIMARY KEY(refund_no),UNIQUE KEY uk_channel_refund_no(channel_refund_no),KEY ix_channel_refund_payment(payment_no),
 CONSTRAINT fk_channel_refund_payment FOREIGN KEY(payment_no) REFERENCES channel_transaction(payment_no),
 CONSTRAINT ck_channel_refund_amount CHECK(amount_cents>0 AND currency='CNY'),
 CONSTRAINT ck_channel_refund_success CHECK((status='SUCCEEDED' AND succeeded_at IS NOT NULL) OR (status<>'SUCCEEDED' AND succeeded_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立通道退款权威事实及不可变原支付绑定';
CREATE TABLE refund_fault_scenario (
 refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一次固定场景的退款号',
 mode ENUM('SUCCESS','REJECT','PROCESSING','RESPONSE_TIMEOUT','CALLBACK_LOST','CALLBACK_DUPLICATE') NOT NULL COMMENT '独立退款故障场景',
 actor_id VARCHAR(64) NOT NULL COMMENT '配置人或默认SYSTEM',
 reason VARCHAR(500) NOT NULL COMMENT '配置原因',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按退款号一次绑定的场景，与支付场景独立';
CREATE TABLE refund_process_task (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 task_no VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定任务编号，回调任务即稳定通知编号',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务业务编号，本表为退款号，复用统一租约记录字段名',
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
 PRIMARY KEY(id),UNIQUE KEY uk_refund_process_task_no(task_no),KEY idx_refund_process_task_ready(state,next_retry_at,id),KEY idx_refund_process_task_lease(state,lease_until,id),
 CONSTRAINT ck_refund_process_task_counts CHECK(attempt_count>=0 AND claim_version>=0 AND deliveries_required BETWEEN 1 AND 2 AND deliveries_done>=0 AND deliveries_done<=deliveries_required)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立退款持久任务，旧租约不可回写';
CREATE TABLE refund_callback_task (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 task_no VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定任务编号，回调任务即稳定通知编号',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务业务编号，本表为退款号，复用统一租约记录字段名',
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
 PRIMARY KEY(id),UNIQUE KEY uk_refund_callback_task_no(task_no),KEY idx_refund_callback_task_ready(state,next_retry_at,id),KEY idx_refund_callback_task_lease(state,lease_until,id),
 CONSTRAINT ck_refund_callback_task_counts CHECK(attempt_count>=0 AND claim_version>=0 AND deliveries_required BETWEEN 1 AND 2 AND deliveries_done>=0 AND deliveries_done<=deliveries_required)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立退款持久任务，旧租约不可回写';
