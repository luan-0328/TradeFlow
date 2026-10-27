CREATE TABLE authorization_guard (
  id BIGINT NOT NULL COMMENT '授权写操作串行化锚点，当前固定1',
  description VARCHAR(100) NOT NULL COMMENT '锚点业务含义，禁止业务代码修改',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台授权写互斥，防止并发移除最后一个管理员';
INSERT INTO authorization_guard(id,description) VALUES(1,'平台账号与角色授权写保护');
