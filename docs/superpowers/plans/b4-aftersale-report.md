# B4 售后模块交付报告

只实现 `UNSHIPPED_FULL` 未发货整单售后。原完整目标 V1 已原样归档至 `docs/database-target/aftersale-full-V1.sql`；当前模块首次 V1 只含本批5表。没有执行测试、构建、迁移、启动、业务请求、远程、.local读取、提交或推送。根任务统一编译/迁移/启动；本报告不声称业务验收通过。

## 表与运行账号权限

所属库 `trade_aftersale`，每张表和全部字段都有中文COMMENT。

| 表 | 用途 | 运行账号权限 |
| --- | --- | --- |
| aftersale | 主单、权威金额快照、当前退款号与状态 | SELECT, INSERT, UPDATE |
| aftersale_task | AUTHORIZATION/TERMINATE/REFUND阶段、30秒租约、claim_version、恢复预算 | SELECT, INSERT, UPDATE |
| aftersale_idempotency | actor_id+operation+key绑定请求摘要与首次售后号 | SELECT, INSERT；仅列 UPDATE(idempotency_key)用于创建同键并发无操作更新 |
| aftersale_refund_attempt | 全部退款尝试号和支付事实历史 | SELECT, INSERT, UPDATE |
| aftersale_audit | 商家审核/新退款尝试/平台任务恢复原因审计 | SELECT, INSERT |

不需要DELETE、跨库权限或运行DDL权限。aftersale_idempotency的请求摘要、结果号不可更新；创建以 `INSERT ... ON DUPLICATE KEY UPDATE idempotency_key=idempotency_key` 取得唯一键竞争后 `SELECT ... FOR UPDATE` 校验摘要，同键同参返回原售后，异参409。

独立迁移入口：`com.tradeflow.aftersale.infrastructure.DatabaseMigration`。业务dev/prod均关闭Flyway；迁移账号仅独立进程使用。

## 环境与配置

- `TRADEFLOW_AFTERSALE_DB_USER/PASSWORD`：业务数据源；dev默认MYSQL_HOST=127.0.0.1，MYSQL_PORT=13316，prod端口默认3306。
- `TRADEFLOW_AFTERSALE_MIGRATION_USER/PASSWORD`：独立迁移凭据；迁移MYSQL_HOST默认127.0.0.1，MYSQL_PORT默认13316。
- `TRADEFLOW_JWT_PUBLIC_KEY`：RSA公钥文件路径；`TRADEFLOW_JWT_ISSUER`默认tradeflow，复用common-web JwtSupport。
- `TRADEFLOW_AFTERSALE_SERVICE_TOKEN`：本服务唯一内部调用凭据；所有用户/订单/支付调用头 `X-Caller-Service: aftersale`，`X-Service-Token`为该环境变量值。不需要ORDER/PAYMENT服务token。
- `TRADEFLOW_USER_SERVICE_URL`默认http://127.0.0.1:8081；`TRADEFLOW_ORDER_SERVICE_URL`默认http://127.0.0.1:8083；`TRADEFLOW_PAYMENT_SERVICE_URL`默认http://127.0.0.1:8084。prod延续显式URL配置。
- `SERVER_PORT`默认8085，单application.yml公共/dev/test/prod，HTTP连接超时1秒/读取3秒；Feign禁止隐式重试和凭据日志。
- 可选 `tradeflow.recovery.delay-ms`默认2000（Spring属性）；每轮至多8次领取。

## API

全部响应ApiResponse；需有效JWT且每次查询用户权威auth-context重新检查ACTIVE和token_version。商家需最新MERCHANT_ADMIN或MERCHANT_OPERATOR及merchant_demo成员，平台需最新PLATFORM_ADMIN。

