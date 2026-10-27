# B5 共享消息基础设施交付报告

日期：2026-10-23。范围仅 contracts 的 MessagingContracts、common-web 消息包/依赖/自动配置 imports、三个 DDL 模板及本报告。没有修改业务服务或启动脚本，没有读取 `.local`，没有执行测试、构建、启动、业务请求、数据库/远程操作、commit 或 push。以下为源码静态自审结果，不代表已通过 Spring/RabbitMQ/MySQL 集成验收；root 的独立 spec/quality 审查与授权验证另行记录。

## Shared API（集成可直接使用）

包名 `com.tradeflow.contracts`：

```java
public final class MessagingContracts {
    public record Event(String eventId, String eventType, int eventVersion,
        String aggregateType, String aggregateId, long aggregateVersion,
        Instant occurredAt, String correlationId, String causationId,
        String traceId, Map<String,Object> payload) { }
}
```

包名 `com.tradeflow.messaging`：

```java
public interface EventHandler {
    String consumer();
    Set<String> eventTypes();
    void handle(MessagingContracts.Event event);
}
public interface OutboxGuard {
    void check(String aggregateType, String aggregateId);
}
public final class MessageOutbox {
    public String enqueue(String aggregateType, String aggregateId,
        long aggregateVersion, String eventType, String orderNo,
        String causationId, Map<String,Object> payload);
}
public final class MessageOperations {
    public Object list(String kind, int page, int size);
    public Map<String,String> retry(String kind, String id, String actor, String reason);
}
```

`DeferredMessageException(String code)` / `(String code,String message)` 表示合法前置条件暂缺；`QuarantinedMessageException` 提供同样两个构造器，表示绑定、金额、版本或其他不可恢复冲突。code 为 `[A-Z0-9_]{1,64}`；持久化只记录稳定 code，不存任意异常正文。未分类 RuntimeException 默认延期；DataAccessException 使事务整体回滚，由接收器有限退避再 NACK，不会把数据库故障 ACK 掉。

每个 consumer 仅注册一个 handler，eventTypes 必须为该队列固定事件集合的非空子集。只消费“配置 consumers 与已注册 handler”的交集；无 handler 的支付服务不创建 listener。handler 在基础设施 TransactionTemplate 本地事务内执行，禁止 RPC，不能另起独立事务提交业务；需调用已拆分的本地业务 apply。基础设施只绑定本服务 DataSource。

`MessageOutbox` 和 `MessageOperations` 在存在 DataSource 和 PlatformTransactionManager 时自动注册，不受 `enabled` 开关影响。enqueue 强制已有可写事务且该事务已绑定相同 DataSource，业务与 outbox 同提交；无事务/只读/其他数据源事务直接拒绝。新增审计事件在 enqueue 时完成信封校验和 wire_payload 冻结。资金发布必须有 OutboxGuard；没有则 `OUTBOX_GUARD_MISSING` 升级人工。无 guard 的 ORDER/AFTERSALE 审计发布允许。

## 配置与环境变量

自动配置为 `com.tradeflow.messaging.MessagingAutoConfiguration`，已注册 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。新增依赖使用 Boot 管理的 starter-amqp 与 spring-jdbc，不引入共享业务实体或测试代码。

| 属性 | 默认/范围 | Spring 标准环境变量 |
| --- | --- | --- |
| tradeflow.messaging.enabled | false；true 启动网络和恢复扫描 | TRADEFLOW_MESSAGING_ENABLED |
| tradeflow.messaging.consumers | 空列表；逗号分隔注册消费者 | TRADEFLOW_MESSAGING_CONSUMERS |
| tradeflow.messaging.poll-interval-ms | 1000，至少100 | TRADEFLOW_MESSAGING_POLLINTERVALMS |
| tradeflow.messaging.batch-size | 20，范围1–100；每轮每类最多该数 | TRADEFLOW_MESSAGING_BATCHSIZE |
| tradeflow.messaging.confirm-timeout-ms | 5000，范围100–10000 | TRADEFLOW_MESSAGING_CONFIRMTIMEOUTMS |
| spring.rabbitmq.host | root 配置 127.0.0.1 | SPRING_RABBITMQ_HOST |
| spring.rabbitmq.port | root 配置 5678 | SPRING_RABBITMQ_PORT |
| spring.rabbitmq.virtual-host | root 配置 /tradeflow | SPRING_RABBITMQ_VIRTUALHOST |
| spring.rabbitmq.username | 每服务用途账号，必须外部注入 | SPRING_RABBITMQ_USERNAME |
| spring.rabbitmq.password | 外部安全注入，不写报告/指令文件 | SPRING_RABBITMQ_PASSWORD |
| spring.rabbitmq.dynamic | 业务服务统一 false，禁止自动资源声明 | SPRING_RABBITMQ_DYNAMIC |
| spring.rabbitmq.publisher-confirm-type | 建议 correlated；消息运行时强制 CORRELATED | SPRING_RABBITMQ_PUBLISHERCONFIRMTYPE |
| spring.rabbitmq.publisher-returns | 建议 true；消息运行时强制 true | SPRING_RABBITMQ_PUBLISHERRETURNS |
| management.health.rabbit.enabled | 非参与服务设 false，避免健康检查引入无关 broker 依赖 | MANAGEMENT_HEALTH_RABBIT_ENABLED |

