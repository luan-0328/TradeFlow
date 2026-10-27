-- B3 首次部署，仅支付事实、额度账户、恢复与通知所需表；完整目标见 docs/database-target/payment-full-V1.sql。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `payment_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付编号',
  `order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号',
  `user_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '买家编号',
  `amount_cents` BIGINT NOT NULL COMMENT '实付金额，分',
  `currency` CHAR(3) CHARACTER SET ascii NOT NULL DEFAULT 'CNY' COMMENT '支付币种',
  `status` ENUM('PENDING','PROCESSING','UNKNOWN','SUCCEEDED','FAILED','CLOSED') NOT NULL DEFAULT 'PENDING' COMMENT '支付事实状态',
  `close_intent` ENUM('NO','YES') NOT NULL DEFAULT 'NO' COMMENT '是否持久化关闭意图，阻止新提交',
  `channel_transaction_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '通道交易号',
  `channel_version` BIGINT NOT NULL DEFAULT 0 COMMENT '最近已确认通道版本',
  `succeeded_at` DATETIME(3) NULL COMMENT '通道成功时间',
  `channel_updated_at` DATETIME(3) NULL COMMENT '通道事实最后更新时间',
  `last_error` VARCHAR(1000) NULL COMMENT '最近错误摘要',
  `review_required` ENUM('NO','YES') NOT NULL DEFAULT 'NO' COMMENT '通道事实矛盾后阻断自动推进，等待人工核验',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_order_1` (`payment_no`),
  UNIQUE KEY `uk_payment_order_2` (`order_no`),
  UNIQUE KEY `uk_payment_order_3` (`channel_transaction_no`),
  KEY `ix_payment_order_1` (`status`,`updated_at`,`id`),
  CONSTRAINT `ck_payment_order_1` CHECK (amount_cents > 0),
  CONSTRAINT `ck_payment_order_2` CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付意图与通道关闭屏障';

CREATE TABLE `refund_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付编号',
  `paid_amount_cents` BIGINT NOT NULL COMMENT '成功支付金额，分',
  `reserved_refund_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '已创建未决退款占用金额，分',
  `succeeded_refund_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '累计成功退款金额，分',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_account_1` (`payment_no`),
  CONSTRAINT `ck_refund_account_1` CHECK (paid_amount_cents > 0),
  CONSTRAINT `ck_refund_account_2` CHECK (reserved_refund_cents >= 0),
  CONSTRAINT `ck_refund_account_3` CHECK (succeeded_refund_cents >= 0),
  CONSTRAINT `ck_refund_account_4` CHECK (reserved_refund_cents + succeeded_refund_cents <= paid_amount_cents),
  CONSTRAINT `fk_refund_account_1` FOREIGN KEY (`payment_no`) REFERENCES `payment_order` (`payment_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单支付退款额度汇总，和退款明细事务维护';

CREATE TABLE `payment_ledger` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付或退款业务编号',
  `entry_type` ENUM('PAYMENT_SUCCEEDED','REFUND_SUCCEEDED') NOT NULL DEFAULT 'PAYMENT_SUCCEEDED' COMMENT '已成功资金事实类型',
  `direction` ENUM('IN','OUT') NOT NULL DEFAULT 'IN' COMMENT '支付入、退款出',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付编号',
  `refund_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '退款编号，支付流水为空',
  `amount_cents` BIGINT NOT NULL COMMENT '正数金额，分',
  `currency` CHAR(3) CHARACTER SET ascii NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `occurred_at` DATETIME(3) NOT NULL COMMENT '通道事实发生时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_ledger_1` (`entry_type`,`business_no`),
  KEY `ix_payment_ledger_1` (`payment_no`,`created_at`,`id`),
  CONSTRAINT `ck_payment_ledger_1` CHECK (amount_cents > 0),
  CONSTRAINT `ck_payment_ledger_2` CHECK (currency = 'CNY'),
  CONSTRAINT `ck_payment_ledger_3` CHECK ((entry_type = 'PAYMENT_SUCCEEDED' AND direction = 'IN' AND refund_no IS NULL) OR (entry_type = 'REFUND_SUCCEEDED' AND direction = 'OUT' AND refund_no IS NOT NULL)),
  CONSTRAINT `fk_payment_ledger_1` FOREIGN KEY (`payment_no`) REFERENCES `payment_order` (`payment_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变支付与退款业务资金流水';

CREATE TABLE `channel_notification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `notification_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '通道通知编号',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付或退款编号',
  `notification_type` ENUM('PAYMENT','REFUND') NOT NULL DEFAULT 'PAYMENT' COMMENT '通知类型',
  `payload_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始载荷摘要',
  `verify_result` ENUM('VALID','INVALID') NOT NULL DEFAULT 'VALID' COMMENT '签名及业务校验结果',
  `process_status` ENUM('RECEIVED','APPLIED','DUPLICATE','QUARANTINED') NOT NULL DEFAULT 'RECEIVED' COMMENT '处理状态',
  `masked_payload` VARCHAR(4000) NOT NULL COMMENT '脱敏通知内容',
  `reason` VARCHAR(1000) NULL COMMENT '处理说明',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_notification_1` (`notification_no`),
  KEY `ix_channel_notification_1` (`business_no`,`created_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='回调验真及处理证据';

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

CREATE TABLE `outbox_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `event_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定事件编号',
  `aggregate_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '聚合类型',
  `aggregate_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '聚合业务编号',
  `aggregate_version` BIGINT NOT NULL DEFAULT 0 COMMENT '聚合版本',
  `event_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件类型',
  `event_version` INT NOT NULL DEFAULT 1 COMMENT '事件契约版本',
  `correlation_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务关联编号',
  `causation_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '触发命令或事件编号',
  `trace_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '追踪编号',
  `payload` JSON NOT NULL COMMENT '事件业务载荷',
  `status` ENUM('READY','RUNNING','SENT','MANUAL_REVIEW') NOT NULL DEFAULT 'READY' COMMENT '发布状态',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '发布尝试次数',
  `next_retry_at` DATETIME(3) NOT NULL COMMENT '下次发布时间',
  `lease_owner` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '领取者',
  `lease_until` DATETIME(3) NULL COMMENT '租约截止时间',
  `claim_version` BIGINT NOT NULL DEFAULT 0 COMMENT '领取版本',
  `published_at` DATETIME(3) NULL COMMENT '确认发布完成时间',
  `last_error` VARCHAR(1000) NULL COMMENT '路由或确认错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbox_event_1` (`event_id`),
  KEY `ix_outbox_event_1` (`status`,`next_retry_at`,`id`),
  KEY `ix_outbox_event_2` (`lease_until`,`id`),
  CONSTRAINT `ck_outbox_event_1` CHECK (event_version > 0),
  CONSTRAINT `ck_outbox_event_2` CHECK (aggregate_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='与本地业务同事务写入的待发布事件';
