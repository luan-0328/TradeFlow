-- B7：冻结账单与完整性投影，不修改支付和退款资金事实。
SET NAMES utf8mb4;
CREATE TABLE bill_projection_scenario (
 request_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '账单请求幂等编号，首次冻结或开发配置时绑定',
 mode ENUM('NONE','INCOMPLETE','BAD_CHECKSUM','TRUNCATED_PAGE') NOT NULL COMMENT '固定账单投影故障，NONE为正常账单',
 actor_id VARCHAR(64) NOT NULL COMMENT '首次配置人，正常账单为SYSTEM',
 reason VARCHAR(500) NOT NULL COMMENT '首次配置原因，须可审计',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '配置时间UTC',
 PRIMARY KEY(request_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按账单请求号一次绑定的开发投影，不改变资金事实';
CREATE TABLE bill_snapshot (
 snapshot_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定不可变账单快照编号',
 request_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一绑定账期币种与截止点的请求号',
 request_json TEXT NOT NULL COMMENT '原始规范请求JSON，重放须完整匹配元数据',
 projection_mode ENUM('NONE','INCOMPLETE','BAD_CHECKSUM','TRUNCATED_PAGE') NOT NULL COMMENT '冻结时绑定的不可变投影模式',
 manifest_json TEXT NULL COMMENT '完整冻结清单JSON，含水位计数成功金额摘要，仅事务内暂态为空',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '快照创建时间UTC',
 PRIMARY KEY(snapshot_no),UNIQUE KEY uk_bill_snapshot_request(request_no),
 CONSTRAINT fk_bill_snapshot_scenario FOREIGN KEY(request_no) REFERENCES bill_projection_scenario(request_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通道一致视图冻结账单，头与全部行在单事务内提交后不可变';
CREATE TABLE bill_snapshot_line (
 snapshot_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属不可变快照编号',
 position BIGINT NOT NULL COMMENT '类型及业务号排序后从一开始连续的稳定游标',
 transaction_type ENUM('PAYMENT','REFUND') NOT NULL COMMENT '支付或退款终态事实类型',
 business_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付号或退款号，按类型唯一',
 payload TEXT NOT NULL COMMENT '规范BillLine JSON，冻结完整关联金额终态与通道流水版本时间',
 PRIMARY KEY(snapshot_no,position),UNIQUE KEY uk_bill_line_business(snapshot_no,transaction_type,business_no),
 CONSTRAINT fk_bill_line_snapshot FOREIGN KEY(snapshot_no) REFERENCES bill_snapshot(snapshot_no),
 CONSTRAINT ck_bill_line_position CHECK(position>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变账单行，不实时拼接，不允许资金状态被投影配置修改';

-- 时间范围与稳定业务号组合索引支持账期扫描，不改既有资金事实。
ALTER TABLE channel_transaction ADD KEY ix_b7_channel_bill_time(updated_at,payment_no);
ALTER TABLE channel_refund ADD KEY ix_b7_channel_refund_bill_time(updated_at,refund_no);