| 方法与路径 | body / query / header | 语义 |
| --- | --- | --- |
| POST /api/v1/aftersales | 必需Idempotency-Key；`{orderNo,type:"UNSHIPPED_FULL",reason}` | 本人订单意图确认后事务固定as_+32hex与首次rfd_+32hex，落CREATING和AUTHORIZATION任务 |
| GET /api/v1/aftersales | query limit默认20，限制1..100；before可选售后号游标 | 仅本人 |
| GET /api/v1/aftersales/{no} | 无body | 仅本人 |
| POST /api/v1/aftersales/{no}/cancel | 无body、无需key | CREATING/PENDING_REVIEW先CANCELLING，任务确认订单TERMINATED后CANCELLED；重复撤销幂等 |
| GET /api/v1/merchant/aftersales | 同limit/before | 仅merchant_demo，无买家地址 |
| GET /api/v1/merchant/aftersales/{no} | 无body | 仅merchant_demo |
| POST /api/v1/merchant/aftersales/{no}/review | 必需Idempotency-Key；`{approved:true或false,reason}` | 批准仅原金额，先REFUND_PROCESSING再激活/退款；拒绝先REJECTING，终止确认后REJECTED |
| POST /api/v1/merchant/aftersales/{no}/retry-refund | 必需Idempotency-Key；`{reason}` | 仅当前支付退款权威FAILED且订单授权EXECUTING，事务固定新退款号和尝试，原领取围栏失效 |
| GET /api/v1/admin/aftersale-tasks | 无body | 最新PLATFORM_ADMIN，只列MANUAL_REVIEW任务，最多100 |
| POST /api/v1/admin/aftersale-tasks/{no}/retry | `{reason}`，无需key | 仅MANUAL_REVIEW，原因审计，重启原阶段/原退款号，不可人工造资金终态 |

reason必需非空且最多500字符；Idempotency-Key允许ASCII字母数字及 `_ . : -`，最多64字符。同键异参409。Jackson拒绝未知字段，因此客户端不能传金额、证据或其它未支持字段。Create幂等范围actor+CREATE+key；审核和退款重试范围actor+REVIEW/RETRY目标售后+key。详情字段为aftersaleNo/orderNo/userId/merchantId/type/reason/status/refundNo/refundStatus/amountCents/currency/lastError；没有地址。

## 静态自审结论

- 所有RPC均在TransactionTemplate外，结果应用与公共用例统一短事务主单→任务锁顺序；领取事务仅锁任务、不触及业务主单。领取包含READY与过期RUNNING，30秒租约、递增claim_version，应用结果复核版本及未过期租约。阶段切换/新退款尝试提升围栏；本阶段累计30次领取未完成转任务MANUAL_REVIEW，保留主单义务。平台恢复审计并重置预算。
- 创建先最新用户身份和订单本人意图，持久化后恢复任务先GET原授权，仅404 AUTHORIZATION_NOT_FOUND才重放reserve。reserve明确400/409/422业务拒绝转REJECTED；未知保留原号，绝不假装失败释放。CNY、金额(0,100000000]以及source/order/user/merchant绑定校验。
- 撤销与审核批准主单行锁竞争，撤销先CANCELLING，订单terminate可先到墓碑；批准先持久REFUND_PROCESSING，再activate，激活响应丢失下轮先查原授权，不能普通撤销。
- 支付GET原退款号仅404 REFUND_NOT_FOUND才POST同号。明确FAILED写refundStatus=FAILED、lastError，主单仍REFUND_PROCESSING，原任务DONE等商家受控重试，不释放订单权益。新号前事务外核验支付FAILED和来源EXECUTING，事务内复核旧绑定并提升领取围栏。
- SUCCEEDED退款还必须等订单授权COMPLETED（退款结算及库存回补完成）才售后COMPLETED；无MQ、无文件证据、无金额仅退款或退货退款实现。
- 创建同键并发、退款重试同键重放在新号尚未提交时的404、未知RPC前台错误映射与错误原文泄漏均已静态检查并修正；未执行动态验证，最终编译与运行由根任务统一完成。
