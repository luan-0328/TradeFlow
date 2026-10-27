-- 仅连接到 trade_order 执行；Flyway 默认 schema=trade_order。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `orders` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号',
  `user_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '买家用户编号',
  `merchant_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属商家编号',
  `payment_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '下单时确定的唯一支付编号',
  `reservation_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一库存预占编号',
  `quote_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '成交报价编号',
  `total_amount_cents` BIGINT NOT NULL COMMENT '订单商品实付总金额，分',
  `currency` CHAR(3) CHARACTER SET ascii NOT NULL DEFAULT 'CNY' COMMENT '币种，固定 CNY',
  `status` ENUM('CREATING','PENDING_PAYMENT','PAID','SHIPPED','COMPLETED','CLOSING','CLOSED','CANCELLED') NOT NULL DEFAULT 'CREATING' COMMENT '订单履约状态',
  `fulfillment_lock` ENUM('NONE','AFTERSALE') NOT NULL DEFAULT 'NONE' COMMENT '未发货售后履约互斥锁，属于持久化业务状态',
  `locked_aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '持有履约锁的售后编号',
  `stock_decision` ENUM('UNDECIDED','COMMIT','RELEASE') NOT NULL DEFAULT 'UNDECIDED' COMMENT '预占最终决策，不可反转',
  `stock_sync_status` ENUM('PENDING','CONFIRMED','RELEASED','RESTOCKED') NOT NULL DEFAULT 'PENDING' COMMENT '库存决策或回补同步结果',
  `pay_deadline` DATETIME(3) NOT NULL COMMENT '支付截止时间',
  `paid_at` DATETIME(3) NULL COMMENT '订单接受支付时间',
  `shipped_at` DATETIME(3) NULL COMMENT '发货时间',
  `completed_at` DATETIME(3) NULL COMMENT '收货完成时间',
  `closed_at` DATETIME(3) NULL COMMENT '关闭或取消完成时间',
  `close_reason` VARCHAR(255) NULL COMMENT '关闭原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_orders_1` (`order_no`),
  UNIQUE KEY `uk_orders_2` (`payment_no`),
  UNIQUE KEY `uk_orders_3` (`reservation_no`),
  KEY `ix_orders_1` (`user_id`,`created_at`,`id`),
  KEY `ix_orders_2` (`merchant_id`,`status`,`created_at`,`id`),
  KEY `ix_orders_3` (`status`,`pay_deadline`,`id`),
  CONSTRAINT `ck_orders_1` CHECK (total_amount_cents > 0),
  CONSTRAINT `ck_orders_2` CHECK (currency = 'CNY'),
  CONSTRAINT `ck_orders_3` CHECK ((fulfillment_lock = 'NONE' AND locked_aftersale_no IS NULL) OR (fulfillment_lock = 'AFTERSALE' AND locked_aftersale_no IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单主表及履约与关闭决策';

CREATE TABLE `order_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号',
  `product_id` BIGINT UNSIGNED NOT NULL COMMENT '商品引用主键',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '规格引用主键',
  `product_name_snapshot` VARCHAR(200) NOT NULL COMMENT '成交商品名称',
  `spec_snapshot` VARCHAR(500) NOT NULL COMMENT '成交规格说明',
  `unit_price_cents` BIGINT NOT NULL COMMENT '成交单价，分',
  `quantity` INT NOT NULL COMMENT '购买件数',
  `line_amount_cents` BIGINT NOT NULL COMMENT '成交行金额，分',
  `reserved_refund_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '当前售后申请预留金额，分',
  `succeeded_refund_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '累计成功退款金额，分',
  `returned_qty` INT NOT NULL DEFAULT 0 COMMENT '已验收退回数量',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_item_1` (`order_no`,`sku_id`),
  CONSTRAINT `ck_order_item_1` CHECK (quantity BETWEEN 1 AND 99),
  CONSTRAINT `ck_order_item_2` CHECK (unit_price_cents > 0),
  CONSTRAINT `ck_order_item_3` CHECK (line_amount_cents = unit_price_cents * quantity),
  CONSTRAINT `ck_order_item_4` CHECK (reserved_refund_cents >= 0),
  CONSTRAINT `ck_order_item_5` CHECK (succeeded_refund_cents >= 0),
  CONSTRAINT `ck_order_item_6` CHECK (reserved_refund_cents + succeeded_refund_cents <= line_amount_cents),
  CONSTRAINT `ck_order_item_7` CHECK (returned_qty BETWEEN 0 AND quantity),
  CONSTRAINT `fk_order_item_1` FOREIGN KEY (`order_no`) REFERENCES `orders` (`order_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单商品成交快照及权益汇总';

CREATE TABLE `order_item_unit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `order_item_id` BIGINT UNSIGNED NOT NULL COMMENT '订单商品行主键',
  `unit_no` INT NOT NULL COMMENT '行内购买件编号，从一开始',
  `paid_amount_cents` BIGINT NOT NULL COMMENT '本件成交金额，分',
  `reserved_refund_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '本件未结束售后预留金额，分',
  `succeeded_refund_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '本件累计成功退款金额，分',
  `active_aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前占用本件的售后编号，一次只允许一单',
  `return_state` ENUM('NONE','RESERVED','RECEIVED') NOT NULL DEFAULT 'NONE' COMMENT '未退货、退货授权中、已验收退货',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_item_unit_1` (`order_item_id`,`unit_no`),
  CONSTRAINT `ck_order_item_unit_1` CHECK (unit_no BETWEEN 1 AND 99),
  CONSTRAINT `ck_order_item_unit_2` CHECK (paid_amount_cents > 0),
  CONSTRAINT `ck_order_item_unit_3` CHECK (reserved_refund_cents >= 0),
  CONSTRAINT `ck_order_item_unit_4` CHECK (succeeded_refund_cents >= 0),
  CONSTRAINT `ck_order_item_unit_5` CHECK (reserved_refund_cents + succeeded_refund_cents <= paid_amount_cents),
  CONSTRAINT `ck_order_item_unit_6` CHECK (reserved_refund_cents = 0 OR active_aftersale_no IS NOT NULL),
  CONSTRAINT `fk_order_item_unit_1` FOREIGN KEY (`order_item_id`) REFERENCES `order_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购买件级金额权益，虚拟编号不是实物序列号';

CREATE TABLE `order_address` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号',
  `receiver_name` VARCHAR(64) NOT NULL COMMENT '收件人姓名',
  `receiver_phone` VARCHAR(32) NOT NULL COMMENT '收件电话，日志与页面需脱敏',
  `region` VARCHAR(255) NOT NULL COMMENT '省市区',
  `detail_address` VARCHAR(500) NOT NULL COMMENT '详细地址，仅授权读取',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_address_1` (`order_no`),
  CONSTRAINT `fk_order_address_1` FOREIGN KEY (`order_no`) REFERENCES `orders` (`order_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='下单时收货地址快照';

CREATE TABLE `shipment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号',
  `carrier` VARCHAR(64) NOT NULL COMMENT '物流承运方',
  `tracking_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '模拟物流单号',
  `operator_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发货操作人',
  `shipped_at` DATETIME(3) NOT NULL COMMENT '业务发货时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shipment_1` (`order_no`),
  CONSTRAINT `fk_shipment_1` FOREIGN KEY (`order_no`) REFERENCES `orders` (`order_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='整单一次发货记录';

CREATE TABLE `aftersale_entitlement` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后编号',
  `order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号',
  `aftersale_type` ENUM('UNSHIPPED_FULL','REFUND_ONLY','RETURN_REFUND') NOT NULL DEFAULT 'UNSHIPPED_FULL' COMMENT '未发货全额、已发货仅退款、退货退款',
  `authorized_amount_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '授权总退款金额，分',
  `state` ENUM('RESERVED','EXECUTING','SETTLING','COMPLETED','TERMINATED') NOT NULL DEFAULT 'RESERVED' COMMENT '授权状态，EXECUTING 后不得普通撤销',
  `refund_authorized` ENUM('NO','YES') NOT NULL DEFAULT 'NO' COMMENT '是否激活支付执行授权',
  `settled_refund_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '已结算的成功退款编号，同来源至多成功一次',
  `request_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '授权请求摘要，提前终止墓碑可为空',
  `terminate_reason` VARCHAR(500) NULL COMMENT '终止原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aftersale_entitlement_1` (`aftersale_no`),
  UNIQUE KEY `uk_aftersale_entitlement_2` (`settled_refund_no`),
  KEY `ix_aftersale_entitlement_1` (`order_no`,`state`,`id`),
  CONSTRAINT `ck_aftersale_entitlement_1` CHECK (authorized_amount_cents >= 0),
  CONSTRAINT `ck_aftersale_entitlement_2` CHECK (refund_authorized = 'NO' OR state IN ('EXECUTING','SETTLING','COMPLETED')),
  CONSTRAINT `fk_aftersale_entitlement_1` FOREIGN KEY (`order_no`) REFERENCES `orders` (`order_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单侧售后授权及取消墓碑';

CREATE TABLE `entitlement_unit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `aftersale_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后编号',
  `order_item_id` BIGINT UNSIGNED NOT NULL COMMENT '订单行主键',
  `unit_no` INT NOT NULL COMMENT '购买件编号',
  `amount_cents` BIGINT NOT NULL COMMENT '本次分配退款金额，分',
  `requires_return` ENUM('NO','YES') NOT NULL DEFAULT 'NO' COMMENT '本次是否要求退回实物',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entitlement_unit_1` (`aftersale_no`,`order_item_id`,`unit_no`),
  CONSTRAINT `ck_entitlement_unit_1` CHECK (amount_cents >= 0),
  CONSTRAINT `fk_entitlement_unit_1` FOREIGN KEY (`aftersale_no`) REFERENCES `aftersale_entitlement` (`aftersale_no`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_entitlement_unit_2` FOREIGN KEY (`order_item_id`,`unit_no`) REFERENCES `order_item_unit` (`order_item_id`,`unit_no`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后对购买件的金额与退货授权分配';

CREATE TABLE `order_status_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号',
  `from_status` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原状态',
  `to_status` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标状态',
  `operation` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务动作',
  `actor_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作人或服务',
  `request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求编号',
  `reason` VARCHAR(500) NOT NULL COMMENT '迁移原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  KEY `ix_order_status_log_1` (`order_no`,`created_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变订单状态迁移记录';

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
