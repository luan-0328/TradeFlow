# B3 支付与关闭屏障

本批实现支付业务代码和独立模拟通道，金额使用整数分，服务分别持有trade_payment与trade_payment_simulator。不连接远程，不运行测试。实际构建、迁移及启动记录见verification.md。

## 已实现接口

| 入口 | 用途 |
| --- | --- |
| POST /api/v1/payments | 本人支付，body仅orderNo，Idempotency-Key必填，202只表示持久受理 |
| GET /api/v1/payments/{paymentNo} | 本人查询持久支付事实 |
| GET /api/v1/admin/payment-ledger | 平台资金流水分页 |
| GET /internal/v1/orders/{orderNo}/payment-intent | payment读取订单权威金额、支付号与买家 |
| POST /internal/v1/orders/{orderNo}/payment-results | payment持久事件交付，ACK表示事实和后续任务已落库 |
| POST /internal/v1/payments/{paymentNo}/close | order申请关闭；即使支付尚未创建也建立关闭意图 |
| GET /internal/v1/payments/{paymentNo} | order查询本地权威结果 |
| POST /internal/v1/payments/{paymentNo}/query-channel | order触发原支付号通道查询 |
| POST /internal/v1/reservations/{reservationNo}/confirm | order以COMMIT确认原预占，扣减reserved，不回增available |
| POST /channel/v1/payments | 模拟通道接受完整支付绑定 |
| POST /channel/v1/payments/{paymentNo}/close | 通道真实关闭，先关闭也创建墓碑 |
| GET /channel/v1/payments/{paymentNo} | 独立通道事实；404不是关闭成功 |
| POST /callbacks/v1/payments | 原始正文HMAC验证后统一应用事实 |
| POST /simulator-admin/v1/scenarios | 仅dev按支付号一次配置场景，独立管理员凭据 |

外部用户入口统一走8080网关；internal/channel/callback/scenario不通过公开路由。运行Swagger见8083、8084、8086的/swagger-ui/index.html；docs/contracts/openapi.json同时保留后续PLANNED目标。

## 状态、事务和恢复

支付金额只能来自订单快照，不能前端自报。支付任务先持久意图和固定paymentNo，再事务外请求通道。发出写操作前持久QUERY恢复步骤，响应未知时先查询原号。成功流水、退款账户初始化和Outbox同一事务提交；退款账户初始化不表示退款功能已经实现。

订单PENDING_PAYMENT接受支付时写PAID+COMMIT，激活原订单流程确认库存。待支付取消或到期写CLOSING+RELEASE+PAYMENT_REQUIRED；通道明确CLOSED/FAILED后才释放预占。创建中尚未授权支付的关闭可直接释放。原CREATE_ORDER/QUOTE_STOCK任务在本批继续承担确认和关闭，不因名字误解为只能创建；阶段激活递增claim_version并重置恢复预算，统一task→intent→order锁顺序。网络调用不持数据库事务。

关闭已成立却查到SUCCEEDED，保留LATE_PAYMENT_REFUND_REQUIRED并转人工，订单不会伪装成退款完成。B4前不能自动消除这项资金义务，不得手工改成CLOSED掩盖。PAID尚无发货接口；后续发货必须要求库存CONFIRMED。

矛盾终态不覆盖原事实，持久review_required与审计；内部权威结果返回PAYMENT_MANUAL_REVIEW，未发送Outbox隔离MANUAL_REVIEW，阻止自动释放。发送前检查不能撤回已经在网络中的旧通知；通道终态不可反转是本协议前提，真实通道违约需人工核验与后续对账，不能宣称跨服务原子撤回。

B3使用持久HTTP交付Outbox，不是RabbitMQ。重试复用eventId，只有订单code=OK且data.ack=true才SENT。租约和claim_version保护旧执行者回写，业务编号仍是远程幂等边界。支付恢复及Outbox最多30次自动网络尝试，耗尽后保留人工任务；本批未提供支付/模拟器人工重启API，不建议直接改数据库绕过核验。

## 模拟场景与秘密

场景body为paymentNo/mode/actorId/reason，Header X-Simulator-Admin-Token。SUCCESS、REJECT、PROCESSING、RESPONSE_TIMEOUT、CALLBACK_LOST、CALLBACK_DUPLICATE确定性绑定支付号。PROCESSING最早五秒后成功；RESPONSE_TIMEOUT先提交成功再返回504。这里只说明实现，未执行场景验证。

签名头X-Notification-Timestamp为UNIX秒，五分钟窗口；另有X-Notification-Nonce和X-Notification-Signature。HMAC-SHA256覆盖timestamp+换行+nonce+换行+UTF8原始body（最多64KB）。验真先于通知号去重，同号异摘要409，合法重放ACK。

.local/dev-env.json受本机ACL保护，启动时补充PAYMENT_SERVICE_TOKEN、CHANNEL_SERVICE_TOKEN、CALLBACK_SECRET、SIMULATOR_ADMIN_TOKEN，各用途独立，已有秘密不轮换。迁移账号只进入短期迁移进程；业务账号无DDL，各库独立，账本与审计只追加。当前内部随机静态凭据适用于本机开发，服务JWT、TLS和轮换属于生产化工作。

首次支付V1仅8表，未存当前固定Intent未提供且未使用的merchant_id；完整目标归档保留，后续结算需迁移补齐权威商家来源。模拟器V1仅5表。订单追加V3，既有迁移不改写。中文注释随行为同步。

未运行自动化、端到端、并发、故障或性能测试，不能用编译成功或健康UP证明业务正确。

> B4已补迟到支付固定来源自动退款、库存释放和受控人工恢复；本文B3未实现退款的说明属于历史批次边界，当前见b4-development.md。
