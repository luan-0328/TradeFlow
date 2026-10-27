-- 验收认可与商品可再售分离；历史验收沿用旧版全部可再售语义，新请求必须显式逐件处置。
CREATE TABLE aftersale_return_disposition (
 aftersale_no VARCHAR(35) NOT NULL COMMENT '已认可退货的售后号',
 order_item_id BIGINT NOT NULL COMMENT '原申请订单商品行标识',
 unit_no INT NOT NULL COMMENT '原申请购买件序号',
 disposition VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实物去向：SALEABLE可再售、QUARANTINE待检、DAMAGED残次、SCRAPPED报损',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '验收处置保存UTC时间',
 PRIMARY KEY(aftersale_no,order_item_id,unit_no),
 CONSTRAINT fk_return_disposition_allocation FOREIGN KEY(aftersale_no,order_item_id,unit_no) REFERENCES aftersale_allocation(aftersale_no,order_item_id,unit_no),
 CONSTRAINT ck_return_disposition CHECK(disposition IN ('SALEABLE','QUARANTINE','DAMAGED','SCRAPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='退货逐件不可变处置，认可退款不代表恢复可售库存';
INSERT INTO aftersale_return_disposition(aftersale_no,order_item_id,unit_no,disposition)
 SELECT a.aftersale_no,a.order_item_id,a.unit_no,'SALEABLE' FROM aftersale_allocation a
 JOIN aftersale_return_inspection i ON i.aftersale_no=a.aftersale_no WHERE i.accepted=1;
