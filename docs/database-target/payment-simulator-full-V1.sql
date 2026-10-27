-- 仅连接到 trade_payment_simulator 执行；Flyway 默认 schema=trade_payment_simulator。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `channel_transaction` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商户支付编号，关闭先到也保留',
  `channel_transaction_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '通道交易号',
  `amount_cents` BIGINT NOT NULL COMMENT '权威支付金额，分',
  `currency` CHAR(3) CHARACTER SET ascii NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `status` ENUM('PENDING','PROCESSING','SUCCEEDED','FAILED','CLOSED') NOT NULL DEFAULT 'PENDING' COMMENT '通道支付状态',
  `close_intent` ENUM('NO','YES') NOT NULL DEFAULT 'NO' COMMENT '关闭意图，阻止迟到支付',
  `reserved_refund_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '通道未决退款占用，分',
  `succeeded_refund_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '通道累计成功退款，分',
  `scenario_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '已绑定故障场景',
  `succeeded_at` DATETIME(3) NULL COMMENT '支付成功时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_transaction_1` (`payment_no`),
  UNIQUE KEY `uk_channel_transaction_2` (`channel_transaction_no`),
  KEY `ix_channel_transaction_1` (`updated_at`,`id`),
  CONSTRAINT `ck_channel_transaction_1` CHECK (amount_cents > 0),
  CONSTRAINT `ck_channel_transaction_2` CHECK (currency = 'CNY'),
  CONSTRAINT `ck_channel_transaction_3` CHECK (reserved_refund_cents >= 0),
  CONSTRAINT `ck_channel_transaction_4` CHECK (succeeded_refund_cents >= 0),
  CONSTRAINT `ck_channel_transaction_5` CHECK (reserved_refund_cents + succeeded_refund_cents <= amount_cents)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立模拟通道支付及关闭墓碑';

