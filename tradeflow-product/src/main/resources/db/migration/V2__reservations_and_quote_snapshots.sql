-- B2追加预占与不可变报价元数据，不修改B1已执行迁移。
ALTER TABLE product_quote
  ADD COLUMN source_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '发起报价的稳定订单号，创建即绑定来源，预占时校验' AFTER merchant_id,
  ADD UNIQUE KEY uk_quote_source_order(source_order_no);
UPDATE product_quote q JOIN idempotency_request r ON r.business_no=q.quote_no
  AND r.actor_type='SERVICE' AND r.actor_id='order' AND r.operation='CREATE_QUOTE'
  SET q.source_order_no=r.idempotency_key WHERE q.source_order_no IS NULL;
ALTER TABLE quote_item
  ADD COLUMN product_id_snapshot BIGINT UNSIGNED NULL COMMENT '报价时商品主键，旧报价未知时为空并禁止用于新下单',
  ADD COLUMN product_name_snapshot VARCHAR(200) NULL COMMENT '报价时商品名称，不随后续改名变化',
  ADD COLUMN spec_snapshot VARCHAR(500) NULL COMMENT '报价时规格说明，不随后续编辑变化';
CREATE TABLE `reservation_header` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `reservation_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '整单预占编号',
  `order_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单编号',
  `quote_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '报价编号，提前取消时可为空',
  `request_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原预占请求摘要，提前取消时可为空',
  `status` ENUM('RESERVED','CONFIRMED','RELEASED','REJECTED') NOT NULL DEFAULT 'RESERVED' COMMENT '预占状态；释放和拒绝均不可重占',
  `decision` ENUM('UNDECIDED','COMMIT','RELEASE') NOT NULL DEFAULT 'UNDECIDED' COMMENT '来自订单的库存决策，只能单向确定',
  `expires_at` DATETIME(3) NULL COMMENT '触发订单关闭检查的时间，不自动释放',
  `reject_reason` VARCHAR(500) NULL COMMENT '拒绝或取消原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_header_1` (`reservation_no`),
  UNIQUE KEY `uk_reservation_header_2` (`order_no`),
  KEY `ix_reservation_header_1` (`status`,`expires_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='整单库存预占及提前取消墓碑';

CREATE TABLE `reservation_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `reservation_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '预占编号',
  `warehouse_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '仓库编号',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '规格主键',
  `quantity` INT NOT NULL COMMENT '本次预占数量',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_item_1` (`reservation_no`,`sku_id`),
  CONSTRAINT `ck_reservation_item_1` CHECK (quantity > 0),
  CONSTRAINT `fk_reservation_item_1` FOREIGN KEY (`reservation_no`) REFERENCES `reservation_header` (`reservation_no`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_reservation_item_2` FOREIGN KEY (`sku_id`) REFERENCES `sku` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='整单原子预占的商品明细';