Spring 环境变量规则为属性转大写、点转下划线、删除连字符；项目 YAML 可以再显式映射自己的 `TRADEFLOW_*` 变量。其余 Rabbit 连接、TLS、心跳、连接超时使用 Spring Boot 标准属性；本层不自定义密钥。poll/batch/confirm 可调，租约30秒、事务超时20秒、5/15/60/300/900秒退避、10次或30分钟升级人工为固定协议。

```yaml
tradeflow:
  messaging:
    enabled: true
    consumers: order.payment,order.refund,order.audit
spring:
  rabbitmq:
    host: 127.0.0.1
    port: 5678
    virtual-host: /tradeflow
    dynamic: false
    publisher-confirm-type: correlated
    publisher-returns: true
```

支付 consumers 为空，售后为 `aftersale.refund`。所有运行账号 configure 权限为 `^$`；管理员在独立准备脚本预建拓扑，基础设施不创建 RabbitAdmin/Exchange/Queue/Binding/Declarables。common-web 新依赖会启用 Boot 的 Rabbit 自动配置，root 应显式设置 dynamic=false，并为 user/product/simulator 等非参与服务关闭 Rabbit 健康指标。

## 拓扑与最小权限

durable topic exchange `tradeflow.events`，durable topic DLX `tradeflow.dlx`。源队列和 DLQ 均由 root 预建 durable classic 队列，DLQ 为原队列名加 `.dlq`，DLX 路由键也为该完整 DLQ 名。所有 publish 为 persistent、mandatory，只有 correlated confirm ACK 且没有 return 才将本库发送状态置 SENT。

| consumer / queue | 接受类型与 routing key |
| --- | --- |
| order.payment / q.order.payment | PaymentSucceeded/payment.succeeded.v1，PaymentFailed/payment.failed.v1，PaymentClosed/payment.closed.v1 |
| order.refund / q.order.refund | RefundSucceeded/refund.succeeded.v1，RefundFailed/refund.failed.v1 |
| aftersale.refund / q.aftersale.refund | 同退款两种事件 |
| order.audit / q.order.audit | OrderPaid/order.paid.v1，OrderClosed/order.closed.v1，AftersaleCompleted/aftersale.completed.v1 |

建议 runtime Rabbit 资源权限：payment write=`^tradeflow\.events$`，read=`^$`；order/aftersale write=`^tradeflow\.(events|dlx)$`；order read=`^q\.order\.(payment|refund|audit)$`，aftersale read=`^q\.aftersale\.refund$`。configure 均 `^$`。如使用 Rabbit topic permissions，另限制各自事件 routing key 与自己 DLQ routing key；消费方的 topic read 需覆盖预绑定业务 routing key。应用不消费 broker DLQ，平台列表读取本库隔离记录，不需应用获取其他服务队列读权。

MySQL 运行账号仅授所属库：

| 表 | 必需权限 |
| --- | --- |
| outbox_event | SELECT, INSERT, UPDATE |
| inbox_message | SELECT, INSERT, UPDATE |
| deferred_event | SELECT, INSERT, UPDATE |
| message_failure | SELECT, INSERT, UPDATE |
| message_operation | SELECT, INSERT；禁止 UPDATE/DELETE |

不需要 DELETE/DDL/跨库权限；业务表原有权限不由本层变更。message_operation SELECT 还用于旧 outbox 人工恢复后以最新操作时间开启新的30分钟窗口。数据库连接/迁移以 UTC 和 READ COMMITTED 为基线。root 以迁移账号应用新 Flyway，不让运行账号执行模板。

## 精确 DDL 模板与状态

* `docs/database-target/b5-messaging-tables.sql`：分别复制到 payment/order/aftersale 的新迁移，建 inbox_message、deferred_event、message_failure、message_operation。
* `docs/database-target/b5-outbox-table.sql`：分别复制到 order/aftersale 的新迁移；完整复制现有 payment outbox 结构并增加 nullable LONGTEXT wire_payload。
* `docs/database-target/b5-payment-alter.sql`：仅追加支付已有 outbox_event.wire_payload，不改已发布 V1/V2。

