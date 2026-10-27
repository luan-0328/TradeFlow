# B4 独立模拟支付与退款通道

模块拥有 `trade_payment_simulator`，不访问支付、订单库。接口 DTO 严格使用公共 `PaymentContracts`；所有响应采用 `ApiResponse`。

## HTTP 契约

- `POST /channel/v1/payments`：`ChannelCommand(paymentNo, orderNo, amountCents, currency)`，正整数分且 CNY。
- `POST /channel/v1/payments/{paymentNo}/close`：同一完整命令，路径支付号必须一致；先到关闭也保留完整绑定墓碑。
- `GET /channel/v1/payments/{paymentNo}`：返回 `ChannelFact`；不存在为 `404 CHANNEL_NOT_FOUND`，不能解读为关闭。
- 三个入口必须 `X-Caller-Service: payment` 和 `X-Service-Token`；唯一交易行串行 pay、query、close、异步成功。成功、失败和关闭终态不反转，同号异参409。锁超时/死锁完整回滚后最多三次新事务抖动重试，超限503并继续复用原号。
- 仅 dev 可用 `POST /simulator-admin/v1/scenarios`，`X-Simulator-Admin-Token`；body `{paymentNo,mode,actorId,reason}`，操作人和原因必填，支付号场景只绑定一次。已有配置或首次支付默认配置后再次写入均409。

| mode | 确定行为 |
| --- | --- |
| SUCCESS | 同事务成功并创建回调任务 |
| REJECT | 明确失败并创建失败事实回调 |
| PROCESSING | 持久任务最早五秒后推进成功；关闭可抢先，任务不会覆盖墓碑 |
| RESPONSE_TIMEOUT | 成功事实和回调任务先提交，再返回504；按原号查询仍为成功 |
| CALLBACK_LOST | 支付成功，显式不创建回调任务，保留查询事实 |
| CALLBACK_DUPLICATE | 至少两次业务ACK，通知号和原始JSON始终相同 |

回调固定向 `${TRADEFLOW_PAYMENT_SERVICE_URL}/callbacks/v1/payments`；禁止客户端回调URL。body是冻结的 `Callback(notificationNo,fact)` UTF8 JSON；头为 `X-Notification-Timestamp`（秒）、`X-Notification-Nonce`、`X-Notification-Signature`（HMAC-SHA256小写十六进制），签名消息为 `timestamp + "\n" + nonce + "\n" + rawBody`。重试只换时间戳与nonce。只有 HTTP200、信封 code=OK 且 data.ack=true 才确认送达。

恢复任务领取和结果应用分短事务；HTTP在事务外，1秒连接/3秒请求超时，无自动重定向。租约30秒，每次领取递增claim_version，旧执行者不得回写；失败按2秒递增退避，最多60秒，累计30次仍未确认进入MANUAL_REVIEW并保留任务。本批没有人工重启接口，不通过直接修改终态伪造处理。

## 配置和迁移

只有一个 `application.yml`，分 common/dev/test/prod。dev默认本机 `127.0.0.1:13316/trade_payment_simulator`，业务进程Flyway关闭。独立入口 `com.tradeflow.paymentsimulator.infrastructure.DatabaseMigration` 负责迁移，禁止把迁移账号交给业务JVM。

必需变量：

- `TRADEFLOW_PAYMENT_SIMULATOR_DB_USER` / `TRADEFLOW_PAYMENT_SIMULATOR_DB_PASSWORD`
- `TRADEFLOW_CHANNEL_SERVICE_TOKEN`、`TRADEFLOW_CALLBACK_SECRET`、`TRADEFLOW_SIMULATOR_ADMIN_TOKEN`，至少32 UTF8字节，与其他用途凭据分离
- 迁移进程单独提供 `TRADEFLOW_PAYMENT_SIMULATOR_MIGRATION_USER` / `TRADEFLOW_PAYMENT_SIMULATOR_MIGRATION_PASSWORD`

可选变量：`MYSQL_PORT`（dev/迁移默认13316，prod默认3306）、`MYSQL_HOST`（迁移/prod默认127.0.0.1）、`TRADEFLOW_PAYMENT_SERVICE_URL`（默认http://127.0.0.1:8084）、`SERVER_PORT`（默认8086）、`BIND_ADDRESS`（prod默认0.0.0.0）。隔离测试库URL为 `TRADEFLOW_PAYMENT_SIMULATOR_TEST_DB_URL`，本批不运行测试。

初次V1仅创建本批5张表。原完整目标V1归档在 `docs/database-target/payment-simulator-full-V1.sql`，不是实际迁移，不表示退款/账单已实现。

## 运行账号权限

所有表禁止DELETE。运行账号不授予DDL、其他逻辑库权限或Flyway历史表权限。按下表授予SELECT/INSERT，并限列UPDATE；迁移账号单独拥有所属库所需DDL。

| 表 | 权限及可变边界 |
| --- | --- |
| channel_transaction | SELECT, INSERT；UPDATE(payment_no,status,channel_version,updated_at,succeeded_at)。payment_no仅供同号ON DUPLICATE KEY无值变化；绑定订单/金额/币种/通道号不可变 |
| fault_scenario | SELECT, INSERT；UPDATE(payment_no)仅供默认场景同号无值变化；场景、操作人、原因不可变 |
| process_task | SELECT, INSERT；UPDATE(task_no,state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error)。task_no仅同号无值变化；绑定、payload、要求次数不可变 |
| callback_task | 同process_task；payload与通知号由首次事实冻结，重试不改正文 |
| audit_log | SELECT, INSERT；不可UPDATE，审计只追加 |

