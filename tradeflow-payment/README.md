# B4 支付与退款服务

本批实现本人支付受理与查询、订单内部关闭/查询、通道回调、恢复任务和持久HTTP Outbox。POST支付返回202及PaymentContracts.View，表示已经本地持久受理，结果需查询。B4追加退款执行、来源与支付双额度、HMAC退款回调及退款HTTP Outbox；迟到支付使用订单激活的LATE_PAYMENT授权。

恢复任务与资金意图按“task → payment_order”锁顺序串行，网络调用全部在事务外。提交/关闭调用前先把恢复步骤保存为QUERY；超时、进程退出和租约接管均先查原支付号。查无记录只有未存在close_intent时才允许按原号重新提交；存在关闭意图必须调用通道close建立真正墓碑。恢复任务与Outbox使用30秒租约、递增claim_version围栏，陈旧执行者不能完成新租约。

回调在读取最多64KB原始字节后校验五分钟HMAC，再解析与占用notificationNo。签名输入为timestamp + 换行 + nonce + 换行 + rawBody，签名为HMAC-SHA256十六进制，头为X-Notification-Timestamp、X-Notification-Nonce、X-Notification-Signature。同通知号同摘要ACK，异摘要409；未验真不写去重表。

响应、回调和主动查询统一校验paymentNo/orderNo、金额、CNY、交易号、版本和终态。成功状态、唯一PAYMENT_SUCCEEDED流水、refund_account初始化与唯一终态事件同事务提交。相反终态、同版本异事实或交易号变化持久标记review_required=YES并审计，恢复任务转人工，禁止覆盖已确认事实。人工核验和受控恢复入口不在本批范围。

Outbox向订单POST /internal/v1/orders/{orderNo}/payment-results传固定PaymentContracts.Event，只有ApiResponse.OK且data.ack=true才置SENT。丢响应仅重发原eventId。本批没有MQ发布实现。

## 配置

- TRADEFLOW_PAYMENT_DB_USER / TRADEFLOW_PAYMENT_DB_PASSWORD：业务账号。
- TRADEFLOW_PAYMENT_MIGRATION_USER / TRADEFLOW_PAYMENT_MIGRATION_PASSWORD：独立DatabaseMigration进程账号，不注入业务JVM。
- MYSQL_HOST默认127.0.0.1；MYSQL_PORT在dev/迁移默认13316，prod模板默认3306；数据库trade_payment。
- TRADEFLOW_JWT_PUBLIC_KEY：RS256公钥文件；TRADEFLOW_JWT_ISSUER默认tradeflow。
- TRADEFLOW_PAYMENT_SERVICE_TOKEN：payment到user/order，X-Caller-Service=payment。
- TRADEFLOW_ORDER_SERVICE_TOKEN：入站order内部入口，X-Caller-Service=order。
- TRADEFLOW_CHANNEL_SERVICE_TOKEN：payment到模拟通道，X-Caller-Service=payment。
- TRADEFLOW_CALLBACK_SECRET：独立回调HMAC秘密。
- 统一使用Nacos发现tradeflow-user、tradeflow-order和tradeflow-payment-simulator；不再配置下游URL。设置NACOS_SERVER_ADDR及实际客户端凭据、namespace/group，见[运行手册](../docs/runbooks/nacos-discovery.md)。
- TRADEFLOW_PAYMENT_TEST_DB_URL：仅test隔离库URL，默认trade_payment_test；本批未运行测试。

业务进程Flyway始终关闭；DatabaseMigration为独立迁移入口。一个application.yml包含公共/dev/test/prod配置。恢复/Outbox定时任务启用后将正常处理真实持久任务，不能把启动行为误称为业务验证。出站HTTP固定1秒连接、3秒请求超时，无应用层隐式写重试、禁止跳转。

## 本批表与运行权限

