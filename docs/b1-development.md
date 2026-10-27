# B1 用户权限与商品库存

## 实现范围与边界

实现资料包B1及其必需的B0身份/迁移基础：真实用户账号与角色、商家成员、商品/SKU、报价、零库存初始化、有依据的库存调整。已编译并启动，未执行测试或完整业务联调；不能宣称越权、并发和故障场景已验收。

本文件保留B1历史范围；当前多商家与每店角色以[U1–U4及P0说明](u1-u4-development.md)和[用户契约](../tradeflow-user/docs/upgrade-u1-u4.md)为准。历史商家为`merchant_demo`，默认逻辑仓为`warehouse_main`，不能选择未经授权的商家。所有ID是JSON字符串，金额是整数分。

## 本机准备与账号

`Start-Local.ps1` 调用 `Prepare-Dev.py`，仅在 `.local/mysql/data` 初始化新实例，监听127.0.0.1:13316，已有监听先验证datadir，拒绝接管陌生实例或库。Windows关闭MySQL监护进程以记录真实PID，停止数据库使用优雅shutdown。

数据库trade_user、trade_product独立运行/迁移账号，正常连接READ COMMITTED。Flyway通过独立DatabaseMigration入口执行，业务JVM不继承迁移账号。V1/V2首次发布前按本批拆分；现已执行，之后只追加版本。新增登录限额V3和授权锚点V4均有中文COMMENT。

`.local/dev-env.json` 保存随机初始平台密码和开发环境；`.local/database-admin.json` 仅准备脚本使用，密钥文件仅当前Windows用户可读。首次启动仅在没有有效平台管理员时创建platform_admin，不提升同名普通账号，不提供公开初始化接口。

IDEA调试前先运行启动脚本准备数据库，再停止相应Java进程释放端口。给用户服务配置公私钥路径、USER_DB账号、两个内部凭证及issuer；商品服务只配公钥、PRODUCT_DB账号、两个内部凭证及Nacos连接配置；网关只配公钥和issuer。值来自受保护开发文件，激活dev，不提交Run Configuration中的密钥。远程组件按专门说明显式接入，普通本机启动不自动SSH。

## 实际HTTP入口

外部路径统一以http://localhost:8080为前缀。Swagger在8081、8082，Authorize填写登录Token。内部路径只可直接访问对应服务端口。

| 方法/路径 | 权限与说明 |
| --- | --- |
| POST /api/v1/auth/email-code | 公开；邮箱验证码5分钟有效、60秒冷却及小时额度；QQ SMTP |
| POST /api/v1/auth/register | 公开；邮箱、验证码、密码、显示名称；邮箱规范为小写并作登录名，仅USER，见[邮箱注册](email-registration.md) |
| POST /api/v1/auth/login | 公开；BCrypt验密，15分钟RS256 Token，每账号15分钟最多10次尝试 |
| GET /api/v1/users/me | 本人Token与最新账号版本 |
| GET /api/v1/admin/users | 平台管理员，page/pageSize分页 |
| PATCH /api/v1/admin/users/{id} | 平台修改ACTIVE/DISABLED，原因必填，撤销旧Token |
| PUT /api/v1/admin/users/{id}/roles | 平台全量替换受控角色，撤销旧Token |
| POST /api/v1/admin/merchant-members | 平台设置本商家成员状态与role，默认OPERATOR，撤销旧Token |
| GET /api/v1/products、/{id} | 公开，只有上架商品/有效规格 |
| GET /api/v1/merchant/products | 有效商家操作员/管理员，包含草稿 |
| POST /api/v1/merchant/products | 本商家ADMIN，必填Idempotency-Key，SKU与零库存同事务 |
| PATCH /api/v1/merchant/products/{id} | 本商家ADMIN，version乐观更新 |
| PATCH /api/v1/merchant/skus/{id} | 本商家ADMIN，version与priceVersion分别管理 |
| POST /api/v1/merchant/inventory/adjustments | 商家管理员，Idempotency-Key及原因必填 |
| GET /api/v1/merchant/inventory/ledger | 有效商家，分页查所属流水 |
| GET :8081/internal/v1/users/{id}/auth-context | product/order服务凭据，只读最新身份 |
| POST :8082/internal/v1/quotes | 仅order凭据，报价有效60秒，不表示占库存成功 |

