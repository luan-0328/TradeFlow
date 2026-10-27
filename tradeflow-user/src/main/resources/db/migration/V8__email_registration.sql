-- 邮箱直接作为新账号登录名；历史用户名和密码不变，不把旧账号伪标记为已验证邮箱。
ALTER TABLE user_account
  MODIFY COLUMN username VARCHAR(254) NOT NULL COMMENT '登录标识：新注册为规范化邮箱，历史用户名继续兼容',
  ADD COLUMN email_verified_at DATETIME(3) NULL COMMENT '注册邮箱验证完成时间，历史未验证账号为空' AFTER display_name;
