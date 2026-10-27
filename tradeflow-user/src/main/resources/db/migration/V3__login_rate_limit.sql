CREATE TABLE login_rate_limit (
  attempt_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化登录名的SHA256，不保存密码或原始用户名',
  window_started_at DATETIME(3) NOT NULL COMMENT '当前限额窗口开始时间，UTC',
  attempts INT NOT NULL COMMENT '本窗口尝试次数，成功和失败均计数',
  PRIMARY KEY (attempt_key),
  KEY ix_login_rate_window (window_started_at),
  CONSTRAINT ck_login_rate_attempts CHECK (attempts >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='多实例共享的登录尝试限额，每账号15分钟最多10次';
