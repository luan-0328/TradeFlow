-- B4只追加，不改写已经执行的订单迁移。
CREATE TABLE refund_authorization (
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '售后号或CLOSE订单来源号',
 source_type ENUM('UNSHIPPED_FULL','LATE_PAYMENT') NOT NULL COMMENT '退款授权来源类型',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属订单号',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付号',
 user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '买家编号',
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属商家编号',
 amount_cents BIGINT NOT NULL COMMENT '本来源权威授权金额分',
 currency CHAR(3) CHARACTER SET ascii NOT NULL DEFAULT 'CNY' COMMENT '人民币币种',
 state ENUM('RESERVED','EXECUTING','SETTLING','COMPLETED','TERMINATED') NOT NULL COMMENT '权益预留执行结算状态',
 settled_refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '唯一成功退款执行号',
 version BIGINT NOT NULL DEFAULT 1 COMMENT '不可变授权内容版本，状态迁移不变',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新UTC时间',
 PRIMARY KEY(source_no), UNIQUE KEY uk_settled_refund(settled_refund_no), KEY ix_auth_order(order_no),
 CONSTRAINT ck_auth_amount CHECK(amount_cents>0 AND amount_cents<=100000000),
 CONSTRAINT ck_auth_currency CHECK(currency='CNY'),
 CONSTRAINT fk_auth_order FOREIGN KEY(order_no) REFERENCES orders(order_no)
) ENGINE=InnoDB COMMENT='订单权威退款来源及不可撤销执行授权';
CREATE TABLE order_shipment (
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '整单发货订单号',
 carrier VARCHAR(80) NOT NULL COMMENT '模拟承运商',
 tracking_no VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '模拟物流单号',
 actor_id VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '登记商家用户',
 request_hash CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '首次发货内容摘要',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发货登记UTC时间',
 PRIMARY KEY(order_no), CONSTRAINT fk_shipment_order FOREIGN KEY(order_no) REFERENCES orders(order_no)
) ENGINE=InnoDB COMMENT='不可变整单物流快照';
CREATE TABLE refund_flow_task (
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定退款来源任务号',
 step ENUM('EXECUTE','SETTLE') NOT NULL COMMENT '迟到支付执行或退款后回补阶段',
 refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '人工确认旧执行失败后绑定的新退款号，空时使用固定首号',
 state ENUM('READY','RUNNING','SUCCEEDED','MANUAL_REVIEW') NOT NULL DEFAULT 'READY' COMMENT '恢复执行状态',
 attempt_count INT NOT NULL DEFAULT 0 COMMENT '当前自动执行次数',
 claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增围栏',
 lease_owner VARCHAR(64) CHARACTER SET ascii NULL COMMENT '当前执行实例',
 lease_until DATETIME(3) NULL COMMENT '领取租约UTC截止',
 next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次恢复UTC时间',
 last_error VARCHAR(500) NULL COMMENT '稳定脱敏错误码',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
 PRIMARY KEY(source_no), KEY ix_refund_task(state,next_retry_at),
 CONSTRAINT fk_refund_task_source FOREIGN KEY(source_no) REFERENCES refund_authorization(source_no)
) ENGINE=InnoDB COMMENT='订单退款执行及回补持久恢复任务';
CREATE TABLE order_refund_fact (
 event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付结果事件唯一号',
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '退款授权来源',
 refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原退款执行号',
 payload_hash CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '不可变事实摘要',
 status VARCHAR(32) CHARACTER SET ascii NOT NULL COMMENT '已验证退款终态',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '接收UTC时间',
 PRIMARY KEY(event_id), KEY ix_refund_fact_source(source_no)
) ENGINE=InnoDB COMMENT='不可变退款接收证据及事件幂等记录';
