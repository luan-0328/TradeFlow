CREATE TABLE merchant_shipping_rule (
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商家编号',
 fee_cents BIGINT NOT NULL DEFAULT 0 COMMENT '每个商家子订单固定运费，整数分',
 version BIGINT NOT NULL DEFAULT 1 COMMENT '乐观更新版本',
 PRIMARY KEY(merchant_id),CONSTRAINT ck_shipping_fee CHECK(fee_cents BETWEEN 0 AND 1000000)
) ENGINE=InnoDB COMMENT='商家固定运费规则';
CREATE TABLE order_charge_snapshot (
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原订单编号',
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威商家归属',
 shipping_cents BIGINT NOT NULL COMMENT '受理时确认的运费，按购买件均分并保留尾差',
 shipping_version BIGINT NOT NULL COMMENT '受理时运费规则版本',
 PRIMARY KEY(order_no),CONSTRAINT ck_charge_shipping CHECK(shipping_cents BETWEEN 0 AND 1000000)
) ENGINE=InnoDB COMMENT='不可变订单运费快照';
CREATE TABLE trade_group (
 group_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '交易组编号',
 user_id BIGINT NOT NULL COMMENT '买家编号',
 idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '买家作用域原请求键',
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范请求摘要，不保存明文地址',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(group_no),UNIQUE KEY uk_group_request(user_id,idempotency_key),KEY ix_group_user(user_id,created_at)
) ENGINE=InnoDB COMMENT='跨商家独立支付交易组';
CREATE TABLE trade_group_child (
 group_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '交易组编号',
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '子订单所属商家',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立履约支付订单编号',
 PRIMARY KEY(group_no,merchant_id),UNIQUE KEY uk_group_child_order(order_no)
) ENGINE=InnoDB COMMENT='交易组子订单绑定，和创建意图同事务提交';

-- 商品原单价保持不变，行实付包含分摊运费，退款汇总受行实付约束。
ALTER TABLE order_item DROP CHECK ck_order_item_3, ADD CONSTRAINT ck_order_item_paid CHECK(line_amount_cents >= unit_price_cents * quantity);

CREATE TABLE shipping_rule_command (
 actor_id BIGINT NOT NULL COMMENT '操作人编号',
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原运费修改请求键',
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商家版本金额摘要',
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原商家编号',
 fee_cents BIGINT NOT NULL COMMENT '原修改后运费快照',
 rule_version BIGINT NOT NULL COMMENT '原修改后规则版本',
 PRIMARY KEY(actor_id,request_key)
) ENGINE=InnoDB COMMENT='运费修改不可变幂等结果';
