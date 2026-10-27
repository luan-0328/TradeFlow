CREATE TABLE order_return_disposition (
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可撤销验收授权来源',
 order_item_id BIGINT UNSIGNED NOT NULL COMMENT '原授权购买商品行',
 unit_no INT NOT NULL COMMENT '原授权购买件序号',
 disposition VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变实物去向：SALEABLE、QUARANTINE、DAMAGED、SCRAPPED',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '权威验收处置保存UTC时间',
 PRIMARY KEY(source_no,order_item_id,unit_no),
 CONSTRAINT fk_order_return_disposition_receipt FOREIGN KEY(source_no) REFERENCES order_return_receipt(source_no),
 CONSTRAINT fk_order_return_disposition_allocation FOREIGN KEY(source_no,order_item_id,unit_no) REFERENCES refund_allocation(source_no,order_item_id,unit_no),
 CONSTRAINT ck_order_return_disposition CHECK(disposition IN ('SALEABLE','QUARANTINE','DAMAGED','SCRAPPED'))
) ENGINE=InnoDB COMMENT='订单权威逐件退货去向，商品只能按该事实处理库存';
INSERT INTO order_return_disposition(source_no,order_item_id,unit_no,disposition)
 SELECT a.source_no,a.order_item_id,a.unit_no,'SALEABLE' FROM refund_allocation a JOIN order_return_receipt r ON r.source_no=a.source_no;
