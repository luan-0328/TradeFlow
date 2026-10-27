-- 仅连接到 trade_user 执行；Flyway 默认 schema=trade_user。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE `user_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `username` VARCHAR(64) NOT NULL COMMENT '登录名，应用层统一规范化后保存',
  `password_hash` VARCHAR(255) NOT NULL COMMENT 'BCrypt 或 Argon2 密码哈希，禁止保存明文',
  `display_name` VARCHAR(64) NOT NULL COMMENT '用户显示名称',
  `status` ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态：正常、停用',
  `token_version` BIGINT NOT NULL DEFAULT 0 COMMENT '令牌撤销版本，停用或强制退出时递增',
  `last_login_at` DATETIME(3) NULL COMMENT '最近成功登录时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_account_1` (`username`),
  CONSTRAINT `ck_user_account_1` CHECK (token_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户账号及登录安全状态';

CREATE TABLE `role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `role_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '角色编码，如 USER、MERCHANT_ADMIN',
  `role_name` VARCHAR(64) NOT NULL COMMENT '中文角色名称',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_1` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='受控角色定义';

CREATE TABLE `user_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户账号主键',
  `role_id` BIGINT UNSIGNED NOT NULL COMMENT '角色主键',
  `status` ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '角色关联有效状态，授权查询只读取 ACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role_1` (`user_id`,`role_id`),
  CONSTRAINT `fk_user_role_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_role_2` FOREIGN KEY (`role_id`) REFERENCES `role` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户与角色关联，撤销时停用而非物理删除';

CREATE TABLE `merchant_member` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户账号主键',
  `merchant_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商家业务编号',
  `status` ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '商家成员状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_member_1` (`merchant_id`,`user_id`),
  CONSTRAINT `fk_merchant_member_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商家成员及业务归属';

CREATE TABLE `idempotency_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `actor_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户、商家、平台或服务',
  `actor_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '调用主体编号',
  `operation` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作编码',
  `idempotency_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端或调用方稳定幂等键',
  `request_hash` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求 SHA-256',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对应业务编号',
  `state` ENUM('PROCESSING','SUCCEEDED','REJECTED') NOT NULL DEFAULT 'PROCESSING' COMMENT '业务请求处理状态',
  `result_code` VARCHAR(64) NULL COMMENT '结果码',
  `result_snapshot` JSON NULL COMMENT '可安全返回的结果快照',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idempotency_request_1` (`actor_type`,`actor_id`,`operation`,`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='请求幂等及同键异参检测';

CREATE TABLE `process_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `task_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务编号',
  `task_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务类型',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务编号',
  `step` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '流程步骤',
  `payload` JSON NOT NULL COMMENT '恢复所需业务参数，不含密钥',
  `state` ENUM('READY','RUNNING','SUCCEEDED','MANUAL_REVIEW','CANCELLED') NOT NULL DEFAULT 'READY' COMMENT '任务状态',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '执行次数',
  `next_retry_at` DATETIME(3) NOT NULL COMMENT '下次执行时间',
  `lease_owner` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '任务领取者',
  `lease_until` DATETIME(3) NULL COMMENT '租约截止',
  `claim_version` BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增的令牌',
  `last_error` VARCHAR(1000) NULL COMMENT '脱敏失败原因',
  `manual_review_at` DATETIME(3) NULL COMMENT '升级人工时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，统一存储 UTC',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本号，每次受控修改递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_process_task_1` (`task_no`),
  UNIQUE KEY `uk_process_task_2` (`task_type`,`business_no`,`step`),
  KEY `ix_process_task_1` (`state`,`next_retry_at`,`id`),
  KEY `ix_process_task_2` (`lease_until`,`id`),
  CONSTRAINT `ck_process_task_1` CHECK (attempt_count >= 0),
  CONSTRAINT `ck_process_task_2` CHECK (claim_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可抢占可恢复的本地业务任务';

CREATE TABLE `audit_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部自增主键，不能替代业务授权校验',
  `actor_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作者类型',
  `actor_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作者编号',
  `action` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作编码',
  `business_no` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '相关业务编号',
  `request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求编号',
  `trace_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '追踪编号',
  `reason` VARCHAR(1000) NOT NULL COMMENT '操作理由',
  `change_summary` JSON NOT NULL COMMENT '脱敏前后摘要',
  `result` ENUM('SUCCEEDED','FAILED') NOT NULL DEFAULT 'SUCCEEDED' COMMENT '操作结果',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一存储 UTC',
  PRIMARY KEY (`id`),
  KEY `ix_audit_log_1` (`business_no`,`created_at`,`id`),
  KEY `ix_audit_log_2` (`actor_id`,`created_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变敏感操作审计';