| 表 | 运行权限 | 用途 |
| --- | --- | --- |
| payment_order | SELECT,INSERT,UPDATE | 唯一支付意图/关闭意图/已确认通道事实及人工标记 |
| channel_notification | SELECT,INSERT,UPDATE | 已验真摘要去重及隔离 |
| idempotency_request | SELECT,INSERT,UPDATE | 本人支付同键异参约束；UPDATE仅幂等插入无变更分支 |
| process_task | SELECT,INSERT,UPDATE | 唯一支付恢复步骤与租约 |
| outbox_event | SELECT,INSERT,UPDATE | 终态事件与HTTP交付租约 |
| payment_ledger | SELECT,INSERT | 不可变成功入账流水 |
| audit_log | SELECT,INSERT | 不可变矛盾处置审计 |
| refund_account | SELECT,INSERT,UPDATE | 成功本金与退款执行占用、成功金额转换 |

运行账号不需要DELETE/DDL。payment_order本批不存固定Intent尚未提供的merchant_id；完整目标SQL保留在docs/database-target/payment-full-V1.sql，后续商家维度需扩充权威契约后另批迁移。初次V1只包含以上8张表；B4只追加V2，不修改已执行的V1。

## 验证边界

完成代码与SQL静态自审。按用户要求未编写或运行测试、未发送业务请求、未连接远程服务器、未读取.local密钥、未迁移/启动服务、未执行Maven编译、未commit/push。根任务统一执行允许的编译、迁移和启动，并记录结果；本说明不代表业务、并发或故障场景验证通过。

## B4退款契约与恢复

- POST `/internal/v1/refunds` 使用 `RefundContracts.Submit(refundNo,sourceNo,orderNo)`，GET `/internal/v1/refunds/{refundNo}` 返回固定View；不存在为404 `REFUND_NOT_FOUND`。
- 入站 `order` 及 `TRADEFLOW_ORDER_SERVICE_TOKEN` 仅能操作 `CLOSE:ord_...` 来源；`aftersale` 及新增必需变量 `TRADEFLOW_AFTERSALE_SERVICE_TOKEN` 仅能操作 `as_...`。YAML映射 `tradeflow.internal.aftersale-token`，密钥由环境注入。
- 首次执行在事务外GET订单 `/internal/v1/refund-authorizations/{sourceNo}`，要求EXECUTING及固定类型LATE_PAYMENT/UNSHIPPED_FULL。金额由权威授权计算，不接受客户端金额。相同退款号必须绑定原来源与订单，重放不重新占用额度；来源授权版本和摘要不可变。
- 锁顺序固定 `payment_order → refund_account → refund_source → refund_order`。条件更新同时约束原支付本金和来源总授权，来源同一时间仅一笔未决执行。明确FAILED仅释放执行占用，订单权益及售后义务保持；UNKNOWN和PROCESSING均保留占用。
- 成功状态、双额度转换、唯一REFUND_SUCCEEDED出账流水及退款Outbox同一事务。退款号、支付号、订单号、来源号、金额、币种、通道退款号、版本与成功时间统一核对；相反终态及同版本异证据冻结为人工核验，内部查询和未发送Outbox均拒绝继续传播冻结事实。
- POST `/callbacks/v1/refunds` 使用与支付相同头和原始字节HMAC协议，64KB上限、五分钟窗口；验真后才占用通知号，同号同摘要ACK，同号异参409。
- 退款恢复独立领取task_type=REFUND，领取事务只锁任务；30秒租约、owner及claim_version和有效期围栏。写请求前持久QUERY，超时或重启先查原号，仅明确404时复用原退款号再次提交，最多30次网络执行后转人工并保留事实与占用。
- 退款Outbox按aggregate_type=REFUND独立领取，只向订单 `/internal/v1/orders/{orderNo}/refund-results` 投递RefundContracts.Event；API信封OK且data.ack=true后完成。ACK表示订单持久接收，并不表示库存回补已完成。本批没有MQ，也不额外通知售后。

V2新增 `refund_source`、`refund_order`，运行账号需两表SELECT/INSERT/UPDATE；`refund_account`新增UPDATE权限。既有process_task、outbox_event、channel_notification、payment_ledger、audit_log沿用原权限。流水和审计仍只允许SELECT/INSERT，所有表不授予DELETE或DDL。绑定及授权证据列仅插入，额度/状态列按Mapper受控更新；具体部署权限见B4报告。

B4仅完成代码/SQL静态自审，未编译、迁移、启动或执行业务验证。
