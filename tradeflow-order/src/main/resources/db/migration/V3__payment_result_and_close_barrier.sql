ALTER TABLE orders ADD COLUMN close_phase ENUM('NONE','PAYMENT_REQUIRED','CHANNEL_CLOSED','LATE_PAYMENT') NOT NULL DEFAULT 'NONE' COMMENT '支付关闭证据阶段，待支付关闭不得跳过通道终态确认';
CREATE TABLE order_payment_fact (
  event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付服务持久通知事件号，同号同参ACK',
  payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单固定支付号',
  order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本库权威订单号',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '通知事实规范摘要，防止同号异参',
  status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已验真可信通道终态',
  channel_version BIGINT NOT NULL COMMENT '通道事实版本，不能据旧通知倒退终态',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '通知应用记录时间UTC',
  PRIMARY KEY(event_id),
  KEY ix_order_payment_fact(order_no,created_at),
  CONSTRAINT fk_order_payment_fact FOREIGN KEY(order_no) REFERENCES orders(order_no),
  CONSTRAINT ck_order_payment_fact_version CHECK(channel_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付HTTP结果可靠接收证据，不是MQ消费已实现';