业务账号即使可访问库也不能修改绑定、回调材料和审计证据。B4追加独立退款与真实预算；本批无MQ或账单能力。

## 本批验证边界

仅静态阅读和自审；不写/运行测试，不执行业务请求，不读取.local，不远程连接，不启动服务或迁移，不commit/push。由主任务统一跳过测试编译、迁移和启动；未声称并发、故障矩阵、重启恢复或端到端验证通过。

## B4独立退款通道

POST `/channel/v1/refunds` 使用RefundContracts.ChannelCommand；GET `/channel/v1/refunds/{refundNo}` 使用RefundContracts.ChannelFact。不访问payment或order库；持原支付锁校验本库SUCCEEDED支付与订单绑定，原支付 `reserved_refund_cents + succeeded_refund_cents <= amount_cents` 是最终通道预算。首次受理占用预算，成功转换为累计成功金额；REJECT、非成功支付或预算不足写明确FAILED，不占用资金。相同退款号绑定不可变，重放不重复占用或记成功。

退款场景通过dev专用 POST `/simulator-admin/v1/refund-scenarios` 配置，body `{refundNo,mode,actorId,reason}`，沿用独立 `X-Simulator-Admin-Token`。与支付故障场景完全独立，六种mode行为相同；PROCESSING持久任务最早五秒后成功，退款不提供关闭接口。RESPONSE_TIMEOUT先提交事实再返回504；CALLBACK_LOST只缺通知不缺事实；CALLBACK_DUPLICATE用同通知号与原JSON完成两次ACK。

退款回调固定 `${TRADEFLOW_PAYMENT_SERVICE_URL}/callbacks/v1/refunds`，复用独立 `TRADEFLOW_CALLBACK_SECRET`，原始字节HMAC及ACK规则与支付相同。退款推进/回调使用独立队列，30秒租约、owner+claim+有效期围栏，最多30次自动执行；不会将未知自动标失败。没有新增环境变量。

V2只追加迁移：channel_transaction新增两个退款预算字段；新表channel_refund、refund_fault_scenario、refund_process_task、refund_callback_task。两个退款任务表为复用统一任务Mapper，其 `payment_no` 列实际存退款号，已在数据库COMMENT说明，绝不据此查询支付事实。

| 表 | B4运行权限 |
| --- | --- |
| channel_transaction | 既有权限加UPDATE(reserved_refund_cents,succeeded_refund_cents) |
| channel_refund | SELECT,INSERT；UPDATE(status,channel_version,succeeded_at,updated_at) |
| refund_fault_scenario | SELECT,INSERT；UPDATE(refund_no)仅用于同号默认配置无变更分支 |
| refund_process_task / refund_callback_task | SELECT,INSERT；UPDATE(state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error) |
| audit_log | 沿用SELECT,INSERT，禁止UPDATE/DELETE |

V1保持不变，V2未在本子任务执行。仅静态自审，未运行构建、测试、服务或业务请求。

## B7冻结账单

POST `/channel/v1/bill-snapshots` 接收 `ReconciliationContracts.BillRequest`；GET `/channel/v1/bill-snapshots/{snapshot}` 返回固定清单；GET `/channel/v1/bill-snapshots/{snapshot}/lines?after=0&pageSize=100` 返回稳定位置游标页。三者均要求 `X-Caller-Service: payment` 和既有独立 `X-Service-Token`，没有新增密钥。

账期按UTC半开区间 `[start,end)` 筛选 `updated_at`，最多31天，时间精度毫秒，`end+300秒 <= cutoff <= 数据库当前UTC`，币种固定CNY。支付包含SUCCEEDED/FAILED/CLOSED，退款包含SUCCEEDED/FAILED；计数包含全部终态，金额仅累计成功事实。一次REPEATABLE_READ事务以普通SELECT读取支付和退款同一可见快照，再冻结账头及全部行；不使用INSERT SELECT或跨库访问。水位由数据库UTC时间提供；该水位描述冻结时刻，不保证未来不会出现迟到事实。

requestNo以唯一键一次绑定账期与截止点，同号异参409；按PAYMENT/REFUND、业务号排序，position从1连续，摘要使用共享规范SHA256，空账单同样返回确定摘要。最多100000行，超限422整笔回滚，绝不截断冒充完整。正常分页最大100行，after必须在0至lineCount内，终页hasMore=false且nextCursor为实际末行；空账单为0、false。

开发专用POST `/simulator-admin/v1/bill-projections` 接收 `{requestNo,mode,actorId,reason}`，mode可为INCOMPLETE、BAD_CHECKSUM、TRUNCATED_PAGE。须在首次冻结前一次配置，沿用dev环境及独立管理令牌限制，并追加audit_log；正常冻结也绑定NONE屏障，防止冻结后插入故障。故障只改变指定账单投影，不更改支付、退款或冻结明细。TRUNCATED_PAGE固定省略最终页最后一行，用于完整性检测。

V3新增bill_projection_scenario、bill_snapshot、bill_snapshot_line；V1/V2不修改。运行账号最小权限：

| 表 | 新增运行权限 |
| --- | --- |
| bill_projection_scenario | SELECT,INSERT；UPDATE(request_no)仅用于默认幂等无变更分支 |
| bill_snapshot | SELECT,INSERT；UPDATE(request_no,manifest_json)，只在首次冻结事务内填充清单 |
| bill_snapshot_line | SELECT,INSERT，禁止UPDATE/DELETE |
| channel_transaction / channel_refund | 沿用SELECT，不写资金 |
| audit_log | 沿用INSERT，不改审计历史 |

本子任务仅静态检查，未执行测试、构建、迁移、启动、业务请求、MQ或SSH；这些接口的并发与故障行为尚未运行验证。
