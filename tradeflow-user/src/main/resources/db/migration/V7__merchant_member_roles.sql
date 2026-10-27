-- 每商家独立职级：仅迁移历史有效全局商家管理员，不让未来新成员继承旧全局角色。
ALTER TABLE merchant_member ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'OPERATOR' COMMENT '本商家成员职级：ADMIN管理员、OPERATOR操作员';
UPDATE merchant_member mm JOIN user_role ur ON ur.user_id=mm.user_id AND ur.status='ACTIVE'
JOIN role r ON r.id=ur.role_id AND r.role_code='MERCHANT_ADMIN'
SET mm.role='ADMIN' WHERE mm.status='ACTIVE';
ALTER TABLE merchant_member ADD CONSTRAINT ck_merchant_member_role CHECK (role IN ('ADMIN','OPERATOR'));
