-- B6购买件分配与不可撤销验收入库。
ALTER TABLE refund_authorization MODIFY source_type ENUM('UNSHIPPED_FULL','LATE_PAYMENT','REFUND_ONLY','RETURN_REFUND','TOMBSTONE') NOT NULL COMMENT '退款授权来源类型或终止墓碑', ADD allocation_hash CHAR(64) CHARACTER SET ascii NULL COMMENT '规范排序的不可变购买件金额摘要';
CREATE TABLE refund_allocation (
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退款授权来源',
 order_item_id BIGINT UNSIGNED NOT NULL COMMENT '订单商品行主键',
 unit_no INT NOT NULL COMMENT '购买件编号',
 amount_cents BIGINT NOT NULL COMMENT '本购买件授权金额分',
 PRIMARY KEY(source_no,order_item_id,unit_no),
 CONSTRAINT fk_allocation_source FOREIGN KEY(source_no) REFERENCES refund_authorization(source_no),
 CONSTRAINT ck_allocation_amount CHECK(amount_cents>0)
) ENGINE=InnoDB COMMENT='不可变退款逐件金额分配';
CREATE TABLE order_return_receipt (
 receipt_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定RET售后验收号',
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变退货授权来源',
 status ENUM('RECEIVED','APPLIED') NOT NULL DEFAULT 'RECEIVED' COMMENT '已验收或已确认入库',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '验收UTC时间',
 PRIMARY KEY(receipt_no),UNIQUE KEY uk_return_source(source_no),
 CONSTRAINT fk_return_source FOREIGN KEY(source_no) REFERENCES refund_authorization(source_no)
) ENGINE=InnoDB COMMENT='不可撤销的逐件退货验收事实';
CREATE TABLE return_stock_task (
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退货来源任务号',
 state ENUM('READY','RUNNING','SUCCEEDED','MANUAL_REVIEW') NOT NULL DEFAULT 'READY' COMMENT '独立入库恢复状态',
 attempt_count INT NOT NULL DEFAULT 0 COMMENT '当前自动恢复次数',
 claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '租约递增围栏',
 lease_until DATETIME(3) NULL COMMENT '执行租约UTC截止',
 next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次恢复UTC时间',
 last_error VARCHAR(500) NULL COMMENT '稳定脱敏错误码',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '任务创建UTC时间',
 PRIMARY KEY(source_no),KEY ix_return_due(state,next_retry_at),
 CONSTRAINT fk_return_task FOREIGN KEY(source_no) REFERENCES refund_authorization(source_no)
) ENGINE=InnoDB COMMENT='与退款成败无关的验收退货入库恢复任务';