内部请求使用X-Caller-Service（product或order）和对应随机X-Service-Token，不是用户JWT。网关删除外来服务凭据头。当前身份RPC统一按tradeflow-user服务名从Nacos发现，不再配置固定下游URL；订单Feign已在后续批次实现，当前运行方式见runbooks/nacos-discovery.md。

密码至少12字符，UTF-8最多72字节，包含大小写字母、数字和符号；不做BCrypt静默截断。未实现刷新令牌，过期重新登录。Token禁止写日志。

## 手动使用顺序（本轮未执行这些业务请求）

1. 用受保护文件中的初始平台账号登录。
2. 注册普通账号，记录userId。
3. 注册账号的USER权限保留；旧全局MERCHANT_ADMIN不再授予商家权限，无需通过全局角色接口授商家职级。
4. 平台专用成员入口设置本店职级：`{"userId":"用户ID","merchantId":"merchant_demo","status":"ACTIVE","role":"ADMIN","reason":"承担当前商家管理职责"}`。role省略默认OPERATOR；成员或全局账号变更不得移除最后一个有效ADMIN。
5. 商家重新登录获取最新版本Token，旧Token已撤销。
6. 创建商品，Header带稳定Idempotency-Key，例如以下JSON。

```json
{"name":"无线鼠标","description":"办公用鼠标","status":"ON_SALE","skus":[{"skuCode":"MOUSE-BLACK","specification":"黑色","priceCents":9900}]}
```

返回productId、skuId、version、priceVersion与真实库存，首次availableQty/reservedQty均为0。修改SKU可用 `{"version":0,"priceCents":10900,"status":"ACTIVE"}`，成功版本递增，旧版本409。

7. 用SKU ID入库，Header带新的稳定幂等键：

```json
{"warehouseId":"warehouse_main","reason":"首批采购入库，采购单PO-001","items":[{"skuId":"规格ID","quantityDelta":100}]}
```

首次入库也是ADJUSTMENT正数单，负数用于有依据纠错，不扣预占数、不允许负可用数。每行非零且在±100万以内，SKU不重复，最多100行，余额最大10亿。同步返回202和 `{"businessNo":"rcp_...","status":"APPLIED"}`，表示真正完成。

相同主体、操作和键重放同参返回原快照，异参409。丢响应时保留原键，不能换新键再入库。商品创建重放同样返回初始快照，最新价格与库存另查商品。

内部报价结构见QuoteRequest：orderNo为ord_加32位UUID，带userId、merchantId、数量与expectedUnitPriceCents。同SKU合并后最多99件、20个SKU、整单不超过100000000分。价格变化PRICE_CHANGED；同orderNo同参返回原报价，不延长有效期。唯一订单绑定在B2库存预占实现。

## 正确性设计与限制

- 注册唯一名由MySQL仲裁。状态、角色、成员修改与Token版本、审计同事务；专用授权锚点串行化平台写，防止并发移除最后管理员。重新启用账号也不复活旧Token。
- 网关与用户/商品独立验签，固定RS256、issuer、audience、用途与时间。商品敏感写在事务前查最新版本与归属，查询失败503。已完成授权检查的在途操作可能继续完成，不声称跨服务撤销和业务提交原子。
- 商品编辑用version条件更新，改价同时递增priceVersion。库存按SKU顺序锁余额，单据、明细、余额、流水、审计、幂等结果同事务；竞争最多在新事务重试3次，复用业务号。不用Redis锁替代库存权威。
- 订单、预占、MQ、支付退款、恢复与对账不在本批。内部静态服务凭据为开发版本，生产密钥轮换、TLS和网络隔离按后续部署批次完成。
- 本轮未写/运行测试、Smoke、业务演示请求、压测或故障注入；只做构建、迁移、启动和只读元信息确认。原骨架测试不是B1测试，test配置后续需接真实MySQL测试容器和认证材料。
