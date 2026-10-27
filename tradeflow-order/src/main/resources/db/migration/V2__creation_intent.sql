ALTER TABLE process_task ADD COLUMN retry_cycle_started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '当前恢复轮次起点，人工重启重置此字段但保留原创建时间';
ALTER TABLE idempotency_request ADD KEY ix_order_request_business(business_no);
CREATE TABLE order_creation_intent (
  order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求受理即固定的订单号，报价前也可查询恢复',
  user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '买家编号，查询和取消必须本人',
  payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定支付号，B3前只保存不调用支付',
  reservation_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定预占编号，所有恢复必须复用',
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '包含地址及合并排序SKU的规范请求SHA256',
  request_payload JSON NULL COMMENT '报价前恢复所需请求，包含地址禁止记录日志，快照入单后清空',
  state ENUM('QUOTING','ORDER_CREATED','REJECTED','CANCELLED') NOT NULL DEFAULT 'QUOTING' COMMENT '创建意图状态，不等同订单履约状态',
  result_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前明确结果码，不将超时当终态失败',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '受理时间UTC，恢复不能延长支付窗口',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间UTC',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '本地受控修改版本',
  PRIMARY KEY(order_no),
  UNIQUE KEY uk_creation_payment(payment_no),
  UNIQUE KEY uk_creation_reservation(reservation_no),
  KEY ix_creation_owner(user_id,created_at),
  CONSTRAINT ck_creation_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报价前先落地的下单意图及隐私恢复材料';
