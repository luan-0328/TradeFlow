# B4 发货收货、未发货售后与退款

本批按开发资料06的B4实施。实现商家整单发货、本人确认收货、未发货整单全额售后、支付与来源双额度、独立通道退款、退款后销售回补，以及B3迟到支付退款。金额仅退款与退货退款保留B6；并未删除“轻微破损、不退货补偿部分金额”的设计。

## 正常流程与入口

用户下单支付，订单PAID并完成库存CONFIRMED后，商家可以登记整单发货；本人确认收货后订单COMPLETED。物流仅模拟承运商与单号快照，没有接入快递公司或自动收货。

| 入口 | 当前行为 |
| --- | --- |
| GET /api/v1/merchant/orders | 最新商家身份，过滤所属商家订单 |
| POST /api/v1/merchant/orders/{orderNo}/ship | body carrier/trackingNo，Idempotency-Key必填；库存未确认或有售后锁拒绝 |
| POST /api/v1/orders/{orderNo}/receive | 本人已发货订单确认收货，Idempotency-Key必填 |
| POST /api/v1/aftersales | 本人未发货整单申请，body orderNo/type=UNSHIPPED_FULL/reason；Idempotency-Key必填 |
| GET /api/v1/aftersales、/{aftersaleNo} | 只读本人申请和进度 |
| POST /api/v1/aftersales/{aftersaleNo}/cancel | 审核前持久撤销，可靠终止原授权后才释放权益 |
| GET /api/v1/merchant/aftersales、/{aftersaleNo} | 商家只读所属售后 |
| POST /api/v1/merchant/aftersales/{aftersaleNo}/review | 商家同意原全额或拒绝，原因留审计 |
| POST /api/v1/merchant/aftersales/{aftersaleNo}/retry-refund | 旧退款明确失败才可新尝试，未知禁止换号 |
| GET /api/v1/admin/refund-tasks | 平台查看订单库退款及回补义务 |
| POST /api/v1/admin/refund-tasks/{sourceNo}/retry | body reason/newAttempt；普通重启原执行，newAttempt仅CLOSE来源且旧执行明确FAILED |

外部业务经8080网关，订单/支付/售后各自验签，并查询用户服务最新权限和撤销版本。商家归属不能通过外部头自报。申请、审核和退款新尝试都要求Idempotency-Key（ASCII最多64）；撤销以原售后号幂等。售后列表使用limit/before游标，商家订单与订单退款任务使用page/pageSize。平台另有GET /api/v1/admin/aftersale-tasks与POST /api/v1/admin/aftersale-tasks/{aftersaleNo}/retry，body reason，仅按原号恢复。实际参数与已实现路径以运行时Swagger及更新OpenAPI为准。

## 三层业务边界

订单拥有购买件权益和履约互斥，支付拥有资金本金、退款执行额度和流水，模拟通道拥有独立真实支付/退款事实。售后服务协调审核和流程，不跨库改订单或资金。

发货与未发货退款授权竞争同一订单行。授权事务按订单→商品行→购买件→授权加锁，每件记录active_aftersale_no及reserved_refund_cents，整单汇总与购买件一起提交。该预留是可跨重启恢复的业务状态，不是通知其他线程“正在执行”的锁。

来源as_号属于售后；CLOSE:ord_号属于迟到支付。订单授权内容version固定，RESERVED审核后激活EXECUTING，激活后不可普通撤销。支付事务外查询该授权，短事务按支付→本金账户→来源→退款锁定，并原子限制成功+处理中不超过本金与来源授权两个上限。退款请求不能由前端指定金额。

成功事实、额度转换、唯一资金流水、HTTP Outbox同一支付事务；订单以事件号及授权唯一settled_refund_no双层防重复，购买件预留转成功金额，同事务持久后续回补任务。ACK只表示事实已保存，不代表库存完成。

## 库存与退款时点

