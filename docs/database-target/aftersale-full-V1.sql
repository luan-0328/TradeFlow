-- 仅连接到 trade_aftersale 执行；Flyway 默认 schema=trade_aftersale。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `aftersale_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后编号',
  `order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付编号',
  `user_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申请用户',
  `merchant_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商家编号',
  `aftersale_type` ENUM('UNSHIPPED_FULL','REFUND_ONLY','RETURN_REFUND') NOT NULL DEFAULT 'UNSHIPPED_FULL' COMMENT '未发货全额、已发货仅退款、退货退款',
  `status` ENUM('CREATING','PENDING_REVIEW','REJECTED','CANCELLED','WAITING_RETURN','RETURN_IN_TRANSIT','RETURN_RECEIVED','RETURN_REJECTED','REFUND_PROCESSING','COMPLETED') NOT NULL DEFAULT 'CREATING' COMMENT '售后业务阶段',
  `requested_amount_cents` BIGINT NOT NULL COMMENT '申请金额，分',
  `approved_amount_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '商家同意金额，分',
  `reason` VARCHAR(1000) NOT NULL COMMENT '申请原因',
  `entitlement_status` ENUM('PENDING','RESERVED','ACTIVATED','SETTLED','RELEASED') NOT NULL DEFAULT 'PENDING' COMMENT '订单侧权益同步状态',
  `stock_status` ENUM('NOT_REQUIRED','PENDING','APPLIED') NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT '库存回补同步状态',
  `refund_status` ENUM('NOT_STARTED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED') NOT NULL DEFAULT 'NOT_STARTED' COMMENT '退款结果投影，非资金最终权威',
  `current_refund_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前执行退款编号',
  `return_deadline` DATETIME(3) NULL COMMENT '提交退货物流截止时间',
  `reviewed_at` DATETIME(3) NULL COMMENT '审核时间',
  `recovery_reason` VARCHAR(1000) NULL COMMENT '异常恢复说明',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aftersale_order_1` (`aftersale_no`),
  KEY `ix_aftersale_order_1` (`user_id`,`created_at`,`id`),
  KEY `ix_aftersale_order_2` (`merchant_id`,`status`,`created_at`,`id`),
  KEY `ix_aftersale_order_3` (`order_no`,`status`,`id`),
  CONSTRAINT `ck_aftersale_order_1` CHECK (requested_amount_cents > 0),
  CONSTRAINT `ck_aftersale_order_2` CHECK (approved_amount_cents BETWEEN 0 AND requested_amount_cents)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后业务主单';

CREATE TABLE `aftersale_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后编号',
  `order_item_id` BIGINT UNSIGNED NOT NULL COMMENT '订单行引用',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '规格引用',
  `quantity` INT NOT NULL COMMENT '申请涉及购买件数量',
  `amount_cents` BIGINT NOT NULL COMMENT '本行退款金额，分',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aftersale_item_1` (`aftersale_no`,`order_item_id`),
  CONSTRAINT `ck_aftersale_item_1` CHECK (quantity > 0),
  CONSTRAINT `ck_aftersale_item_2` CHECK (amount_cents >= 0),
  CONSTRAINT `fk_aftersale_item_1` FOREIGN KEY (`aftersale_no`) REFERENCES `aftersale_order` (`aftersale_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后商品明细及金额分配';

CREATE TABLE `aftersale_unit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后编号',
  `order_item_id` BIGINT UNSIGNED NOT NULL COMMENT '订单商品行引用',
  `unit_no` INT NOT NULL COMMENT '购买件逻辑编号',
  `amount_cents` BIGINT NOT NULL COMMENT '该件退款金额，分',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aftersale_unit_1` (`aftersale_no`,`order_item_id`,`unit_no`),
  CONSTRAINT `ck_aftersale_unit_1` CHECK (amount_cents >= 0),
  CONSTRAINT `fk_aftersale_unit_1` FOREIGN KEY (`aftersale_no`) REFERENCES `aftersale_order` (`aftersale_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本次售后涉及的购买件与金额快照';

CREATE TABLE `evidence_file` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `object_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端随机文件键，不含用户路径',
  `uploader_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '上传者用户编号',
  `mime_type` VARCHAR(64) NOT NULL COMMENT '校验文件头后的允许类型',
  `size_bytes` BIGINT NOT NULL COMMENT '文件字节数',
  `content_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件 SHA-256',
  `state` ENUM('UPLOADED','BOUND','TOMBSTONED') NOT NULL DEFAULT 'UPLOADED' COMMENT '已上传、已关联、待删除',
  `bound_aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '已关联售后编号',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evidence_file_1` (`object_key`),
  KEY `ix_evidence_file_1` (`state`,`created_at`,`id`),
  CONSTRAINT `ck_evidence_file_1` CHECK (size_bytes BETWEEN 1 AND 5242880)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='证据文件上传归属及安全清理元数据';

CREATE TABLE `aftersale_evidence` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后编号',
  `evidence_type` ENUM('APPLICATION','RETURN_INSPECTION') NOT NULL DEFAULT 'APPLICATION' COMMENT '申请或验收证据',
  `object_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受控文件键，不保存任意可执行地址',
  `uploader_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '上传者编号',
  `content_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件内容摘要',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aftersale_evidence_1` (`aftersale_no`,`evidence_type`,`object_key`),
  KEY `ix_aftersale_evidence_1` (`aftersale_no`,`id`),
  CONSTRAINT `fk_aftersale_evidence_1` FOREIGN KEY (`aftersale_no`) REFERENCES `aftersale_order` (`aftersale_no`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_aftersale_evidence_2` FOREIGN KEY (`object_key`) REFERENCES `evidence_file` (`object_key`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后问题及验收证据引用';

CREATE TABLE `return_shipment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后编号',
  `carrier` VARCHAR(64) NOT NULL COMMENT '承运方',
  `tracking_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退货物流单号',
  `submitter_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '提交用户',
  `submitted_at` DATETIME(3) NOT NULL COMMENT '提交时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_return_shipment_1` (`aftersale_no`),
  CONSTRAINT `fk_return_shipment_1` FOREIGN KEY (`aftersale_no`) REFERENCES `aftersale_order` (`aftersale_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户提交的一次退货物流';

CREATE TABLE `return_receipt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `receipt_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定验收及入库业务号',
  `aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后编号',
  `inspection_result` ENUM('ACCEPTED','REJECTED') NOT NULL DEFAULT 'ACCEPTED' COMMENT '可再售验收通过或拒绝',
  `operator_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '验收商家操作人',
  `remark` VARCHAR(1000) NOT NULL COMMENT '验收结论及证据说明',
  `inspected_at` DATETIME(3) NOT NULL COMMENT '验收时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_return_receipt_1` (`receipt_no`),
  UNIQUE KEY `uk_return_receipt_2` (`aftersale_no`),
  CONSTRAINT `fk_return_receipt_1` FOREIGN KEY (`aftersale_no`) REFERENCES `aftersale_order` (`aftersale_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商家一次退货验收结果';

CREATE TABLE `aftersale_audit_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后编号',
  `operator_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作者',
  `action` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务动作',
  `from_status` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原阶段',
  `to_status` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标阶段',
  `request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求编号',
  `remark` VARCHAR(1000) NOT NULL COMMENT '审核及操作原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  KEY `ix_aftersale_audit_log_1` (`aftersale_no`,`created_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变售后操作和阶段迁移记录';

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
