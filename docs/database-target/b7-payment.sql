-- B7 本库对账任务、不可变暂存证据与可恢复修复命令；禁止跨库访问。
SET NAMES utf8mb4;
CREATE TABLE reconciliation_run (
 task_no VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定业务任务编号',
 state VARCHAR(24) NOT NULL COMMENT '持久任务或差异状态',
 payload LONGTEXT NOT NULL COMMENT '不可丢失的请求快照与阶段检查点JSON',
 lease_owner VARCHAR(64) NULL COMMENT '当前领取执行者实例编号',
 claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增的围栏版本',
 lease_until DATETIME(6) NULL COMMENT '30秒租约到期时间UTC',
 consecutive_failures INT NOT NULL DEFAULT 0 COMMENT '连续依赖失败预算，正常进度归零',
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '下次允许执行时间UTC',
 last_error VARCHAR(128) NULL COMMENT '最近异常类型，不保存密钥和远程响应',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间UTC',
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近进度更新时间UTC',
 PRIMARY KEY(task_no), KEY ix_due(state,next_at,lease_until)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='对账run持久状态及租约围栏';
CREATE TABLE reconciliation_issue (
 task_no VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定业务任务编号',
 state VARCHAR(24) NOT NULL COMMENT '持久任务或差异状态',
 payload LONGTEXT NOT NULL COMMENT '不可丢失的请求快照与阶段检查点JSON',
 lease_owner VARCHAR(64) NULL COMMENT '当前领取执行者实例编号',
 claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增的围栏版本',
 lease_until DATETIME(6) NULL COMMENT '30秒租约到期时间UTC',
 consecutive_failures INT NOT NULL DEFAULT 0 COMMENT '连续依赖失败预算，正常进度归零',
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '下次允许执行时间UTC',
 last_error VARCHAR(128) NULL COMMENT '最近异常类型，不保存密钥和远程响应',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间UTC',
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近进度更新时间UTC',
 PRIMARY KEY(task_no), KEY ix_due(state,next_at,lease_until)
, fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '通道类型业务号差异类型稳定摘要', transaction_type VARCHAR(16) NOT NULL COMMENT 'PAYMENT或REFUND', business_no VARCHAR(64) NOT NULL COMMENT '支付或退款业务编号', issue_type VARCHAR(32) NOT NULL COMMENT '差异分类', action_no VARCHAR(48) NULL COMMENT '最近修复复查动作编号', UNIQUE KEY uk_fingerprint(fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='对账issue持久状态及租约围栏';
CREATE TABLE reconciliation_action (
 task_no VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定业务任务编号',
 state VARCHAR(24) NOT NULL COMMENT '持久任务或差异状态',
 payload LONGTEXT NOT NULL COMMENT '不可丢失的请求快照与阶段检查点JSON',
 lease_owner VARCHAR(64) NULL COMMENT '当前领取执行者实例编号',
 claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增的围栏版本',
 lease_until DATETIME(6) NULL COMMENT '30秒租约到期时间UTC',
 consecutive_failures INT NOT NULL DEFAULT 0 COMMENT '连续依赖失败预算，正常进度归零',
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '下次允许执行时间UTC',
 last_error VARCHAR(128) NULL COMMENT '最近异常类型，不保存密钥和远程响应',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间UTC',
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近进度更新时间UTC',
 PRIMARY KEY(task_no), KEY ix_due(state,next_at,lease_until)
, issue_no VARCHAR(48) NULL COMMENT '关联差异编号，定向查询可为空', origin_run_no VARCHAR(48) NULL COMMENT '自动动作原运行编号，人工及定向动作留空', KEY ix_issue(issue_no,state), KEY ix_origin_run(origin_run_no,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='对账action持久状态及租约围栏';
CREATE TABLE reconciliation_stage_line (
 run_no VARCHAR(48) NOT NULL COMMENT '原对账运行编号',
 position_no BIGINT NOT NULL COMMENT '快照连续位置，从一开始',
 transaction_type VARCHAR(16) NOT NULL COMMENT 'PAYMENT或REFUND',
 business_no VARCHAR(64) NOT NULL COMMENT '通道业务编号',
 payload LONGTEXT NOT NULL COMMENT '冻结快照原始事实JSON',
 PRIMARY KEY(run_no,position_no), UNIQUE KEY uk_business(run_no,transaction_type,business_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='完整校验前只下载暂存，禁止产生缺失差异';
CREATE TABLE reconciliation_observation (
 run_no VARCHAR(48) NOT NULL COMMENT '观察所属运行编号',
 issue_no VARCHAR(48) NOT NULL COMMENT '稳定差异编号',
 evidence LONGTEXT NOT NULL COMMENT '本次运行独立差异证据JSON',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '观察时间UTC',
 PRIMARY KEY(run_no,issue_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='每轮对账差异观察，历史运行不被覆盖';
CREATE TABLE reconciliation_command (
 actor VARCHAR(64) NOT NULL COMMENT '平台操作者或系统身份',
 operation_name VARCHAR(96) NOT NULL COMMENT '幂等操作与目标作用域',
 idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '调用方稳定幂等键',
 request_hash CHAR(64) NOT NULL COMMENT '请求参数和原因的规范摘要',
 request_body LONGTEXT NOT NULL COMMENT '原命令参数与操作原因JSON审计',
 result_no VARCHAR(48) NULL COMMENT '原受理运行或动作编号',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '受理时间UTC',
 PRIMARY KEY(actor,operation_name,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='平台命令幂等与审计，不直接操作资金';

-- 关键对账读取按业务号检查唯一流水，并给通道更新时间与旧未决扫描提供索引。
ALTER TABLE payment_ledger ADD KEY ix_b7_business_entry(business_no,entry_type);
ALTER TABLE payment_order ADD KEY ix_b7_channel_updated(channel_updated_at,payment_no);
ALTER TABLE refund_order ADD KEY ix_b7_channel_updated(channel_updated_at,refund_no), ADD KEY ix_b7_pending_created(status,created_at,refund_no);
