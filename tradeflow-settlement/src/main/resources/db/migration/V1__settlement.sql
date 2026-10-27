SET NAMES utf8mb4;
SET time_zone = '+00:00';
CREATE TABLE settlement_task (
 task_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定任务号',
 lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租约执行者',
 claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '领取版本围栏',
 scan_cursor BIGINT NOT NULL DEFAULT 0 COMMENT '资金账本扫描游标，周期归零修复提交空洞',
 candidate_cursor BIGINT NOT NULL DEFAULT 0 COMMENT '资格扫描游标，每轮推进',
 lease_until DATETIME(3) NULL COMMENT '租约截止UTC',
 last_error VARCHAR(1000) NULL COMMENT '最近失败，保留游标待恢复',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(task_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全局扫描恢复任务及版本租约';

CREATE TABLE settlement_account (
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威商家编号',
 debt_cents BIGINT NOT NULL DEFAULT 0 COMMENT '已模拟完成后退款尚待抵扣负债分',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(merchant_id), CHECK(debt_cents>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商家模拟完成后退款负债';

CREATE TABLE settlement_item (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付号幂等',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威订单编号',
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威商家归属',
 paid_cents BIGINT NOT NULL DEFAULT 0 COMMENT '原支付本金分',
 refund_cents BIGINT NOT NULL DEFAULT 0 COMMENT '累计退款本金分',
 fee_cents BIGINT NOT NULL DEFAULT 0 COMMENT '当前剩余本金对应服务费分',
 fee_bps INT NOT NULL COMMENT '单据费率万分比快照',
 freeze_days INT NOT NULL COMMENT '单据冻结天数快照',
 status ENUM('FROZEN','SIMULATED_COMPLETED') NOT NULL DEFAULT 'FROZEN' COMMENT '冻结待资格或模拟完成',
 batch_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '模拟完成批次号',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(id), UNIQUE KEY uk_item_payment(payment_no), UNIQUE KEY uk_item_order(order_no), KEY ix_candidate(status,id), CHECK(paid_cents>0 AND refund_cents>=0 AND refund_cents<=paid_cents), CHECK(fee_cents>=0 AND fee_cents<=paid_cents-refund_cents), CHECK(fee_bps BETWEEN 0 AND 10000), CHECK(freeze_days BETWEEN 0 AND 3650)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='原支付结算单及冻结费率快照';

CREATE TABLE settlement_fact (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原资金事实类型',
 business_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付号或退款号',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付号',
 order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原订单号',
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已核对商家归属',
 amount_cents BIGINT NOT NULL DEFAULT 0 COMMENT '正数本金分',
 currency CHAR(3) NOT NULL COMMENT '币种CNY',
 occurred_at DATETIME(3) NOT NULL COMMENT '原资金事实发生时间UTC',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(id), UNIQUE KEY uk_fact(kind,business_no), CHECK(amount_cents>0), CHECK(currency='CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变支付退款事实镜像';

CREATE TABLE settlement_ledger (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 entry_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定事实或结算号唯一键',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付号',
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威商家编号',
 entry_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '支付退款或模拟完成',
 principal_delta BIGINT NOT NULL DEFAULT 0 COMMENT '本金变动分，退款为负',
 fee_delta BIGINT NOT NULL DEFAULT 0 COMMENT '平台服务费变动分，冲回为负',
 payable_delta BIGINT NOT NULL DEFAULT 0 COMMENT '冻结或待结算应付变动分',
 simulated_delta BIGINT NOT NULL DEFAULT 0 COMMENT '净模拟完成权利变动分，退款可为负',
 batch_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '模拟完成批次号',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(id), UNIQUE KEY uk_entry(entry_key), KEY ix_merchant(merchant_id,id), CHECK(principal_delta-fee_delta=payable_delta+simulated_delta)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加四维守恒结算账本';

CREATE TABLE settlement_batch (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 batch_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定批次号',
 payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原支付号',
 merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商家归属',
 net_cents BIGINT NOT NULL DEFAULT 0 COMMENT '单据净结算分',
 simulated_payout_cents BIGINT NOT NULL DEFAULT 0 COMMENT '扣历史债务后模拟出款分',
 debt_offset_cents BIGINT NOT NULL DEFAULT 0 COMMENT '抵扣历史退款债务分',
 status VARCHAR(32) NOT NULL DEFAULT 'SIMULATED_COMPLETED' COMMENT '仅模拟完成，不代表真实出款',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(id), UNIQUE KEY uk_batch(batch_no), UNIQUE KEY uk_batch_payment(payment_no), CHECK(net_cents=simulated_payout_cents+debt_offset_cents), CHECK(simulated_payout_cents>=0 AND debt_offset_cents>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单支付模拟结算批次，不产生真实出款';

CREATE TABLE settlement_reconciliation (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 run_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '每次核对唯一编号',
 result VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PASS或DIFFERENCE',
 detail JSON NOT NULL COMMENT '各单据差异明细，仅对本地已导入事实负责',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(id), UNIQUE KEY uk_reconcile(run_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='结算本地账单守恒核对留痕';

CREATE TABLE settlement_audit (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
 actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '最新核验平台操作者',
 action VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结算运行或核对命令',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台结算操作追加审计';

