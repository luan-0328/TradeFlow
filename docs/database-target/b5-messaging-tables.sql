-- B5 模板：分别复制为 order/payment/aftersale 所属库的新 Flyway 迁移，禁止改已发布迁移。
-- 不含 USE/跨库外键；迁移连接统一 UTC。应用账户只访问本库。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE inbox_message (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
  consumer_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本服务消费者名称',
  event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源稳定事件编号',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整信封规范化JSON的SHA256摘要，包含扩展字段',
  status ENUM('RECEIVED','APPLIED','DEFERRED','QUARANTINED') NOT NULL COMMENT '接收、已应用、数据库延期或隔离状态',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次可靠接收时间，UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后状态变化时间，UTC',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inbox_consumer_event (consumer_name,event_id),
  KEY ix_inbox_status (status,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消费事务防重和原事件内容绑定';

CREATE TABLE deferred_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
  deferred_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '安全稳定延期记录编号，平台恢复入口使用',
  consumer_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '负责恢复的本服务消费者',
  event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '始终保留的原事件编号',
  event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原事件类型',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '与inbox绑定的完整信封摘要',
  raw_body LONGBLOB NOT NULL COMMENT '原始消息字节，重试不得修改或丢失扩展字段',
  status ENUM('READY','RUNNING','APPLIED','MANUAL_REVIEW','QUARANTINED') NOT NULL DEFAULT 'READY' COMMENT '延期等待、执行、已应用或人工隔离状态',
  attempt_count INT NOT NULL DEFAULT 0 COMMENT '本轮恢复领取次数，最多十次',
  cycle_started_at DATETIME(3) NOT NULL COMMENT '本轮自动恢复起点，人工原文重放开启新一轮',
  next_retry_at DATETIME(3) NOT NULL COMMENT '下次恢复时间，UTC',
  lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前领取执行者',
  lease_until DATETIME(3) NULL COMMENT '三十秒租约截止，UTC',
  claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增的旧执行者围栏',
  last_error VARCHAR(1000) NULL COMMENT '稳定脱敏原因码，不存任意异常正文',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次延期时间，UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后变化时间，UTC',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '本地记录乐观版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_deferred_id (deferred_id),
  UNIQUE KEY uk_deferred_consumer_event (consumer_name,event_id),
  KEY ix_deferred_due (status,next_retry_at,id),
  KEY ix_deferred_lease (lease_until,id),
  CONSTRAINT ck_deferred_attempt CHECK (attempt_count >= 0),
  CONSTRAINT ck_deferred_claim CHECK (claim_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据库可靠接管的原事件延期恢复任务';

CREATE TABLE message_failure (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
  failure_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '安全稳定隔离记录编号，平台恢复入口使用',
  consumer_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原目标消费者',
  event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原事件编号，无法解析时由正文摘要稳定派生',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整规范化信封或非法原文SHA256摘要',
  raw_body LONGBLOB NOT NULL COMMENT '隔离原始字节，DLQ发送和人工重放不得修改',
  reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Schema、绑定、冲突或超限的稳定原因码',
  disposition ENUM('QUARANTINED','RETRY_REQUESTED','RESOLVED') NOT NULL DEFAULT 'QUARANTINED' COMMENT '业务隔离、已申请原文重放或重放已应用，与broker送达状态独立',
  status ENUM('READY','RUNNING','SENT','MANUAL_REVIEW') NOT NULL DEFAULT 'READY' COMMENT '独立DLQ发送器状态，SENT仅表示broker已确认且无return',
  attempt_count INT NOT NULL DEFAULT 0 COMMENT '本轮DLQ发送领取次数',
  cycle_started_at DATETIME(3) NOT NULL COMMENT '本轮DLQ发布恢复起点，UTC',
  next_retry_at DATETIME(3) NOT NULL COMMENT '下次DLQ发布时间，UTC',
  lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'DLQ发布领取者',
  lease_until DATETIME(3) NULL COMMENT '三十秒发送租约截止，UTC',
  claim_version BIGINT NOT NULL DEFAULT 0 COMMENT '每次DLQ领取递增的围栏',
  published_at DATETIME(3) NULL COMMENT 'DLQ消息确认送达时间，UTC，不表示业务成功',
  last_error VARCHAR(1000) NULL COMMENT '最近脱敏发布错误码',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次隔离时间，UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后变化时间，UTC',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '本地记录乐观版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_failure_id (failure_id),
  UNIQUE KEY uk_failure_original (consumer_name,event_id,payload_hash),
  KEY ix_failure_due (status,next_retry_at,id),
  KEY ix_failure_lease (lease_until,id),
  CONSTRAINT ck_failure_attempt CHECK (attempt_count >= 0),
  CONSTRAINT ck_failure_claim CHECK (claim_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可恢复消息原文隔离及独立可靠DLQ发布';

CREATE TABLE message_operation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
  operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定操作审计编号',
  kind ENUM('outbox','deferred','failure') NOT NULL COMMENT '原记录类型白名单',
  target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原事件编号或原延期隔离记录编号',
  actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Controller已核验最新权限的平台操作者',
  reason VARCHAR(1000) NOT NULL COMMENT '人工操作必填处置原因',
  before_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '恢复前状态',
  action VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原编号重试或原文业务重放动作',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '与恢复请求同事务提交的审计时间，UTC',
  PRIMARY KEY (id),
  UNIQUE KEY uk_message_operation_id (operation_id),
  KEY ix_message_operation_target (kind,target_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加的消息受控恢复操作审计';
