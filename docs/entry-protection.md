# 本地业务入口保护

本次只修改代码与新增隔离验证工具，没有迁移现有业务库、启动项目服务或修改现有进程。外部业务仍经 Gateway 的 Nacos `lb://` 白名单路由，JWT 验签、下游实时权限复核及身份头清理继续生效；不增加写请求自动重试。

## 登录前的数据库约束

用户服务新增 Flyway `V5__login_entry_protection.sql`，只创建 `login_entry_window`；不改已发布 V1–V4 或旧限额记录。所有用户服务实例必须连接相同用户数据库并使用相同限额配置。运行账号对新增表需要 `SELECT, INSERT, UPDATE, DELETE`，DELETE 只供每五分钟最多500行的七天过期清理。用户业务数据不属于清理范围。

登录前按「socket来源→规范化账号」顺序，在 `REQUIRES_NEW` 短事务内执行唯一键 UPSERT，再在已持锁事务内读取次数。数据库UTC时钟按固定窗口编号切窗，多实例和进程重启共享限制；计数饱和为上限加一，拒绝不会滑动延长窗口。事务提交后才抛429或执行 BCrypt，不在数据库事务内RPC。账号/来源只保存带作用域SHA256，不保存密码、原始用户名或原始IP。

默认每900秒来源最多100次、账号最多10次。先预留再验密，失败与未决请求占用账号额度。成功只减去自己的一个账号预留，并带窗口版本条件；不会删除历史失败、并发预留或新窗口计数。来源保留成功、失败及因账号限额被拒绝的尝试，防止轮换账号绕过。进程崩溃等未决预留与失败一样保留到窗口结束。已被拒绝的账号计数也可能保留一个饱和占用，策略偏保守，不因迟到成功放宽限制。没有指数等待或逐次延长锁定，所有退避以当前固定窗口剩余秒数为准。

以下配置必须在所有用户服务实例保持一致，变更时应协调发布。窗口秒数纳入哈希作用域，避免不同长度窗口编号混算；调整窗口长度会开启新的限额作用域。UPSERT窗口编号保持单调，等待行锁后才执行的旧时间语句只能计入当前窗口，不能倒退重置次数。

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `TRADEFLOW_LOGIN_WINDOW_SECONDS` | 900 | 固定窗口秒数，1–86400 |
| `TRADEFLOW_LOGIN_ACCOUNT_LIMIT` | 10 | 每规范化账号上限，1–100000 |
| `TRADEFLOW_LOGIN_SOURCE_LIMIT` | 100 | 每socket来源上限，1–100000 |

`LOGIN_RATE_LIMITED` 返回429及 `Retry-After: <剩余整秒>`。账号不存在、密码错误、停用账号仍统一 `INVALID_CREDENTIALS`。数据库不可用不能跳过准入，返回503；数据库资源/锁竞争错误附 `Retry-After: 5`，不假装登录成功。

## 网关的Redis共享约束

网关始终启用 Redis 入口保护。`ReactiveStringRedisTemplate` 执行一段原子Lua，同时检查「分类总额度」和「主体额度」，两桶都可用才同时计数。两key共享同一Redis Cluster hash tag。拒绝不消耗另一桶、不延长TTL；不使用JVM Map或Sentinel单机规则代替多实例共享约束。Redis故障、超时或结果缺失时，受保护入口返回 `503 ENTRY_PROTECTION_UNAVAILABLE` 与 `Retry-After: 5`，不会转发下游。调用方应先查询原业务，再考虑用原幂等键重试，不能自动改键或重放写请求。

限额返回 `429 RATE_LIMITED`，`Retry-After` 是阻塞桶剩余TTL的最大秒数。额外Sentinel拒绝仍区分429流控和503熔断，并分别给出1秒和5秒的重试提示。Redis连接错误不会暴露连接地址、凭据或底层异常。Gateway readiness现在包含Redis健康，缺少依赖不能通过启动就绪检查。

| 实际入口分类 | 默认窗口秒数 | 默认每主体/总额度 |
| --- | --- | --- |
| POST `/api/v1/auth/login`、`/auth/register`、`/auth/email-code` | 60 | 30 / 300 |
| POST `/api/v1/orders` | 60 | 20 / 200 |
| 已匹配公开路由的 GET/HEAD，包括原键查询、列表、详情 | 60 | 180 / 3000 |
| `/api/v1/admin/**` 的写请求、商家售后 `*/retry-refund` | 60 | 10 / 100 |
| 其余已匹配公开写入口 | 60 | 60 / 600 |

