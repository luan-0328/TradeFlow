-- 仅连接到 trade_payment 执行；Flyway 默认 schema=trade_payment。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `payment_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付编号',
  `order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号',
  `user_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '买家编号',
  `merchant_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商家编号',
  `amount_cents` BIGINT NOT NULL COMMENT '实付金额，分',
  `currency` CHAR(3) CHARACTER SET ascii NOT NULL DEFAULT 'CNY' COMMENT '支付币种',
  `status` ENUM('PENDING','PROCESSING','UNKNOWN','SUCCEEDED','FAILED','CLOSED') NOT NULL DEFAULT 'PENDING' COMMENT '支付事实状态',
  `close_intent` ENUM('NO','YES') NOT NULL DEFAULT 'NO' COMMENT '是否持久化关闭意图，阻止新提交',
  `channel_transaction_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '通道交易号',
  `channel_version` BIGINT NOT NULL DEFAULT 0 COMMENT '最近已确认通道版本',
  `succeeded_at` DATETIME(3) NULL COMMENT '通道成功时间',
  `channel_updated_at` DATETIME(3) NULL COMMENT '通道事实最后更新时间',
  `last_error` VARCHAR(1000) NULL COMMENT '最近错误摘要',
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

CREATE TABLE `refund_source` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `source_type` ENUM('AFTERSALE','LATE_PAYMENT') NOT NULL DEFAULT 'AFTERSALE' COMMENT '售后或迟到支付补偿',
  `source_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后编号或关闭补偿编号',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付编号',
  `authorized_amount_cents` BIGINT NOT NULL COMMENT '来源授权金额，分',
  `reserved_refund_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '本来源未决退款金额，分',
  `succeeded_refund_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '本来源成功退款金额，分',
  `current_attempt_no` INT NOT NULL DEFAULT 0 COMMENT '当前执行尝试序号',
  `active_refund_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前未决执行编号',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_source_1` (`source_type`,`source_no`),
  CONSTRAINT `ck_refund_source_1` CHECK (authorized_amount_cents > 0),
  CONSTRAINT `ck_refund_source_2` CHECK (reserved_refund_cents >= 0),
  CONSTRAINT `ck_refund_source_3` CHECK (succeeded_refund_cents >= 0),
  CONSTRAINT `ck_refund_source_4` CHECK (reserved_refund_cents + succeeded_refund_cents <= authorized_amount_cents),
  CONSTRAINT `ck_refund_source_5` CHECK (current_attempt_no >= 0),
  CONSTRAINT `fk_refund_source_1` FOREIGN KEY (`payment_no`) REFERENCES `payment_order` (`payment_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款来源授权及跨尝试金额约束';

CREATE TABLE `refund_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `refund_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退款执行编号',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付编号',
  `source_type` ENUM('AFTERSALE','LATE_PAYMENT') NOT NULL DEFAULT 'AFTERSALE' COMMENT '退款来源类型',
  `source_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源业务号',
  `attempt_no` INT NOT NULL COMMENT '同来源执行序号，从一开始',
  `amount_cents` BIGINT NOT NULL COMMENT '本次退款金额，分',
  `status` ENUM('CREATED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED') NOT NULL DEFAULT 'CREATED' COMMENT '退款状态，未知不能释放占用',
  `request_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退款请求摘要',
  `channel_refund_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '通道退款编号',
  `channel_version` BIGINT NOT NULL DEFAULT 0 COMMENT '最近通道版本',
  `succeeded_at` DATETIME(3) NULL COMMENT '退款成功时间',
  `channel_updated_at` DATETIME(3) NULL COMMENT '通道事实最后更新时间',
  `failure_code` VARCHAR(64) NULL COMMENT '明确失败码或查询错误码',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_order_1` (`refund_no`),
  UNIQUE KEY `uk_refund_order_2` (`source_type`,`source_no`,`attempt_no`),
  UNIQUE KEY `uk_refund_order_3` (`channel_refund_no`),
  KEY `ix_refund_order_1` (`payment_no`,`status`,`id`),
  KEY `ix_refund_order_2` (`status`,`updated_at`,`id`),
  CONSTRAINT `ck_refund_order_1` CHECK (amount_cents > 0),
  CONSTRAINT `ck_refund_order_2` CHECK (attempt_no > 0),
  CONSTRAINT `fk_refund_order_1` FOREIGN KEY (`payment_no`) REFERENCES `payment_order` (`payment_no`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_refund_order_2` FOREIGN KEY (`source_type`,`source_no`) REFERENCES `refund_source` (`source_type`,`source_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='逐笔退款执行与通道结果';

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

CREATE TABLE `reconciliation_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `run_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对账运行编号',
  `range_start` DATETIME(3) NOT NULL COMMENT 'UTC 账期起点，包含',
  `range_end` DATETIME(3) NOT NULL COMMENT 'UTC 账期终点，不包含',
  `snapshot_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '通道完整账单快照号',
  `status` ENUM('CREATED','RUNNING','INCOMPLETE','COMPLETED','FAILED') NOT NULL DEFAULT 'CREATED' COMMENT '对账运行状态',
  `cursor_value` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '断点续跑游标',
  `channel_count` BIGINT NOT NULL DEFAULT 0 COMMENT '通道快照记录数',
  `internal_count` BIGINT NOT NULL DEFAULT 0 COMMENT '内部记录数',
  `channel_amount_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '通道快照金额汇总，分',
  `difference_count` BIGINT NOT NULL DEFAULT 0 COMMENT '本次发现差异数',
  `operator_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发起人或定时任务',
  `last_error` VARCHAR(1000) NULL COMMENT '失败原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reconciliation_run_1` (`run_no`),
  KEY `ix_reconciliation_run_1` (`status`,`created_at`,`id`),
  CONSTRAINT `ck_reconciliation_run_1` CHECK (range_start < range_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对账账期与完整账单快照任务';

CREATE TABLE `reconciliation_issue` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `issue_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '差异编号',
  `fingerprint` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '通道类型业务号差异类型组合的 SHA-256',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '相关业务编号',
  `issue_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '差异类型编码',
  `status` ENUM('OPEN','VERIFYING','REPAIRING','RESOLVED','MANUAL_REVIEW') NOT NULL DEFAULT 'OPEN' COMMENT '差异处置状态',
  `evidence` JSON NOT NULL COMMENT '两侧状态金额及快照证据',
  `last_observed_at` DATETIME(3) NOT NULL COMMENT '最近发现时间',
  `resolved_at` DATETIME(3) NULL COMMENT '复查确认解决时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reconciliation_issue_1` (`issue_no`),
  UNIQUE KEY `uk_reconciliation_issue_2` (`fingerprint`),
  KEY `ix_reconciliation_issue_1` (`status`,`created_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可复查的交易对账差异';

CREATE TABLE `reconciliation_observation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `run_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对账运行编号',
  `issue_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '差异编号',
  `evidence` JSON NOT NULL COMMENT '本次对比证据',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reconciliation_observation_1` (`run_no`,`issue_no`),
  CONSTRAINT `fk_reconciliation_observation_1` FOREIGN KEY (`run_no`) REFERENCES `reconciliation_run` (`run_no`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_reconciliation_observation_2` FOREIGN KEY (`issue_no`) REFERENCES `reconciliation_issue` (`issue_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每次运行观察到的差异证据';

CREATE TABLE `reconciliation_action` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `action_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '处置动作编号',
  `issue_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '差异编号',
  `operator_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作人或恢复任务',
  `action_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '执行动作编码',
  `reason` VARCHAR(1000) NOT NULL COMMENT '操作原因和证据说明',
  `result` ENUM('STARTED','SUCCEEDED','FAILED') NOT NULL DEFAULT 'STARTED' COMMENT '动作结果',
  `result_evidence` JSON NULL COMMENT '执行后复查证据',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reconciliation_action_1` (`action_no`),
  CONSTRAINT `fk_reconciliation_action_1` FOREIGN KEY (`issue_no`) REFERENCES `reconciliation_issue` (`issue_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对账安全修复审计';

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

CREATE TABLE `inbox_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `consumer_name` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立消费者名称',
  `event_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原事件编号',
  `payload_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原事件摘要，防止同号异内容',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联业务编号',
  `processed_at` DATETIME(3) NOT NULL COMMENT '本地事务处理完成时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbox_message_1` (`consumer_name`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消费提交后去重证据';

CREATE TABLE `deferred_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `consumer_name` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '消费者名称',
  `event_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原事件编号',
  `envelope` JSON NOT NULL COMMENT '完整事件封装',
  `state` ENUM('WAITING','APPLIED','MANUAL_REVIEW') NOT NULL DEFAULT 'WAITING' COMMENT '延期处理状态',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '尝试次数',
  `next_retry_at` DATETIME(3) NOT NULL COMMENT '下次尝试时间',
  `reason` VARCHAR(1000) NOT NULL COMMENT '延期或隔离原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_deferred_event_1` (`consumer_name`,`event_id`),
  KEY `ix_deferred_event_1` (`state`,`next_retry_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合法但前置条件未齐的延期事件';
