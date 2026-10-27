CREATE TABLE login_entry_window (
  attempt_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '账号或socket来源作用域与规范化值的SHA256，不保存密码或原始身份',
  window_id BIGINT NOT NULL COMMENT '数据库UTC秒除以配置窗口秒数所得窗口编号',
  attempts INT NOT NULL COMMENT '窗口内失败或未决验密次数，来源窗口累计所有请求，饱和到上限加一',
  updated_at DATETIME(3) NOT NULL COMMENT '最后计数更新时间，UTC，仅用于过期清理',
  PRIMARY KEY (attempt_key),
  KEY ix_login_entry_updated (updated_at),
  CONSTRAINT ck_login_entry_attempts CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='多实例共享登录前准入窗口，账号与来源分别约束，成功只释放本次账号占用';
