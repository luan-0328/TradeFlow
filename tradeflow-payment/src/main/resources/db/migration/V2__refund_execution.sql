-- B4增量退款；V1已执行，不修改历史迁移。
SET NAMES utf8mb4;
CREATE TABLE refund_source (
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单授权来源号，售后或关闭来源',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一绑定原支付编号',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一绑定订单编号',
 source_type VARCHAR(32) NOT NULL COMMENT '订单授权业务类型',
 authorized_cents BIGINT NOT NULL COMMENT '订单权威授权总额，分',
 authorization_version BIGINT NOT NULL COMMENT '固定订单授权版本',
 authorization_hash CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '固定授权绑定摘要',
 reserved_cents BIGINT NOT NULL DEFAULT 0 COMMENT '当前未决执行占用金额，分',
 succeeded_cents BIGINT NOT NULL DEFAULT 0 COMMENT '来源累计已退款金额，分',
 active_refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '唯一未决退款号，失败后清空允许受控重试',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '受控更新版本',
 PRIMARY KEY(source_no), KEY ix_refund_source_payment(payment_no),
 CONSTRAINT fk_refund_source_payment FOREIGN KEY(payment_no) REFERENCES payment_order(payment_no),
 CONSTRAINT ck_refund_source_amount CHECK(authorized_cents>0 AND reserved_cents>=0 AND succeeded_cents>=0 AND reserved_cents+succeeded_cents<=authorized_cents),
 CONSTRAINT ck_refund_source_active CHECK((reserved_cents=0 AND active_refund_no IS NULL) OR (reserved_cents>0 AND active_refund_no IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单来源退款额度及不可变授权证据';
CREATE TABLE refund_order (
 refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一退款执行号',
 source_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一绑定订单授权来源',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一绑定订单编号',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一绑定原支付编号',
 amount_cents BIGINT NOT NULL COMMENT '本次权威退款金额，分',
 currency CHAR(3) CHARACTER SET ascii NOT NULL COMMENT '币种固定人民币',
 status ENUM('PENDING','PROCESSING','UNKNOWN','SUCCEEDED','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '退款事实状态，未知保留占用',
 channel_refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '通道唯一退款流水号',
 channel_version BIGINT NOT NULL DEFAULT 0 COMMENT '最近可信通道版本',
 channel_updated_at DATETIME(3) NULL COMMENT '通道事实更新时间UTC',
 succeeded_at DATETIME(3) NULL COMMENT '退款成功时间UTC',
 review_required ENUM('NO','YES') NOT NULL DEFAULT 'NO' COMMENT '矛盾事实冻结自动执行',
 last_error VARCHAR(1000) NULL COMMENT '脱敏错误及人工原因',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '受控更新版本',
 PRIMARY KEY(refund_no), UNIQUE KEY uk_refund_channel(channel_refund_no), KEY ix_refund_payment(payment_no),
 CONSTRAINT fk_refund_order_source FOREIGN KEY(source_no) REFERENCES refund_source(source_no),
 CONSTRAINT ck_refund_order_amount CHECK(amount_cents>0 AND currency='CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款执行事实，成功与双额度流水事件同事务';