所有新表、所有新字段都有中文 COMMENT。模板没有 USE、跨库外键、DROP 或 IF NOT EXISTS，不隐式掩盖结构漂移。

| 表 | 唯一键/关键字段/状态 |
| --- | --- |
| inbox_message | UNIQUE(consumer_name,event_id)，payload_hash；status=RECEIVED/APPLIED/DEFERRED/QUARANTINED |
| deferred_event | deferred_id=`def_`+32位UUID；UNIQUE(consumer_name,event_id)；raw_body LONGBLOB；status=READY/RUNNING/APPLIED/MANUAL_REVIEW/QUARANTINED；cycle_started_at 与租约字段 |
| message_failure | failure_id=`fail_`+32位UUID；UNIQUE(consumer_name,event_id,payload_hash)；raw_body LONGBLOB；reason_code；status=READY/RUNNING/SENT/MANUAL_REVIEW 仅代表DLQ发布；disposition=QUARANTINED/RETRY_REQUESTED/RESOLVED 代表业务处置 |
| message_operation | operation_id=`mop_`+32位UUID；kind、target_id、actor_id、reason、before_status、action、created_at；仅追加审计 |
| outbox_event | 保持现有 payment 列及约束；event_id为恢复编号；status=READY/RUNNING/SENT/MANUAL_REVIEW；wire_payload为冻结完整信封 |

无法解析合法 eventId 的非法消息用 `invalid_`+原始正文SHA256前48位派生稳定ID；合法ID仍保留原值。DLQ messageId 与 `x-original-event-id` 使用原ID，附加 `x-failure-id`、`x-failure-code`、`x-consumer` 头，正文保持原始字节。

## 可靠性与事件校验

codec 复制全局 ObjectMapper 后独立关闭未知字段失败、拒绝重复 JSON 键及尾随正文；严格 UTF8，最大业务信封1MiB。完整已接收 JSON 树递归排序对象键后计算 SHA256，数组顺序保留、未知扩展字段参与摘要。原始 byte[] 独立持久，恢复不从已丢未知字段的 Event DTO 重构正文。

强制 eventVersion 为可安全转 int 的1、aggregateVersion为非负long；检查8种类型、consumer和路由绑定、PAYMENT/REFUND/ORDER/AFTERSALE 聚合类型、聚合编号、订单关联编号。资金载荷直接为完整 ChannelFact 而非嵌套fact，检查整数正金额≤100000000分/CNY、channelVersion至少1且与aggregateVersion相等、类型与终态一致；所有资金事实必须显式带succeededAt字段，成功必须为有效时间，FAILED/CLOSED必须为null。支付channelTransactionNo始终为合法ID；退款channelRefundNo必须出现，失败允许null或最多64字符文本，成功必须为非空合法ID。审计必须带OrderPaid.paymentNo、OrderClosed.reason（文本，最多255个Unicode码点）、AftersaleCompleted.refundNo，其余绑定字段同样必需；与root新版8事件Schema逐字段对齐。最终权威金额/绑定仍由服务本库 handler 与 OutboxGuard 校验，不按 aggregateVersion 丢弃较旧资金事实。

历史 payment outbox 的 payload 保持 Event{eventId,fact} 原结构。首次领取后按行中稳定 eventId、聚合版本、关联/因果/trace及 created_at 包装完整信封；校验历史包装器 eventId 与行一致；在短事务中围栏校验后写 wire_payload。后续包括人工重发复用该列相同UTF8正文，绝不重新使用 Instant.now 或重新生成事件ID。

领取使用本库 SELECT FOR UPDATE SKIP LOCKED，READY到期和RUNNING过期均可领，立即提交，递增 claim_version/attempt_count，设置 owner 与30秒租约。网络发布在事务外，再按 owner+claim_version+RUNNING+有效租约回写。租约不保证网络只执行一次；确认丢失可原ID重复，资金和库存仍由业务唯一键与状态机保护。

Broker 入口：inbox+本地业务+后续任务同事务；提交后才ACK。前置暂缺或暂时业务错误使业务整体回滚，在独立事务保存 deferred+DEFERRED inbox后ACK；同ID重复入口看到DEFERRED只确认已可靠交接。恢复任务直接调用专门resume入口，不再经过入口去重，任务/inbox/业务APPLIED同事务；最终围栏UPDATE必须影响1行，否则业务也回滚。

