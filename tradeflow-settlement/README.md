# U4 独立模拟结算服务

本模块只记录模拟商家结算应付、服务费、批次及模拟完成，不调用银行卡、真实支付、提现或真实出款。独立数据库 `trade_settlement`；应用端口默认 8087，管理端口 18087。当前提交的是实现及测试源码，测试、迁移、应用启动和端到端验收由集成阶段统一执行，不能将本文视为已通过报告。

## 规则与金额守恒

- 首次导入支付成功事实时保存 `fee_bps` 和 `freeze_days` 单据快照。默认服务费 **0 bps**、冻结 **7 天**；配置变化仅影响后续新单据。费用规则 v1 为 `floor(剩余本金 × bps / 10000)`，金额全部为整数分，Java 使用大整数中间乘积避免溢出。
- 权威资格来自订单服务：`status=COMPLETED`、`receivedAt` 非空、`receivedAt + freeze_days <= 当前UTC时间` 且 `hasActiveAftersale=false`。订单当前 `completed_at` 是该收货时间。配置从不修改旧单据。
- 原支付号与原退款号分别是不可变事实幂等键；同号重放必须同订单、同支付、同金额、同币种、同发生时间。冲突不覆盖旧记录，任务保存错误，后续有明确的账本和任务查询入口。
- 费用按累计退款后的剩余本金重算。分次退款全退时费用全部冲回，不因每次取整留下尾差。退款本金不得超过原支付本金。
- 结算前退款减少待结算应付；结算后退款追加 `POST_SETTLEMENT_REFUND`，减少净模拟完成权利并增加商家 `debt_cents`。下一笔符合资格的净结算先抵扣债务，剩余部分才形成模拟支付给商家的金额。债务不足以抵完时保留余额，绝不抹去退款义务。
- 每个支付独立一个模拟批次（数据库 `UNIQUE(payment_no)`），单据、批次、抵扣、追加账本同事务提交。已存在批次金额永不因退款回写；账单同时展示原批次和当前净权利。
- 每条追加账本满足：`principal_delta - fee_delta = payable_delta + simulated_delta`。模拟完成条目为待结算转出和模拟完成转入；退款本金、费用冲回及净权利成对核对。净模拟完成权利是退款后的会计权利，和历史模拟支付累计不是同一指标。

例：支付100元、10%费用，模拟完成净90元；之后退款20元，冲回费用2元，净模拟完成减少18元，并形成18元负债。后续符合资格单据净50元，先抵扣18元，再模拟支付32元。原90元批次不删除或重写。

## RPC与并发边界

所有RPC在本地事务之外。出站请求头固定 `X-Caller-Service: settlement`、`X-Service-Token: tradeflow.internal.settlement-token`，通过 Nacos 服务发现，不提供服务直连回退。

依赖协议均是 `ApiResponse<T>`，其中 `code=OK` 且 `data` 非空：

| 服务 | 内部GET | 返回数据 |
| --- | --- | --- |
| payment | `/internal/v1/settlement/facts?after=0&pageSize=100` | `{items:[{factId,businessNo,paymentNo,orderNo,kind,amountCents,currency,occurredAt}],nextAfter,hasMore}` |
| order | `/internal/v1/settlement/orders/{orderNo}` | `{orderNo,userId,merchantId,status,receivedAt,hasActiveAftersale}` |
| user | `/internal/v1/users/{id}/auth-context` | `{userId,status,roles,merchantIds,tokenVersion}` |

`kind` 只能为 `PAYMENT_SUCCEEDED`、`REFUND_SUCCEEDED`；`occurredAt` 为带Z的UTC ISO时间，`currency=CNY`。`factId` 是 payment 资金账本ID而非可变支付订单ID；`businessNo` 是原支付号或原退款号。payment端仅开放上述最小投影，不返回用户资料、通道号、签名、令牌和执行退款权限。分页 `after>=0`，`1<=pageSize<=100`；不足一页时 `hasMore=false`。空页 `nextAfter=after`。

扫描任务 `SCAN` 使用数据库两分钟租约、递增 `claim_version`、执行者和有效期围栏。领取事务先提交；每个资金事实和结算事务都重新锁有效围栏，再按商家账户、支付结算单顺序锁业务数据，提交前再次校验。旧实例不能覆盖新租约。任务宕机可能留下部分已提交单据，恢复仍按原事实号重放，不会重复效果。

扫描游标持久化；**自增账本ID不等于事务提交顺序**，每次遍历尾部必须归零重扫，修复晚提交空洞。先到退款但本金尚未导入时不猜测归属或金额，后续完整重扫会恢复。资格候选独立持久游标，每页20笔并周期归零，最早20笔冻结单不会饿死后续成熟单。

