# B4资金与独立退款通道报告

完成Task 1代码及静态自审；仅改payment、payment-simulator与模块README，以及本报告。未读取.local，未执行测试、业务请求、构建、启动、迁移、远程连接、commit或push；没有声明运行验收通过。

## 契约与实现

固定RefundContracts未修改。支付新增RefundController/RefundService/RefundMapper，通道新增SimulatorRefundController/SimulatorRefundService/SimulatorRefundMapper。PaymentRemote新增订单授权、通道退款、退款查询及订单退款结果HTTP适配。支付原任务/Outbox扫描按PAYMENT过滤，退款扫描按REFUND过滤，避免互相误领。

支付POST /internal/v1/refunds、GET /internal/v1/refunds/{refundNo}；order仅CLOSE来源及LATE_PAYMENT，aftersale仅as_来源及UNSHIPPED_FULL。查询缺失404 REFUND_NOT_FOUND。同退款号绑定幂等；订单EXECUTING授权在事务外查询，固定授权摘要/版本持久化。短事务支付→本金账户→来源→退款锁，双额度原子占用；失败回滚，不留部分额度。

通道POST /channel/v1/refunds、GET /channel/v1/refunds/{refundNo}；本库成功原支付、绑定及真实剩余本金决定能否受理。按退款号固定场景POST /simulator-admin/v1/refund-scenarios，dev与独立管理员token限制，六场景独立于支付。

退款成功只应用一次状态与支付/来源额度转换，同事务追加唯一REFUND_SUCCEEDED流水和HTTP Outbox。FAILED释放执行占用，保持订单权益及售后义务；UNKNOWN不释放。终态矛盾、同版本不同证据冻结review_required，内部View、恢复和Outbox阻断自动传播；原支付后续review标记也会同步冻结退款执行/回调应用并阻断既有退款Outbox。退款Outbox仅投订单refund-results，稳定eventId及原payload重放，ACK后本地完成，不实现MQ或向售后写入。

回调POST /callbacks/v1/refunds，64KB、五分钟HMAC原始字节验真后才去重；通知号同摘要重放ACK、不同摘要409。租约30秒、递增claim_version及owner/有效期围栏；退款领取事务不锁业务表。写请求前置QUERY，未知先查原退款号，仅404恢复同号提交。最多30次自动网络执行，耗尽转人工保留全部资金事实。

## 迁移与权限

V1不变；新增payment V2__refund_execution.sql：refund_source、refund_order。新增simulator V2__channel_refund.sql：channel_transaction追加reserved_refund_cents和succeeded_refund_cents；新建channel_refund、refund_fault_scenario、refund_process_task、refund_callback_task。全部新表/新列有中文COMMENT，额度CHECK、唯一编号及本库外键兜底。

| 库/表 | 运行账号权限增量 |
| --- | --- |
| trade_payment.refund_source | SELECT,INSERT；UPDATE(source_no,reserved_cents,succeeded_cents,active_refund_no,version)，source_no只供同号无值变化 |
| trade_payment.refund_order | SELECT,INSERT；UPDATE(status,channel_refund_no,channel_version,channel_updated_at,succeeded_at,review_required,last_error,version) |
| trade_payment.refund_account | 原权限追加UPDATE(reserved_refund_cents,succeeded_refund_cents,version) |
| trade_payment.process_task/outbox_event/channel_notification | 沿用SELECT,INSERT,UPDATE，新增REFUND类型无需结构变更 |
| trade_payment.payment_ledger/audit_log | 沿用SELECT,INSERT，禁止UPDATE/DELETE |
| trade_payment_simulator.channel_transaction | 原权限追加UPDATE(reserved_refund_cents,succeeded_refund_cents) |
| trade_payment_simulator.channel_refund | SELECT,INSERT；UPDATE(status,channel_version,succeeded_at,updated_at) |
| trade_payment_simulator.refund_fault_scenario | SELECT,INSERT；UPDATE(refund_no)仅同号默认无值变化 |
| trade_payment_simulator.refund_process_task/refund_callback_task | SELECT,INSERT；UPDATE(state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error) |
| trade_payment_simulator.audit_log | 沿用SELECT,INSERT，禁止UPDATE/DELETE |

所有表不授予DELETE/DDL，不授予跨库权限；独立迁移账号执行V2。通道退款任务复用统一任务Mapper，payment_no列实际保存退款号，COMMENT已说明。退款事实及授权绑定字段不得通过运行账号更新。

## 环境与关注点

支付新增必需TRADEFLOW_AFTERSALE_SERVICE_TOKEN，YAML映射tradeflow.internal.aftersale-token；其他沿用ORDER_SERVICE_TOKEN、PAYMENT_SERVICE_TOKEN、CHANNEL_SERVICE_TOKEN、CALLBACK_SECRET、固定服务URL及各库独立凭据（均为TRADEFLOW_前缀）。通道无新环境变量，退款回调使用原payment-service-url拼固定refunds路径。源码不含密钥。

主任务仍需统一完成允许的跳过测试编译、V2迁移、运行权限授权和服务启动；本子任务不执行。真实MySQL并发约束、事务回滚、回调/HTTP Outbox与重启恢复均未运行验证。库存完成与授权COMPLETED由订单/商品异步负责；订单ACK不是库存成功。没有人工重启或资金修正入口，30次预算耗尽需受控人工流程。