管理恢复包括实际任务 `*/retry`、消息 `/{owner}-messages/*/*/retry`、对账 `issues/*/repair` 与创建/检查/重试动作，并不匹配一个不存在的统一恢复URL。库存条件 GET `/api/v1/merchant/inventory/conditions` 和 POST `/api/v1/merchant/inventory/condition-adjustments` 分别使用查询/写额度，并要求有效JWT。`/system/info` 与服务目录不访问Redis；内部接口没有新增公开路由。

| 环境变量 | 默认值 |
| --- | --- |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `127.0.0.1` / `16379` / 空 |
| `TRADEFLOW_ENTRY_KEY_PREFIX` | `tradeflow:entry:v1` |
| `TRADEFLOW_ENTRY_TIMEOUT_MILLIS` | 500 |
| `TRADEFLOW_ENTRY_WINDOW_SECONDS` | 60，统一各分类窗口 |
| `TRADEFLOW_ENTRY_LOGIN_LIMIT` / `TRADEFLOW_ENTRY_LOGIN_TOTAL` | 30 / 300 |
| `TRADEFLOW_ENTRY_ORDER_LIMIT` / `TRADEFLOW_ENTRY_ORDER_TOTAL` | 20 / 200 |
| `TRADEFLOW_ENTRY_QUERY_LIMIT` / `TRADEFLOW_ENTRY_QUERY_TOTAL` | 180 / 3000 |
| `TRADEFLOW_ENTRY_RECOVERY_LIMIT` / `TRADEFLOW_ENTRY_RECOVERY_TOTAL` | 10 / 100 |
| `TRADEFLOW_ENTRY_WRITE_LIMIT` / `TRADEFLOW_ENTRY_WRITE_TOTAL` | 60 / 600 |

所有网关实例必须使用相同Redis实例/逻辑库、key前缀和额度。不同环境应使用独立Redis或不同前缀。Redis部署需限制网络访问、配置认证和合理内存/持久化策略；key被删除、被驱逐或故障切换丢失会重置网关临时配额，因此账号安全仍有用户库的独立约束。网关临时配额不能替代订单/资金/库存的数据库业务约束。

## 来源的可信边界

Gateway和用户服务都显式设置 `server.forward-headers-strategy: none`，默认仅使用TCP socket地址。网关删除外部 `Forwarded`、`X-Forwarded-For`、`X-Real-IP`、`X-Login-Source`，不让匿名客户端指定自己的限流来源。已验签主体额度使用JWT subject，同一用户换Token不能绕过。网关分类总额也不随来源变化。

此策略会将同一反向代理后的匿名请求合并；用户服务经网关访问时，也会将网关socket合并为一个来源桶。默认来源100/15分钟适合本地演示，但不是按真实终端IP区分的生产额度。应按实际代理拓扑调整来源额度；如需真实终端来源，必须另行明确可信代理地址范围、边界清理及已认证转发机制，本次没有盲信任转发头。业务服务端口应保持本机/私网边界，注册的来源限流由网关提供。

## 已执行验证及边界

2026-10-25执行定向命令：

```powershell
.\mvnw.cmd -B -ntp -pl tradeflow-user,tradeflow-gateway -am '-Dtest=LoginAdmissionTest,LoginProtectionTest,EntryProtectionPolicyTest,EntryProtectionFilterTest,GatewayBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
python scripts/verification/entry-mysql.py
python scripts/verification/entry-redis.py
```

Maven定向14项通过，覆盖实际路径分类、Redis失败503及429退避、拒绝不转发、只转发一次、限额先于账号查询和BCrypt、失败不成功清理、独立事务提交及旧有JWT/路由边界。日志 `.local/entry-tests.log`。这些不是全部服务测试或生产验收。

MySQL8.0.46隔离新实例执行直接从生产Mapper提取的SQL：32并发恰好10次准入、饱和计数、成功只减自身占用、过窗切换、旧版本迟到成功、等待后旧时间语句不倒退窗口及COMMENT均通过。证据 `.local/verification/entry-mysql-eb6sm1hl/result.json`。

Redis7.0.15隔离新实例执行直接从生产Java提取的Lua：32并发单主体恰好2准入、分类总额3不能靠切换主体绕过、拒绝不增加另一桶、不延长TTL、窗口到期恢复均通过。证据 `.local/verification/entry-redis-y84pjdq6/result.json`。两个脚本只启动随机回环端口的新独占实例，finally停止自己的进程，保留数据与日志，不读取原业务实例或原Redis dump。

尚未执行新表在现有项目环境迁移、Spring/MyBatis真实数据源集成、ReactiveRedisTemplate与真实Redis的完整应用调用、多Gateway/User JVM联合验收、代理部署及负载测试；不能将SQL/Lua并发检查表述为这些验收已通过。未执行SSH、提交或推送。
