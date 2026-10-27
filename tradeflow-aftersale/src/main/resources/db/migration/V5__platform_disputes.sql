CREATE TABLE platform_dispute (
 dispute_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '平台争议编号',
 aftersale_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原售后编号，权益和证据仍归原单',
 user_id BIGINT NOT NULL COMMENT '申请买家编号',
 idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申请人原请求键',
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原售后和申请依据摘要',
 reason VARCHAR(1000) NOT NULL COMMENT '申请平台介入原因',
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN待裁定，DECIDED已裁定',
 decision VARCHAR(32) NULL COMMENT 'APPROVE原额审核通过，REJECT拒绝，UPHOLD保持原处理',
 decision_reason VARCHAR(1000) NULL COMMENT '平台裁定依据',
 decided_by BIGINT NULL COMMENT '平台裁定人编号',
 version BIGINT NOT NULL DEFAULT 1 COMMENT '裁定乐观版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '申请时间UTC',
 decided_at DATETIME(3) NULL COMMENT '裁定时间UTC',
 PRIMARY KEY(dispute_no),UNIQUE KEY uk_dispute_key(user_id,idempotency_key),KEY ix_dispute_aftersale(aftersale_no),KEY ix_dispute_status(status,created_at),
 CONSTRAINT ck_dispute_status CHECK(status IN ('OPEN','DECIDED')),
 CONSTRAINT ck_dispute_decision CHECK(decision IS NULL OR decision IN ('APPROVE','REJECT','UPHOLD'))
) ENGINE=InnoDB COMMENT='平台争议裁定，原售后锁内执行业务迁移，不直接写资金成功';
