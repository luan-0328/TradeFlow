CREATE TABLE aftersale (
 aftersale_no VARCHAR(35) NOT NULL COMMENT '售后号，首次受理固定',
 order_no VARCHAR(36) NOT NULL COMMENT '订单号',
 user_id VARCHAR(32) NOT NULL COMMENT '买家用户号',
 merchant_id VARCHAR(64) NOT NULL COMMENT '所属商家号',
 type VARCHAR(32) NOT NULL COMMENT '售后类型，本批仅未发货整单',
 reason VARCHAR(500) NOT NULL COMMENT '用户申请原因',
 status VARCHAR(32) NOT NULL COMMENT '售后状态，失败退款仍保留执行义务',
 refund_no VARCHAR(36) NOT NULL COMMENT '当前退款尝试号，未知禁止换号',
 refund_status VARCHAR(32) NOT NULL COMMENT '当前支付退款执行状态',
 amount_cents BIGINT NULL COMMENT '订单授权权威金额，单位分',
 currency VARCHAR(8) NULL COMMENT '订单授权币种',
 last_error VARCHAR(128) NULL COMMENT '稳定错误码，不含远端原文',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
 PRIMARY KEY (aftersale_no), UNIQUE KEY uk_current_refund(refund_no),
 KEY idx_buyer(user_id,aftersale_no), KEY idx_merchant(merchant_id,aftersale_no),
 CONSTRAINT ck_aftersale_amount CHECK(amount_cents IS NULL OR amount_cents>0),
 CONSTRAINT ck_aftersale_type CHECK(type='UNSHIPPED_FULL'),
 CONSTRAINT ck_aftersale_state CHECK(status IN ('CREATING','PENDING_REVIEW','CANCELLING','CANCELLED','REJECTING','REJECTED','REFUND_PROCESSING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='未发货整单售后主单，无买家地址';
CREATE TABLE aftersale_task (
 aftersale_no VARCHAR(35) NOT NULL COMMENT '所属售后号，一单一个恢复任务',
 phase VARCHAR(32) NOT NULL COMMENT '授权、终止或退款恢复阶段',
 status VARCHAR(32) NOT NULL COMMENT 'READY、RUNNING、DONE或MANUAL_REVIEW',
 attempts INT NOT NULL DEFAULT 0 COMMENT '本阶段恢复领取次数，30次人工隔离',
 claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取或换阶段递增的围栏版本',
 lease_until DATETIME(6) NULL COMMENT '领取租约截止时间，过期可回收',
 next_run_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '下次可恢复时间',
 last_error VARCHAR(128) NULL COMMENT '恢复稳定错误码',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
 PRIMARY KEY(aftersale_no),KEY idx_recover(status,next_run_at,lease_until),
 CONSTRAINT ck_task_state CHECK(status IN ('READY','RUNNING','DONE','MANUAL_REVIEW')),
 CONSTRAINT fk_task_aftersale FOREIGN KEY(aftersale_no) REFERENCES aftersale(aftersale_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='售后持久恢复任务与租约围栏';
CREATE TABLE aftersale_idempotency (
 actor_id VARCHAR(32) NOT NULL COMMENT '幂等操作者用户号',
 operation VARCHAR(80) NOT NULL COMMENT '操作及目标售后范围',
 idempotency_key VARCHAR(128) NOT NULL COMMENT '调用方幂等键',
 request_hash CHAR(64) NOT NULL COMMENT '规范请求参数SHA256摘要',
 aftersale_no VARCHAR(35) NOT NULL COMMENT '首次受理固定结果售后号',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '首次受理时间',
 PRIMARY KEY(actor_id,operation,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='用户操作幂等记录，同键异参拒绝';
CREATE TABLE aftersale_refund_attempt (
 refund_no VARCHAR(36) NOT NULL COMMENT '独立退款尝试号',
 aftersale_no VARCHAR(35) NOT NULL COMMENT '所属售后号',
 status VARCHAR(32) NOT NULL COMMENT '支付退款执行状态',
 last_error VARCHAR(128) NULL COMMENT '稳定失败原因码',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '尝试创建时间',
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '事实更新时间',
 PRIMARY KEY(refund_no), KEY idx_attempt_aftersale(aftersale_no),
 CONSTRAINT fk_attempt_aftersale FOREIGN KEY(aftersale_no) REFERENCES aftersale(aftersale_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='售后全部退款尝试历史，未知禁止新增尝试';
CREATE TABLE aftersale_audit (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '审计流水主键',
 actor_id VARCHAR(32) NOT NULL COMMENT '操作人用户号',
 aftersale_no VARCHAR(35) NOT NULL COMMENT '目标售后号',
 operation VARCHAR(32) NOT NULL COMMENT '审核、退款重试或任务恢复操作',
 reason VARCHAR(500) NOT NULL COMMENT '操作原因',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '操作时间',
 PRIMARY KEY(id), KEY idx_audit_aftersale(aftersale_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='售后商家审核与人工恢复原因审计';