最终结算短事务前重新查询权威订单资格；RPC与本库事务之间不能构成跨库原子屏障，远端可能恰在重核后新开售后或退款。该实现保证本库已导入退款与结算串行，以及并发成功退款随后追加冲正/负债最终收敛；**不宣称该窗口不存在**。如未来真实出款要求不可撤销资格，必须增加订单侧资格锁定/版本协议及付款清算协议。

扫描每次最多100个事实与20个候选。依赖超时、同号冲突、租约丢失均不推进原游标，错误可从平台任务查看；已提交效果保持原号幂等。坏事实阻止该页继续推进，需要核查权威源，不提供跳过资金事实或直接改余额的恢复按钮。无限历史重扫是本阶段为正确性选择的开销，未来可基于一致的冻结账单/提交水位协议优化，不能简单删除重扫。

## 外部API与权限

所有路径经网关访问，JWT在结算服务独立RS256验签。每次读取和命令均调用用户权威上下文，验证主体、ACTIVE状态、`token_version` 与最新角色；依赖不可用时拒绝，不能信任前端传来的用户或角色头。

| 方法与路径 | 权限及作用 |
| --- | --- |
| `GET /api/v1/merchant/settlement/summary` | 商家角色及最新 `merchantIds` 包含 `X-Merchant-Id`，只读该商家汇总 |
| `GET /api/v1/merchant/settlement/{ledger,items,batches,statements}` | 同上，SQL绑定商家过滤；不能利用平台资源名越权 |
| `GET /api/v1/admin/settlement/{summary,ledger,items,batches,statements}` | 最新 `PLATFORM_ADMIN`，可选 `merchantId` 查询筛选 |
| `GET /api/v1/admin/settlement/{tasks,reconciliations,audit}` | 最新 `PLATFORM_ADMIN`；平台任务、核对历史、操作审计，不接受商家入口访问 |
| `POST /api/v1/admin/settlement/run` | 平台受控运行一个扫描页，先追加操作者审计；无请求体，无真实出款 |
| `POST /api/v1/admin/settlement/reconcile` | 平台核对已导入事实/单据/账本/批次/商家负债并保存独立结果，先审计 |

列表统一 `page>=1`、`1<=pageSize<=100`，响应 `{items,page,pageSize,hasMore}`。列表数据字段保留数据库snake_case；汇总是 `{principalCents,feeCents,payableCents,simulatedCents,debtCents}`。金额全是分。商家账单 `statements` 为结算单据与原模拟批次只读联查，含 `current_net_cents`、`batch_net_cents`、`simulated_payout_cents`、`debt_offset_cents` 和 `simulated_completed_at`，未完成批次字段为空。

商家权限接受当前既有 `MERCHANT_ADMIN` 或 `MERCHANT_OPERATOR` 全局角色，同时必须校验最新商家成员归属。商家不获得跨商家读取能力；平台入口必须有平台角色，商家头不会赋予平台权限。

`run` 返回 `{state:PAGE_COMPLETED,importedOrVerified,nextAfter,candidateCount,simulationOnly:true}` 或 `{state:LEASED_BY_OTHER_INSTANCE}`，前者只说明这一页已处理，不等于所有单据已结算。超时可能已有部分效果，刷新任务和账单，再调用仍沿用原资金事实幂等。

`reconcile` 返回 `{runNo,result:PASS|DIFFERENCE,differences,scope:LOCAL_IMPORTED_FACTS}`，逐笔核对费用累计取整、账本四维、批次存在性、商家负债等。本地核对不把扫描尚未到达的支付事实当成已验证，也不能代替支付渠道账单对账或订单完整性核对。

常见错误：400参数无效；401登录撤销；403缺少角色/商家归属/内部独立凭据；409 `SETTLEMENT_FACT_CONFLICT` 或 `LEASE_LOST`；503权威依赖不可确认。不开放直接写余额、改账本、跳过退款或任意重置任务的API。

## 新表与最小数据库权限

迁移 `src/main/resources/db/migration/V1__settlement.sql`，所有新增表和字段具有中文COMMENT：

