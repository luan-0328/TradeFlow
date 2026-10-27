CREATE TABLE unshipped_restock (
 receipt_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定取消回补单号',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属订单号',
 reservation_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原已确认销售预占号',
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已退款售后来源号',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '回补UTC时间',
 PRIMARY KEY(receipt_no), UNIQUE KEY uk_restock_reservation(reservation_no), UNIQUE KEY uk_restock_source(source_no),
 CONSTRAINT fk_restock_reservation FOREIGN KEY(reservation_no) REFERENCES reservation_header(reservation_no)
) ENGINE=InnoDB COMMENT='整单未发货退款后的唯一销售回补依据';