消费相关多表加锁统一为 **inbox→deferred→failure**：初次接收、失败交接、非法消息隔离先锁inbox；resume及其失败回写用已提交claim中的不可变consumer/event/hash先锁inbox，再锁延期任务重验围栏与绑定；人工retry先普通读取不可变绑定，在事务内按inbox→既有deferred→目标failure加锁并重验绑定。retry(deferred)同样不能先锁延期行等待inbox。无延期行的人工重放在持有同一inbox后才新建，其他同事件创建者也必须先持该inbox，因此不会反向等待既有延期锁。只领取或只回写单个发布任务的事务不获取inbox，领取立即提交，不与消费多表链形成环路。

Schema、绑定与不可恢复冲突或延期超限，先持久 message_failure 原文才ACK；同ID异内容不覆盖已有inbox，独立隔离新摘要。隔离入库与broker DLQ发送为两个状态，数据库ACK不表示DLQ已送达。独立发送器 mandatory+confirm 原文到对应DLQ，失败留本库重试，10次或30分钟转人工。数据库接收失败每次delivery最多3次，退避500/1500/3000ms后NACK requeue；本库恢复后依稳定ID去重，不形成快速requeue热循环。

## 平台操作语义

list 返回 `{page,size,total,items}`，page从1开始，size范围1–100；items仅元数据，不返回原始消息或资金正文。kind只允许 outbox/deferred/failure，分别使用 event_id/deferred_id/failure_id作为retry id。非法kind/page/参数400，不存在404，执行中/已应用/正文或inbox冲突409，稳定BusinessException码。

retry 必须给合法 actor 和1–1000字原因；Controller 先调用用户服务核验最新平台权限，**该远程身份核验在本层事务外完成**。所有成功恢复请求与 message_operation 审计同事务。RUNNING一律拒绝人工夺租；READY和MANUAL_REVIEW可原ID重试；SENT outbox可同wire重发。deferred恢复将绑定摘要一致的原inbox置DEFERRED，原任务原文开启新一轮，不可重放已APPLIED任务。

failure 的两类情况在返回 target 中明确：DLQ status=MANUAL_REVIEW 时先恢复原文DLQ投递（target=dlq、action=RETRY_DLQ_ORIGINAL）；其他非RUNNING状态允许合法且仍隔离的原文通过专用deferred入口业务重放（target=deferred、action=REPLAY_ORIGINAL），保持DLQ发送状态独立。非法Schema、同ID异内容或已APPLIED不允许篡改后重放；先修复服务兼容能力/查明来源，再按原文重试。成功重放将disposition置RESOLVED。返回 `{id,kind,status:'READY',target,operationId}` 仅表示本库恢复请求已受理，不表示资金成功、业务已应用或broker已收到。

## 静态自审记录与边界

已逐项静态复核：稳定API；无handler服务不注入失败；独立codec兼容扩展；完整树摘要/原文保存；旧outbox首次冻结/包装器绑定；mandatory+confirm+无return判定；领取后立即提交；过期RUNNING回收；owner/claim/有效期围栏；延期执行最终围栏失败回滚业务；入口去重与专门恢复分离；业务失败先回滚再交接；同ID异内容保留原inbox；数据库失败不ACK；隔离与DLQ到达独立；DLQ人工恢复；原文恢复与原因审计同事务；平台白名单及错误码；按库最小权限和全部中文COMMENT。

修复了静态发现的 List.of 可空原因码风险、eventVersion 大整数低位截断、无handler集合注入、延期事务租约临界点可能提交业务但未完成任务、历史包装器ID未绑定等问题。未执行编译、真实并发/故障、Rabbit confirm/return集成、Spring事务集成或业务验证；本报告不能作为这些检查通过的证据。单节点classic队列不等于高可用，本层提供至少一次投递与本地幂等/可靠恢复，不宣称 exactly-once。

独立首轮审查反馈的两个P2已按源码修复：审计事件必需字段/资金版本与成功时间约束补齐；人工failure恢复、自动resume和初次handoff的锁顺序统一。修复后重新静态逐路径检查了正常应用、延期再失败/成功、Schema隔离、同ID异内容、deferred人工恢复、failure业务重放、DLQ人工恢复及单表claim/finish。未执行测试或构建，交root继续独立复核与统一验证。

最终静态审查补充：人工failure重放后，延期若再次永久失败或耗尽，在同一围栏事务内将匹配consumer/event/hash的原隔离记录从RETRY_REQUESTED恢复为QUARANTINED，并递增version；保留原始正文、首次reason_code和独立DLQ发送状态/published_at。本次失败原因保存在deferred.last_error，最终围栏失效仍回滚所有处置变化。此限定修补未执行测试或构建，需root对最终源码再统一编译。