CREATE TABLE `channel_refund` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `refund_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商户退款编号',
  `channel_refund_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '通道退款编号',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付编号',
  `amount_cents` BIGINT NOT NULL COMMENT '申请退款金额，分',
  `status` ENUM('PROCESSING','SUCCEEDED','FAILED') NOT NULL DEFAULT 'PROCESSING' COMMENT '通道退款状态',
  `scenario_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '绑定故障场景',
  `succeeded_at` DATETIME(3) NULL COMMENT '退款成功时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_refund_1` (`refund_no`),
  UNIQUE KEY `uk_channel_refund_2` (`channel_refund_no`),
  KEY `ix_channel_refund_1` (`updated_at`,`id`),
  CONSTRAINT `ck_channel_refund_1` CHECK (amount_cents > 0),
  CONSTRAINT `fk_channel_refund_1` FOREIGN KEY (`payment_no`) REFERENCES `channel_transaction` (`payment_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立模拟通道逐笔退款事实';

CREATE TABLE `fault_scenario` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `scenario_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '故障场景编号',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标支付或退款编号',
  `scenario_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '响应丢失、丢回调、重复回调等模式',
  `delay_ms` INT NOT NULL DEFAULT 0 COMMENT '延迟毫秒',
  `remaining_uses` INT NOT NULL DEFAULT 1 COMMENT '剩余触发次数',
  `random_seed` BIGINT NOT NULL DEFAULT 0 COMMENT '重复测试随机种子',
  `enabled` ENUM('NO','YES') NOT NULL DEFAULT 'NO' COMMENT '场景是否启用',
  `parameters` JSON NOT NULL COMMENT '可审计场景参数',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fault_scenario_1` (`scenario_id`),
  KEY `ix_fault_scenario_1` (`business_no`,`enabled`,`id`),
  CONSTRAINT `ck_fault_scenario_1` CHECK (delay_ms >= 0),
  CONSTRAINT `ck_fault_scenario_2` CHECK (remaining_uses >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仅测试环境启用的确定性故障场景';

CREATE TABLE `callback_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `notification_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定通知编号，重试保持不变',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付或退款编号',
  `notification_type` ENUM('PAYMENT','REFUND') NOT NULL DEFAULT 'PAYMENT' COMMENT '回调类型',
  `payload` JSON NOT NULL COMMENT '需签名的回调业务载荷',
  `status` ENUM('READY','RUNNING','SENT','MANUAL_REVIEW') NOT NULL DEFAULT 'READY' COMMENT '回调任务状态',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '回调尝试次数',
  `next_retry_at` DATETIME(3) NOT NULL COMMENT '下次发送时间',
  `lease_owner` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '领取者',
  `lease_until` DATETIME(3) NULL COMMENT '租约截止时间',
  `claim_version` BIGINT NOT NULL DEFAULT 0 COMMENT '领取版本，防止旧执行者覆盖',
  `last_error` VARCHAR(1000) NULL COMMENT '最近回调错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_callback_task_1` (`notification_no`),
  KEY `ix_callback_task_1` (`status`,`next_retry_at`,`id`),
  KEY `ix_callback_task_2` (`lease_until`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立通道持久化回调任务';

CREATE TABLE `channel_bill` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `snapshot_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '快照编号',
  `range_start` DATETIME(3) NOT NULL COMMENT 'UTC 账期起点，包含',
  `range_end` DATETIME(3) NOT NULL COMMENT 'UTC 账期终点，不含',
  `cutoff_at` DATETIME(3) NOT NULL COMMENT '快照冻结时间',
  `status` ENUM('BUILDING','COMPLETE','FAILED') NOT NULL DEFAULT 'BUILDING' COMMENT '账单生成完整性状态',
  `record_count` BIGINT NOT NULL DEFAULT 0 COMMENT '总记录数',
  `payment_total_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '支付金额汇总，分',
  `refund_total_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '退款金额汇总，分',
  `checksum` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '按固定排序计算的明细 SHA-256',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_bill_1` (`snapshot_no`),
  CONSTRAINT `ck_channel_bill_1` CHECK (range_start < range_end),
  CONSTRAINT `ck_channel_bill_2` CHECK (record_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冻结的通道账单快照元数据';

CREATE TABLE `channel_bill_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `snapshot_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '快照编号',
  `transaction_type` ENUM('PAYMENT','REFUND') NOT NULL DEFAULT 'PAYMENT' COMMENT '交易类型',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '交易业务编号',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付编号',
  `amount_cents` BIGINT NOT NULL COMMENT '金额，分',
  `transaction_status` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '快照时的权威终态',
  `channel_updated_at` DATETIME(3) NOT NULL COMMENT '终态事实更新时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_bill_item_1` (`snapshot_no`,`transaction_type`,`business_no`),
  KEY `ix_channel_bill_item_1` (`snapshot_no`,`id`),
  CONSTRAINT `ck_channel_bill_item_1` CHECK (amount_cents > 0),
  CONSTRAINT `fk_channel_bill_item_1` FOREIGN KEY (`snapshot_no`) REFERENCES `channel_bill` (`snapshot_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账单冻结明细，不从变化中的表实时分页';

CREATE TABLE `idempotency_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `actor_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户、商家、平台或服务',
  `actor_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '调用主体编号',
  `operation` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作编码',
  `idempotency_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端或调用方稳定幂等键',
  `request_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求 SHA-256',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对应业务编号',
  `state` ENUM('PROCESSING','SUCCEEDED','REJECTED') NOT NULL DEFAULT 'PROCESSING' COMMENT '业务请求处理状态',
  `result_code` VARCHAR(64) NULL COMMENT '结果码',
  `result_snapshot` JSON NULL COMMENT '可安全返回的结果快照',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idempotency_request_1` (`actor_type`,`actor_id`,`operation`,`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='请求幂等及同键异参检测';

CREATE TABLE `process_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `task_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务编号',
  `task_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务类型',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务编号',
  `step` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '流程步骤',
  `payload` JSON NOT NULL COMMENT '恢复所需业务参数，不含密钥',
  `state` ENUM('READY','RUNNING','SUCCEEDED','MANUAL_REVIEW','CANCELLED') NOT NULL DEFAULT 'READY' COMMENT '任务状态',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '执行次数',
  `next_retry_at` DATETIME(3) NOT NULL COMMENT '下次执行时间',
  `lease_owner` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '任务领取者',
  `lease_until` DATETIME(3) NULL COMMENT '租约截止',
  `claim_version` BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增的令牌',
  `last_error` VARCHAR(1000) NULL COMMENT '脱敏失败原因',
  `manual_review_at` DATETIME(3) NULL COMMENT '升级人工时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_process_task_1` (`task_no`),
  UNIQUE KEY `uk_process_task_2` (`task_type`,`business_no`,`step`),
  KEY `ix_process_task_1` (`state`,`next_retry_at`,`id`),
  KEY `ix_process_task_2` (`lease_until`,`id`),
  CONSTRAINT `ck_process_task_1` CHECK (attempt_count >= 0),
  CONSTRAINT `ck_process_task_2` CHECK (claim_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可抢占可恢复的本地业务任务';

CREATE TABLE `audit_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `actor_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作者类型',
  `actor_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作者编号',
  `action` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作编码',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '相关业务编号',
  `request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求编号',
  `trace_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '追踪编号',
  `reason` VARCHAR(1000) NOT NULL COMMENT '操作理由',
  `change_summary` JSON NOT NULL COMMENT '脱敏前后摘要',
  `result` ENUM('SUCCEEDED','FAILED') NOT NULL DEFAULT 'SUCCEEDED' COMMENT '操作结果',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  KEY `ix_audit_log_1` (`business_no`,`created_at`,`id`),
  KEY `ix_audit_log_2` (`actor_id`,`created_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变敏感操作审计';