| 表 | 职责 | 运行账号权限 |
| --- | --- | --- |
| settlement_task | 稳定任务、资金与资格游标、版本租约、错误 | SELECT/INSERT/UPDATE |
| settlement_account | 商家退款负债及后续抵扣 | SELECT/INSERT/UPDATE |
| settlement_item | 原支付本金、累计退款、费用/冻结快照、状态 | SELECT/INSERT/UPDATE |
| settlement_fact | 不可变原支付/退款事实镜像，唯一kind+原业务号 | SELECT/INSERT |
| settlement_ledger | 唯一entry_key的只追加守恒账本 | SELECT/INSERT |
| settlement_batch | 每支付唯一模拟批次 | SELECT/INSERT |
| settlement_reconciliation | 每轮逐笔差异观察 | SELECT/INSERT |
| settlement_audit | 平台命令操作者审计 | SELECT/INSERT |

业务JVM `spring.flyway.enabled=false`，不得持有DDL账号。独立迁移入口 `com.tradeflow.settlement.DatabaseMigration` 只迁移 `trade_settlement`，读取 `TRADEFLOW_SETTLEMENT_MIGRATION_USER/PASSWORD`；现有服务无需新增业务DDL，payment只有只读Mapper/API扩展。初始数据库由集成部署创建；不在运行代码创建数据库。

## 集成配置

| 配置 | 环境变量与默认值 |
| --- | --- |
| `tradeflow.internal.settlement-token` | `TRADEFLOW_INTERNAL_SETTLEMENT_TOKEN`；独立服务凭据，结算服务要求非空，payment未配置时拒绝所有结算读取 |
| `tradeflow.settlement.fee-bps` | `TRADEFLOW_SETTLEMENT_FEE_BPS=0`，0..10000 |
| `tradeflow.settlement.freeze-days` | `TRADEFLOW_SETTLEMENT_FREEZE_DAYS=7`，0..3650 |
| `tradeflow.settlement.worker-enabled` | `TRADEFLOW_SETTLEMENT_WORKER_ENABLED=true`，test profile默认false |
| `tradeflow.settlement.scan-delay-ms` | `TRADEFLOW_SETTLEMENT_SCAN_DELAY_MS=5000` |
| 数据库业务账号 | `TRADEFLOW_SETTLEMENT_DB_USER/PASSWORD` |
| JWT | 既有 `TRADEFLOW_JWT_PUBLIC_KEY`、`TRADEFLOW_JWT_ISSUER` |
| 服务发现 | 既有Nacos地址、namespace/group及实例可达地址 |

端口 `SERVER_PORT=8087`、`MANAGEMENT_SERVER_PORT=18087`，prod MySQL默认3306、dev默认13316。模块未启动MQ/Redis运行时；依赖通过HTTP成功资金账分页恢复。当前结算没有专属资金指标采样，`tradeflow.observability.enabled=false`，不宣称已有模拟结算指标告警；管理健康接口可用。

模块pom直接依赖 common-web、MyBatis starter、Flyway core/mysql、MySQL驱动、Nacos discovery/config、spring-boot-starter-test；common-web提供JWT、Servlet、安全错误、服务发现和JSON基础能力。根pom模块列表、网关路由、服务发现白名单、数据库创建/账号/迁移/备份/启动清单由主集成修改，未在此模块擅自跨范围修改。

控制台单文件 `web-admin/src/pages/Settlement.vue` 提供账单、明细、分页、模拟资金说明、平台运行/核对/任务历史；路由传 `platform:true` 且有 `PLATFORM_FUNDS` 才显示平台入口，商家页面始终使用所选 `session.merchantId`，切换商家立即清空并中止旧读取。页面不改变权限来源，也不在商家视图触发平台命令。

## 测试源码与验收边界

- `SettlementRulesTest`：累计退款费率尾差、冻结精确边界、售后阻断、负债抵扣与大整数安全。
- `SettlementServiceTest`：旧租约、退款先到、同号异参、默认规则快照、已完成后退款冲正、活动售后重核、双商家越权与撤销令牌。
- payment `SettlementFactsControllerTest`：独立调用者/凭据、缺少配置拒绝、分页边界。
- `SettlementMySqlTest`：仅设置 `TRADEFLOW_SETTLEMENT_TEST_DB_URL`（必须数据库名trade_settlement_test）及对应TEST_DB_USER/PASSWORD时启用；Flyway初始化专用库，验证真实MySQL唯一事实竞争、守恒CHECK、旧租约版本不能回写。未设置环境时跳过，必须分别报告。

以上测试源码尚未由本子任务执行。MySQL测试修改专用测试库，绝不接受业务库URL。真实多实例任务竞争、远端RPC与退款交错、全服务端到端、浏览器交互及历史数据规模下的重扫成本，需要集成阶段实测并分别报告；本地编排单元测试不能替代这些验收。
