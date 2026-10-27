# 商品模块 U1/U2 开发契约（尚未执行验证）

本批仅修改本地源码；未运行Maven、测试、迁移或服务，没有连接服务器。既有 ProductWrite、ProductEdit、ProductView、SKU及内部预占record构造器保持兼容。

## 商家与逻辑默认仓库

商家操作继续用 AuthorityClient.merchant(jwt,boolean) 查询最新AuthContext验证账号、token_version、角色，随后 selectedMerchant(context) 读取 X-Merchant-Id 作为选择并验证最新 merchantIds。缺省只允许唯一归属，多归属缺省返回409 MERCHANT_SELECTION_REQUIRED，越权返回403 MERCHANT_FORBIDDEN。创建/编辑商品、SKU、图片、人工库存调整及新报价在事务前查询用户商家状态且要求ACTIVE；历史查询、库存分级处置、预占释放/确认、取消补偿和实物退货不因商家停用阻塞。

商家状态RPC为 GET /internal/v1/merchants/{merchantId}，使用 product 独立服务身份；禁止在本地事务内调用远端。

本阶段所有库存继续以 warehouse_main 表示逻辑默认仓库。不是共享可互操作库存：SKU是全局主键，库存按(warehouse_id,sku_id)唯一，商家外部查询、调整、分级处置和原键查询均通过商品归属强制过滤。预占从已锁定报价的merchant_id核验全部SKU；释放、确认和未发货补偿只使用原预占明细，退货只接受已核验的订单权威收据并约束原预占SKU/数量。没有引入多仓、调拨或猜测回填历史商家。

幂等摘要含所选商家，防止成员切店后重用同键返回旧店资源；同键跨店返回409，不产生新的业务效果。新增摘要包装使用TreeMap固定序列化顺序。旧版本创建商品、库存调整、实物处置的成功键可安全重放：旧摘要须与原规范化输入相同，且原商品快照或原单据/处置记录可证明当前所选商家；仅返回原成功快照，不改写摘要、不重做业务。无法证明归属、同键异参或跨店重放仍返回409；未知结果必须保留原键查询重放。

## 新增及扩展HTTP契约

所有JSON使用既有ApiResponse封装，商品/SKU主键为字符串。

| 方法和路径 | 输入 | 输出/规则 |
| --- | --- | --- |
| GET /api/v1/products | page/pageSize，新增keyword（最多100字）、merchantId可选 | Page<ProductView>，店铺过滤始终只返回ON_SALE；关键词按普通文本匹配名称或描述 |
| GET /api/v1/merchant/products | page/pageSize，新增keyword | 当前已授权所选商家包含草稿的分页；请求不能自行覆盖归属 |
| POST /api/v1/merchant/products/{id}/skus | Idempotency-Key，原WriteSku结构 | 201 SkuView；商品行锁限制最多100规格，创建零库存、审计、幂等同事务；编码仍使用既有全局唯一约束 |
| POST /api/v1/merchant/products/{id}/images | Idempotency-Key，multipart字段file | 201 ImageView；最多10张 |
| GET /api/v1/products/{id}/images | 无 | ImageView[]，仅上架商品 |
| GET /api/v1/merchant/products/{id}/images | JWT与商家选择 | ImageView[]，可查看所属草稿元数据 |
| GET /api/v1/product-images/{imageNo} | 图片号img_和32位小写十六进制 | PNG二进制，只有所属商品上架且图片未删除才可见 |
| GET /api/v1/merchant/product-images/{imageNo} | JWT与商家选择 | 所选店铺图片PNG，可供草稿鉴权fetch转blob预览 |
| DELETE /api/v1/merchant/products/{id}/images/{imageNo} | JWT与商家选择 | REMOVED；软删除，重复无副作用 |

ImageView为{imageNo,productId,url,contentType,sizeBytes}，url为公开相对URL；草稿的公开URL返回404，商家预览使用独立鉴权入口。原ProductView不增加字段，客户端通过独立图片列表展示。内部QuoteRequest.merchantId放宽为merchant_[a-z0-9_]{1,48}。

## 安全存储与迁移

V6__product_images.sql只新增product_image。运行账号需要该表SELECT、INSERT及仅deleted列UPDATE权限，不需要DELETE；迁移账号单独建表。图片库内BLOB避免文件与数据库双写的孤儿恢复问题，备份时随商品库一起备份，须评估图片增长与备份体积。MySQL max_allowed_packet应支持5MiB图片及SQL协议开销，网关与反向代理上传上限需配套不低于6MiB；应用multipart单文件5MiB、总请求6MiB。

不信任文件名、路径、扩展名或MIME。只解码PNG/JPEG，解码前限制单边8000及总像素1600万，原文件与规范化结果均限制5MiB，重新编码PNG去除原始元数据/主动内容；不保存原始文件名与外部URL。二进制读取携带nosniff、CSP sandbox与no-store，公开读取和元数据列表均按商品可见性检查。删除保留元数据与审计，无文件系统路径输入。

## 待统一验证

已添加SelectedMerchantTest、ProductImageValidationTest、ProductSkuAdditionTest、LegacyIdempotencyTest、ProductMapperXmlTest过滤/字节类型契约，以及ReservationReliabilityMysqlTest第二商家与跨店拒绝场景；调整既有ReturnDispositionMysqlTest的选择商家mock。所有测试均尚未运行。后续需统一验证V1→V6升级、商家JWT矩阵、图片真实HTTP上传/读取、SKU零库存/幂等/回滚/并发上限、真实MySQL预占/恢复与双商家全链路。

前端Catalog.vue已接关键词和店铺URL筛选、公开图片、独立图片错误/重试及购物袋商家归属；Products.vue已接商家关键词、新增SKU、草稿鉴权图片预览、多文件串行上传/按文件摘要原键恢复及软删除。图片与商品读取分离，关闭或切店释放ObjectURL，尚未运行前端build或浏览器验证。
