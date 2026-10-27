# 商品展示缓存与 Mapper 约定

本轮只修改本地源码、配置和验证脚本，没有更新现有运行实例或服务器。

## Redis 客户端

`ReactiveStringRedisTemplate` 是 Spring Data Redis 的响应式字符串客户端，不是 Redis 数据类型。网关使用 WebFlux，继续使用它，避免在响应式线程执行阻塞 Redis 请求。商品服务是 Servlet/MVC，使用 `StringRedisTemplate`；两者的 key/value 均为字符串，商品快照使用 JSON。

`RedisTemplate<K,V>` 可自定义对象序列化；当前没有引入另一套对象序列化协议的必要。Redis 的 String、Hash、List 等才是存储数据类型。

## 缓存范围和一致性

- 公开商品列表、详情缓存商品和有效 SKU 的展示元数据，包括名称、规格、展示价格与版本。
- 可用库存、预占库存不进入缓存，组装响应时仍查询商品服务数据库。
- 商家列表、创建、编辑读取权威数据库；报价、库存预占、退款权益和额度从不读取展示缓存。
- 创建商品、编辑商品、编辑 SKU 成功提交后切换目录代际；事务回滚不失效。单商家范围内使用目录代际同时淘汰列表、详情，代价是编辑会使其他商品展示缓存一并失效。
- 旧代际的 key 不做全库扫描删除，依靠 TTL 自动回收。代际使用随机 UUID，避免 Redis 清空或重建后复用旧代际。
- 写回 Lua 同时校验当前代际和重建锁 owner；锁释放 Lua 只允许删除自身锁。迟到的旧重建者不能覆盖新代际或删除后来者的锁。

展示缓存是最终一致。失效失败不会回滚已提交业务；旧展示可能保留至原 TTL 结束，正常正缓存 TTL 为 60–90 秒。下单报价再次校验当前价格、商品状态，不能按缓存价格直接成交。已开始的查询也可能返回编辑前的快照，不承诺公开浏览强一致。

## 缓存三灾及故障行为

| 场景 | 实现 |
| --- | --- |
| 穿透：反复查询不存在或不可见商品 | ID 格式校验；不存在、草稿、下架对公开详情统一返回不存在，空值缓存 10–20 秒；网关有共享入口额度 |
| 击穿：热点 key 过期 | 本机固定数量条带锁、跨实例 `SET NX` 10 秒租约；未抢到锁只等候约 200ms，未得到结果返回 `503 CATALOG_BUSY`，不会全部直接回源 |
| 雪崩：大量 key 同时失效 | TTL 随机抖动；单实例同时最多 4 个元数据 loader，预算不足快速 503 |
| Redis 故障或损坏快照 | 2 秒冷却后再探测，使用上述有界元数据回源；业务 loader 异常直接传播，不自动重复执行业务查询 |

本机锁等待最多 250ms。商品 Redis 连接和命令超时均为 300ms。此处的等待只用于展示查询，不持有库存或资金行锁。Redis 分布式锁只是减少缓存重建，不承担业务正确性。

**4 个额度只限制缓存元数据的重建/降级 loader。** 命中缓存后的实时库存查询仍访问 MySQL，不在这 4 个额度内；不能据此宣称整个商品查询数据库并发不超过 4，也不能宣称所有请求不再访问数据库。多实例部署需要合计回源预算与连接池容量。空值缓存不阻止随机 ID 的首次查询，仍需入口限额和回源保护；当前未引入布隆过滤器。

本机与独立 Compose 目前共享网关的 Redis，使用独立 key 前缀和 `{catalog}` hash tag。不同环境应使用独立 Redis；实际容量需监控和压测，缓存增长可能挤占共享内存。保留 `noeviction`，避免缓存淘汰策略删除网关限流 key；内存耗尽时网关会按既有策略拒绝受保护请求。若需独立容量和故障域，应拆分缓存 Redis 与入口配额 Redis。

## Mapper 和 SQL

依赖继续使用 MyBatis-Plus Starter，持久层仍按现有原生 MyBatis 接口开发，不切换 `BaseMapper`、`IService`。现有售后 `JdbcTemplate` 用法保留。

- 17 个 Mapper、269 个 SQL statement 放在各模块 `src/main/resources/mapper/{product,order,user,payment,simulator}/`，复杂 SQL 在 XML 内维护。
- SQL 的数据库列名保持 `snake_case`，Java 参数和 XML 占位符使用含义明确的 `camelCase`，例如 `payment_no` 对应 `paymentNo`。
- 生成主键仍使用 XML `useGeneratedKeys`、`keyProperty` 回写原参数对象；创建商品、SKU 和订单明细所需 Map key 已同步调整。
- record 使用显式 constructor/resultMap，避免多个构造器或列顺序带来映射歧义。各模块统一配置 `classpath*:/mapper/**/*.xml`。
- 管理 API 原有 Map 响应中的 `snake_case` 字段属于既有接口契约，本轮没有静默变更；驼峰整理适用于 Java 参数和实体属性。
- 删除迁移后的 SQL、构造映射、生成键注解；删除用户、商品启动类无对应 Feign 接口的 `@EnableFeignClients`。`@Mapper`、命名绑定所需 `@Param`、校验、安全和事务相关注解继续保留。
- 保留中文业务注释、锁顺序、条件更新、租约围栏和幂等约束；没有修改已发布迁移。

手工构造 MyBatis Configuration 的测试必须显式加载同一份 XML；只调用 `addMapper` 不会自动扫描独立的 mapper 资源目录。

## 本轮验证

- `.local/catalog-mapper-tests.log`：17 项离线/单元测试通过，覆盖 XML 注册、参数绑定、record、foreach、生成键，以及订单/支付恢复逻辑。
- `.local/verification/entry-redis-xdja4c2v/`：独占 Redis 上运行真实客户端和 Lua，5 项缓存集成测试、2 项故障回源测试及 2 项商品 XML 测试通过；包含缓存命中时仍实时查询库存，验证快照不含库存字段。
- `.local/verification/return-mysql-ife7_8ii/`：16 份迁移及中文 COMMENT 校验；5 项真实 MyBatis/Spring 事务测试通过，覆盖商品创建主键/原键重放/改价/下架、退货处置、库存并发及审计失败回滚。
- `.local/catalog-fault-mysql.log`：订单/支付 6 项故障单测及真实 MySQL 支付租约围栏 2 项通过。
- `.local/verification/entry-mysql-jhs0z6zt/`：从新 XML 抽取登录窗口 SQL，真实并发及窗口防倒退检查通过。
- `.local/payment-crash-d521d0a2c8ca46879975955c1f5c8d38/result.json`：实际 JVM `halt(73)` 后等待原租约到期，原支付号查询恢复；支付指令、成功流水、退款账户及 Outbox 各一份。通道为持久文件替身。
- `.local/catalog-compile.log`：11 模块编译通过。

上述均使用本轮独占回环实例，完成后停止自己的实例；没有访问现有业务库。不是完整端到端、真实 Nacos、MQ 故障或性能验收。

复现命令：

```powershell
python scripts/verification/entry-redis.py --catalog-jvm
$env:TRADEFLOW_MYSQL_BIN='D:/mysql-8.0.46-winx64/bin'
python scripts/verification/return-mysql.py
python scripts/verification/fault-mysql.py
python scripts/verification/entry-mysql.py
```
