package com.tradeflow.product.infrastructure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 图片只属于商品及其权威商家；字节与元数据同库同事务，拒绝客户端指定路径或外部URL。 */
@Mapper
public interface ProductImageMapper {
    record ImageRow(String imageNo,String productId,String merchantId,String contentType,long sizeBytes) { }
    /** 在商品行锁内计数，保证并发上传不超过十张。 */
    int count(@Param("productId") String productId);
    /** 规范化PNG字节与业务幂等结果同事务提交；不接收原始文件名。 */
    int insert(@Param("imageNo") String imageNo,@Param("productId") String productId,@Param("merchantId") String merchantId,@Param("content") byte[] content);
    /** 列表不读取BLOB，公开读取必须联查上架商品，管理读取按已授权归属过滤。 */
    List<ImageRow> list(@Param("productId") String productId,@Param("merchantId") String merchantId);
    /** 公开二进制读取也在SQL复核商品上架与归属绑定，不能凭图片号泄露草稿。 */
    Map<String,Object> content(@Param("imageNo") String imageNo);
    /** 草稿图片仅按最新授权商家读取，不能凭编号跨店查看。 */
    Map<String,Object> merchantContent(@Param("imageNo") String imageNo,@Param("merchantId") String merchantId);
    /** 软删除保留历史审计；重复删除无新增业务效果。 */
    int remove(@Param("productId") String productId,@Param("merchantId") String merchantId,@Param("imageNo") String imageNo);
}
