CREATE TABLE return_restock (
 receipt_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定退货验收入库收据号',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属订单号',
 reservation_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原已确认销售预占号',
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单权威售后来源号',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库UTC时间',
 PRIMARY KEY(receipt_no), UNIQUE KEY uk_return_restock_source(source_no), KEY idx_return_restock_reservation(reservation_no),
 CONSTRAINT fk_return_restock_reservation FOREIGN KEY(reservation_no) REFERENCES reservation_header(reservation_no)
) ENGINE=InnoDB COMMENT='验收退货独立于退款结果的不可变入库收据';
CREATE TABLE return_restock_item (
 receipt_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属验收入库收据号',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原销售订单号',
 reservation_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原销售预占号',
 order_item_id BIGINT NOT NULL COMMENT '订单权威购买明细标识',
 unit_no INT NOT NULL COMMENT '购买明细内的单件序号',
 sku_id BIGINT NOT NULL COMMENT '订单验收收据确认的SKU标识',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '逐件入库UTC时间',
 PRIMARY KEY(receipt_no,order_item_id,unit_no), UNIQUE KEY uk_return_sold_unit(order_no,order_item_id,unit_no), KEY idx_return_quantity(reservation_no,sku_id),
 CONSTRAINT ck_return_unit_positive CHECK(unit_no>0),
 CONSTRAINT fk_return_item_receipt FOREIGN KEY(receipt_no) REFERENCES return_restock(receipt_no)
) ENGINE=InnoDB COMMENT='每销售购买件至多回补一次的退货入库明细';