UNSHIPPED_FULL退款成功后，先幂等确认原COMMIT预占，再用CAN:as_固定收据执行UNSHIPPED_CANCEL回补；数量只取原预占明细，不接受调用方自报SKU与数量。原预占始终CONFIRMED，不反写RELEASED。回补事实、库存余额及delta流水同事务，以原预占/来源/收据唯一约束防不同号重复入库。确认回补后订单CANCELLED、授权COMPLETED，售后才完成。

迟到支付以固定CLOSE来源授权退款，原库存RELEASE决策保持。退款成功后释放原预占，才最终CLOSED。历史B3保留的LATE_PAYMENT记录通过扫描创建同一授权，不重复生成来源。首次退款号稳定派生，明确失败后平台可审计创建新执行号，未知不能换号。

## 恢复与人工

创建售后先持久CREATING与恢复任务，再申请订单授权；拒绝/撤销可靠终止授权，终止先到墓碑阻止迟到授权。批准与撤销竞争同一售后阶段，批准后先持久执行意图再激活授权。

支付或退款响应超时先查原编号，UNKNOWN不当失败。退款明确FAILED会释放支付执行额度，却保留订单商品权益、履约锁和售后退款义务；受控重试使用新退款号，成功后不能再次退款。

领取事务短，网络在事务外，任务30秒租约与claim_version使旧执行者不能覆盖。预算耗尽保留人工任务；可信成功新到达时，订单SETTLE拥有独立阶段预算，即使先前等待结果任务已转人工也能重新开始回补。一般人工重启必须平台/所属商家有效身份、原因和审计，不提供直接修改资金终态的接口。

人工冻结的矛盾支付或退款阻断内部权威View及未发送Outbox。已经在网络中的通知无法撤回；通道终态不可反转是协议前提，违约需人工核验，后续B7对账处理。

## 独立通道退款

POST /channel/v1/refunds与GET /channel/v1/refunds/{refundNo}使用channel服务凭据；原支付必须真实SUCCEEDED，按原支付串行退款本金预算。POST /simulator-admin/v1/refund-scenarios只在dev按refundNo一次绑定场景，独立管理员凭据和原因。支持SUCCESS/REJECT/PROCESSING/RESPONSE_TIMEOUT/CALLBACK_LOST/CALLBACK_DUPLICATE，退款场景与支付场景隔离。

退款回调/callbacks/v1/refunds沿用原始UTF8正文HMAC-SHA256、五分钟UNIX秒窗口、nonce及64KB上限，验真先于通知号去重。可信响应、查询与回调共用事实应用器。HTTP Outbox只向订单交付退款事实；售后查询退款及订单授权完成状态恢复。B5再引入MQ，不把HTTP重试称为RabbitMQ。

## 本机运行与迁移

根目录mvnw.cmd -B -ntp -Dmaven.test.skip=true package，scripts/Start-Local.ps1先执行独立Flyway迁移再启动七服务。本批新增订单V4、商品V3、支付V2、模拟器V2，售后首次V1按本批拆分；已执行历史迁移不变。售后拥有trade_aftersale，各服务只访问所属库。

.local/dev-env.json受ACL保护，增量补售后数据库凭据和TRADEFLOW_AFTERSALE_SERVICE_TOKEN，原秘密不轮换。业务JVM只注入必要数据库和调用用途秘密，不含迁移/root凭据；账本/审计只追加。内部静态服务凭据和本机HTTP仍为开发拓扑，生产TLS/服务JWT/轮换后续完善。

本轮没有测试、业务演示、并发、故障注入或压测。编译、迁移、UP和静态审查只能说明各自检查结果，实际记录见verification.md，不能称为交易或资金正确性验收。

> B5已将资金HTTP Outbox定时交付切换为RabbitMQ，并新增Inbox/延期/隔离和DLQ；本文HTTP交付说明为B4历史批次，当前消息行为见b5-development.md。
