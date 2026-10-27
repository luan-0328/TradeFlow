-- U1/U2：追加迁移，不修改已有发布版本。商家停用保留历史成员和处置身份。
CREATE TABLE merchant (
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商家业务编号',
 name VARCHAR(120) NOT NULL COMMENT '商家名称',
 description VARCHAR(500) NOT NULL COMMENT '商家经营资料',
 status ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '经营状态，不代替账号和成员权限',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '资料与经营状态并发版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
 PRIMARY KEY(merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家资料与独立经营状态';
INSERT INTO merchant(merchant_id,name,description) VALUES('merchant_demo','历史演示商家','保留历史业务归属');
CREATE TABLE merchant_application (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '入驻申请编号',
 user_id BIGINT UNSIGNED NOT NULL COMMENT '申请账号',
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申请商家编号，拒绝后可使用新编号再申请',
 name VARCHAR(120) NOT NULL COMMENT '申请商家名称',
 description VARCHAR(500) NOT NULL COMMENT '申请经营资料',
 status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING' COMMENT '审核状态',
 reason VARCHAR(500) NULL COMMENT '平台审核原因',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '审核条件版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '申请UTC时间',
 PRIMARY KEY(id), UNIQUE KEY uk_application_merchant(merchant_id), KEY ix_application_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家入驻申请和审核事实';
CREATE TABLE user_address (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '地址编号',
 user_id BIGINT UNSIGNED NOT NULL COMMENT '归属用户编号',
 recipient VARCHAR(64) NOT NULL COMMENT '收件人',
 phone VARCHAR(32) NOT NULL COMMENT '联系电话',
 region VARCHAR(255) NOT NULL DEFAULT '' COMMENT '省市区，兼容历史缺省空值并由用户补填',
 address VARCHAR(500) NOT NULL COMMENT '完整收货地址',
 default_address BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否默认地址',
 deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否软删除，旧订单快照不受影响',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本',
 default_owner BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN default_address=TRUE AND deleted=FALSE THEN user_id ELSE NULL END) STORED COMMENT '默认地址唯一归属防并发',
 PRIMARY KEY(id), UNIQUE KEY uk_address_default(default_owner), KEY ix_address_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户地址簿，默认地址按账号串行维护';
CREATE TABLE user_notification (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '通知编号',
 user_id BIGINT UNSIGNED NOT NULL COMMENT '通知接收用户',
 business_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定通知业务去重键',
 title VARCHAR(120) NOT NULL COMMENT '通知标题',
 content VARCHAR(1000) NOT NULL COMMENT '通知正文，不作为交易成功权威事实',
 read_at DATETIME(3) NULL COMMENT '首次已读UTC时间',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '通知生成UTC时间',
 PRIMARY KEY(id), UNIQUE KEY uk_notification_business(user_id,business_key), KEY ix_notification_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户站内通知，重复业务通知幂等';
