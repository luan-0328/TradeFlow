-- B1 首次发布迁移；每表每字段中文注释。未启用表见 docs/database-target。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `product` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `merchant_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属商家编号',
  `name` VARCHAR(200) NOT NULL COMMENT '商品名称',
  `description` VARCHAR(2000) NOT NULL COMMENT '商品描述',
  `status` ENUM('DRAFT','ON_SALE','OFF_SALE') NOT NULL DEFAULT 'DRAFT' COMMENT '商品状态：草稿、上架、下架',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  KEY `ix_product_1` (`merchant_id`,`status`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品主档，不直接编辑库存';

CREATE TABLE `sku` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `product_id` BIGINT UNSIGNED NOT NULL COMMENT '所属商品主键',
  `sku_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规格编码',
  `specification` VARCHAR(500) NOT NULL COMMENT '规格说明',
  `price_cents` BIGINT NOT NULL COMMENT '当前销售单价，单位分',
  `price_version` BIGINT NOT NULL DEFAULT 0 COMMENT '价格版本，改价时递增',
  `status` ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '规格状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_1` (`sku_code`),
  CONSTRAINT `ck_sku_1` CHECK (price_cents > 0),
  CONSTRAINT `ck_sku_2` CHECK (price_version >= 0),
  CONSTRAINT `fk_sku_1` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品规格和权威销售价格';

CREATE TABLE `product_quote` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `quote_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '报价编号',
  `user_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '报价所属用户编号',
  `merchant_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '报价所属商家',
  `bound_order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '已绑定订单号，同报价只能绑定一单',
  `request_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化报价请求的 SHA-256 摘要',
  `expires_at` DATETIME(3) NOT NULL COMMENT '报价失效时间',
  `status` ENUM('VALID','BOUND','EXPIRED') NOT NULL DEFAULT 'VALID' COMMENT '报价状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_quote_1` (`quote_no`),
  UNIQUE KEY `uk_product_quote_2` (`bound_order_no`),
  KEY `ix_product_quote_1` (`status`,`expires_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变成交报价及订单绑定';

CREATE TABLE `quote_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `quote_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属报价编号',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '规格主键',
  `quantity` INT NOT NULL COMMENT '报价数量',
  `unit_price_cents` BIGINT NOT NULL COMMENT '成交单价，单位分',
  `price_version` BIGINT NOT NULL COMMENT '报价时价格版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quote_item_1` (`quote_no`,`sku_id`),
  CONSTRAINT `ck_quote_item_1` CHECK (quantity BETWEEN 1 AND 99),
  CONSTRAINT `ck_quote_item_2` CHECK (unit_price_cents > 0),
  CONSTRAINT `fk_quote_item_1` FOREIGN KEY (`quote_no`) REFERENCES `product_quote` (`quote_no`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_quote_item_2` FOREIGN KEY (`sku_id`) REFERENCES `sku` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报价商品明细快照';

CREATE TABLE `inventory` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `warehouse_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '仓库编号',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '规格主键',
  `available_qty` BIGINT NOT NULL DEFAULT 0 COMMENT '可销售库存数量',
  `reserved_qty` BIGINT NOT NULL DEFAULT 0 COMMENT '已预占未确认数量',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_1` (`warehouse_id`,`sku_id`),
  CONSTRAINT `ck_inventory_1` CHECK (available_qty >= 0),
  CONSTRAINT `ck_inventory_2` CHECK (reserved_qty >= 0),
  CONSTRAINT `fk_inventory_1` FOREIGN KEY (`sku_id`) REFERENCES `sku` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单仓库规格库存余额';

CREATE TABLE `inventory_receipt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `receipt_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '出入库单编号',
  `receipt_type` ENUM('INITIAL','ADJUSTMENT','RETURN','UNSHIPPED_CANCEL') NOT NULL DEFAULT 'INITIAL' COMMENT '业务类型：初始化、调整、退货、未发货取消',
  `source_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源业务编号',
  `merchant_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属商家',
  `status` ENUM('CREATED','APPLIED','REJECTED') NOT NULL DEFAULT 'CREATED' COMMENT '处理状态',
  `reason` VARCHAR(500) NOT NULL COMMENT '业务原因或验收说明',
  `operator_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作人或服务身份',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_receipt_1` (`receipt_no`),
  UNIQUE KEY `uk_inventory_receipt_2` (`receipt_type`,`source_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购调整及取消退货入库单';

CREATE TABLE `receipt_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `receipt_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '出入库单编号',
  `warehouse_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '仓库编号',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '规格主键',
  `quantity_delta` BIGINT NOT NULL COMMENT '可用库存变化数量，调整可正可负',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_receipt_item_1` (`receipt_no`,`sku_id`),
  CONSTRAINT `ck_receipt_item_1` CHECK (quantity_delta <> 0),
  CONSTRAINT `fk_receipt_item_1` FOREIGN KEY (`receipt_no`) REFERENCES `inventory_receipt` (`receipt_no`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_receipt_item_2` FOREIGN KEY (`sku_id`) REFERENCES `sku` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出入库单明细，调整可为负数';

CREATE TABLE `inventory_ledger` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定业务编号',
  `operation_type` ENUM('RESERVE','CONFIRM','RELEASE','INITIAL','ADJUSTMENT','RETURN','UNSHIPPED_CANCEL') NOT NULL DEFAULT 'RESERVE' COMMENT '库存操作类型',
  `warehouse_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '仓库编号',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '规格主键',
  `available_delta` BIGINT NOT NULL DEFAULT 0 COMMENT '可用库存变化量',
  `reserved_delta` BIGINT NOT NULL DEFAULT 0 COMMENT '预占库存变化量',
  `available_after` BIGINT NOT NULL COMMENT '操作后可用数量',
  `reserved_after` BIGINT NOT NULL COMMENT '操作后预占数量',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_ledger_1` (`business_no`,`operation_type`,`sku_id`),
  KEY `ix_inventory_ledger_1` (`sku_id`,`created_at`,`id`),
  CONSTRAINT `ck_inventory_ledger_1` CHECK (available_after >= 0),
  CONSTRAINT `ck_inventory_ledger_2` CHECK (reserved_after >= 0),
  CONSTRAINT `ck_inventory_ledger_3` CHECK (available_delta <> 0 OR reserved_delta <> 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变库存变化流水';

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
