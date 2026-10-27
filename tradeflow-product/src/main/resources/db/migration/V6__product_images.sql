-- U1/U2 商品图片安全存储；不修改历史商品和报价快照，不创建无来源库存。
CREATE TABLE product_image (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '图片内部主键，仅用于稳定排序',
    image_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的不可变图片编号',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '所属商品主键，禁止跨商品迁移',
    merchant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '上传时复核的商品商家归属',
    content_type VARCHAR(32) CHARACTER SET ascii NOT NULL COMMENT '服务端规范化后的图片媒体类型，仅image/png',
    size_bytes BIGINT NOT NULL COMMENT '规范化PNG内容长度，单位字节',
    content MEDIUMBLOB NOT NULL COMMENT '已校验尺寸并重新编码的PNG字节，不保存原始上传内容',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记，删除后公开读取统一不存在',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，统一UTC',
    PRIMARY KEY(id),
    UNIQUE KEY uk_product_image_no(image_no),
    KEY ix_product_image_owner(product_id,merchant_id,deleted,id),
    CONSTRAINT fk_product_image_product FOREIGN KEY(product_id) REFERENCES product(id),
    CONSTRAINT ck_product_image_size CHECK(size_bytes BETWEEN 1 AND 5242880),
    CONSTRAINT ck_product_image_deleted CHECK(deleted IN(0,1)),
    CONSTRAINT ck_product_image_type CHECK(content_type='image/png')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品安全图片，元数据与内容及幂等同事务提交';
