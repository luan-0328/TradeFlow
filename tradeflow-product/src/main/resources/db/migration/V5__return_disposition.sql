ALTER TABLE return_restock_item ADD disposition VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SALEABLE'
 COMMENT '本购买件最初验收处置；历史行按旧语义可再售，新记录显式指定',
 ADD CONSTRAINT ck_return_restock_disposition CHECK(disposition IN ('SALEABLE','QUARANTINE','DAMAGED','SCRAPPED'));
CREATE TABLE inventory_condition (
 warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '单仓库标识',
 sku_id BIGINT NOT NULL COMMENT '商品SKU标识',
 stock_condition VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可售库存状态QUARANTINE待检或DAMAGED残次',
 quantity BIGINT NOT NULL DEFAULT 0 COMMENT '当前不可售实物数量，不能参与销售预占',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '并发受控更新版本',
 PRIMARY KEY(warehouse_id,sku_id,stock_condition),
 CONSTRAINT ck_inventory_condition_name CHECK(stock_condition IN ('QUARANTINE','DAMAGED')),
 CONSTRAINT ck_inventory_condition_quantity CHECK(quantity BETWEEN 0 AND 1000000000)
) ENGINE=InnoDB COMMENT='与可售库存分开的待检和残次余额';
CREATE TABLE inventory_condition_operation (
 operation_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等库存处置业务号',
 warehouse_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属单仓库',
 sku_id BIGINT NOT NULL COMMENT '受控处置的SKU',
 from_condition VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原待检或残次状态',
 to_condition VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标可售、待检、残次或报损状态',
 quantity BIGINT NOT NULL COMMENT '本次实物处置数量',
 actor_id VARCHAR(64) NOT NULL COMMENT '商家管理员操作人',
 reason VARCHAR(500) NOT NULL COMMENT '检验维修或报损依据',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '处置UTC时间',
 PRIMARY KEY(operation_no),
 CONSTRAINT ck_condition_operation_quantity CHECK(quantity BETWEEN 1 AND 1000000000),
 CONSTRAINT ck_condition_operation_from CHECK(from_condition IN ('QUARANTINE','DAMAGED')),
 CONSTRAINT ck_condition_operation_to CHECK(to_condition IN ('SALEABLE','QUARANTINE','DAMAGED','SCRAPPED') AND from_condition<>to_condition)
) ENGINE=InnoDB COMMENT='不可售库存人工处置流水，不删除原退货事实';
