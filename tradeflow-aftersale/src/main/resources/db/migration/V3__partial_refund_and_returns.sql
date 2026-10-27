-- B6追加升级；旧迁移不可改写，原整单售后继续可恢复。
ALTER TABLE aftersale DROP CHECK ck_aftersale_type, DROP CHECK ck_aftersale_state,
 MODIFY type VARCHAR(32) NOT NULL COMMENT '售后类型：未发货整单、已发货金额仅退款、退货退款',
 ADD return_deadline DATETIME(6) NULL COMMENT '审核通过后七天内提交退货物流的UTC截止时间',
 ADD return_receipt_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '验收通过固定收据号，登记后不可撤销',
 ADD CONSTRAINT ck_aftersale_type CHECK(type IN ('UNSHIPPED_FULL','REFUND_ONLY','RETURN_REFUND')),
 ADD CONSTRAINT ck_aftersale_state CHECK(status IN ('CREATING','PENDING_REVIEW','CANCELLING','CANCELLED','REJECTING','REJECTED','REFUND_PROCESSING','COMPLETED','WAITING_RETURN','RETURN_IN_TRANSIT','RETURN_RECEIVED','RETURN_REJECTING','RETURN_REJECTED')),
 ADD KEY ix_return_timeout(status,return_deadline);
CREATE TABLE aftersale_allocation (
 aftersale_no VARCHAR(35) NOT NULL COMMENT '所属售后号',
 order_item_id BIGINT NOT NULL COMMENT '订单商品行号，仅跨服务引用不跨库外键',
 unit_no INT NOT NULL COMMENT '该商品行虚拟购买件序号，不是实物防伪码',
 amount_cents BIGINT NOT NULL COMMENT '用户确认的本件申请金额分，订单重新校验',
 PRIMARY KEY(aftersale_no,order_item_id,unit_no),
 CONSTRAINT fk_allocation_sale FOREIGN KEY(aftersale_no) REFERENCES aftersale(aftersale_no),
 CONSTRAINT ck_allocation_values CHECK(order_item_id>0 AND unit_no BETWEEN 1 AND 99 AND amount_cents BETWEEN 1 AND 100000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='不可变售后购买件申请分配，重复请求不能换件换金额';
CREATE TABLE aftersale_return_shipment (
 aftersale_no VARCHAR(35) NOT NULL COMMENT '退货售后号，一次登记后不可换物流',
 carrier VARCHAR(80) NOT NULL COMMENT '用户登记的退货承运商',
 tracking_no VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户登记的退货单号',
 actor_id VARCHAR(32) NOT NULL COMMENT '提交物流的本人用户号',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '物流提交UTC时间',
 PRIMARY KEY(aftersale_no), CONSTRAINT fk_return_shipment_sale FOREIGN KEY(aftersale_no) REFERENCES aftersale(aftersale_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='用户退货物流快照，已寄出不自动判定丢失或退款';
CREATE TABLE aftersale_return_inspection (
 aftersale_no VARCHAR(35) NOT NULL COMMENT '一次整单验收的售后号',
 accepted BOOLEAN NOT NULL COMMENT '是否全部验收为可再次销售商品',
 reason VARCHAR(500) NOT NULL COMMENT '验收通过或拒收原因',
 actor_id VARCHAR(32) NOT NULL COMMENT '验收商家用户号',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '验收登记UTC时间',
 PRIMARY KEY(aftersale_no), CONSTRAINT fk_inspection_sale FOREIGN KEY(aftersale_no) REFERENCES aftersale(aftersale_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='不可变退货验收事实，拒收物流返还由人工处置';
CREATE TABLE evidence_file (
 object_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的随机文件键，不含用户路径',
 owner_id VARCHAR(32) NOT NULL COMMENT '上传者用户号',
 media_type VARCHAR(32) CHARACTER SET ascii NOT NULL COMMENT '按文件头识别的PNG或JPEG或WebP类型',
 size_bytes BIGINT NOT NULL COMMENT '文件实际字节数，最大五兆',
 content_hash CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '原文件SHA256摘要',
 state VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED待绑定、BOUND已关联、TOMBSTONED待删除、DELETED已清理',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '上传UTC时间，未绑定一天后允许清理',
 PRIMARY KEY(object_key), KEY ix_evidence_cleanup(state,created_at),
 CONSTRAINT ck_evidence_state CHECK(state IN ('UPLOADED','BOUND','TOMBSTONED','DELETED')),
 CONSTRAINT ck_evidence_size CHECK(size_bytes BETWEEN 1 AND 5242880)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='本地受控证据文件元数据与清理状态';
CREATE TABLE aftersale_evidence (
 aftersale_no VARCHAR(35) NOT NULL COMMENT '关联售后号',
 object_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已验证上传归属的受控文件键',
 purpose VARCHAR(16) CHARACTER SET ascii NOT NULL COMMENT 'APPLICATION申请或INSPECTION验收证据',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '关联UTC时间',
 PRIMARY KEY(aftersale_no,object_key), UNIQUE KEY uk_evidence_binding(object_key),
 CONSTRAINT fk_evidence_sale FOREIGN KEY(aftersale_no) REFERENCES aftersale(aftersale_no),
 CONSTRAINT fk_evidence_object FOREIGN KEY(object_key) REFERENCES evidence_file(object_key),
 CONSTRAINT ck_evidence_purpose CHECK(purpose IN ('APPLICATION','INSPECTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='不可变售后证据绑定，已关联文件禁止自动删除';
