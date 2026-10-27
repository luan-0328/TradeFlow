# P0 消息拓扑漂移与历史事件受控补投

本次修复不改变资金、库存和业务事件契约。`SENT`表示Broker确认且没有mandatory return；只要一个目标仍可路由，它不能证明所有预期队列收到，更不能证明所有消费者应用成功。绑定恢复不会自动补发历史消息。

## 生产处置路径

1. 独立`Rabbit-Exporter.py`以monitor账号读取`/tradeflow`的队列、交换机和绑定。业务JVM仍使用各服务最小AMQP权限，不载入管理员/monitor凭据，也不自动声明或修复拓扑。
2. 部署定义和Exporter共用`deployment_rabbit_topology.expected_topology()`：2个持久topic交换机、8个持久队列（含DLQ）、14条绑定。检查声明属性、完整arguments、队列有效policy及每个交换机绑定集合。额外TTL/容量/policy也报警；任何策略变更需先更新并审阅契约。指标标签只来自固定契约。
3. `tradeflow_rabbitmq_topology_match{kind,resource,vhost}`逐项输出0/1；`tradeflow_rabbitmq_topology_valid`聚合完整性。管理读取失败输出NaN、`collection_success=0`，保留最近成功时间，避免把未知当健康。`TradeFlowRabbitTopologyDrift`持续1分钟后触发；采样失败/陈旧由独立告警覆盖。默认30秒采样，因此检测不是即时或与publish原子一致。
4. 运维使用独立部署权限修复绑定，等待拓扑成功采样并独立复核。根据首次异常时间（至少向前覆盖一个采样周期）、恢复时间、事件类型及目标确定受影响事件清单；取证关联事件ID、原文摘要、已应用/缺失目标及工单。不可仅凭当前拓扑健康推断历史事件已收到。
5. 最新平台身份核验后调用原有服务入口：`POST /api/v1/admin/{payment|order|aftersale}-messages/outbox/{eventId}/retry`，正文`{"reason":"工单/故障时间/缺失目标/核验结果"}`。每次只处理明确事件，响应带`operationId`；不要用SQL批量改状态、改原文或创建新事件ID。后台“人工待办”选择对应服务“发布”来源，再点“查看已发送原事件”和“查看原记录”，使用“补投原事件”填写必填补投原因。列表仅返回`wire_frozen`原文存在标志；SENT且该标志为真才给出`REDELIVER_ORIGINAL`候选动作。历史SENT不计入故障待办数量。
6. `MessageOperations`锁定原Outbox；RUNNING即使过期也拒绝人工夺租约。SENT必须已有合法冻结`wire_payload`且信封与元数据相符，缺原文不能重新构造。READY请求、`claim_version`递增及`message_operation`审计同一事务提交；SENT补投使用`REDELIVER_SENT_ORIGINAL`动作。审计失败整笔回滚。
7. 后台普通publisher重新领取并保留原ID/原wire，仍经所有资金`OutboxGuard`、事务外网络发送及owner/version/未过期租约围栏。旧执行者不能回写新请求；guard失败不会直接发布。平台请求成功只代表恢复意图提交，不代表资金可用或消息已补投完成。
8. 原交换机扇出重投会同时到达已成功队列；这些消费者按`consumer_name+event_id`和原摘要Inbox去重。不同正文隔离且不能覆盖原Inbox；没有按目标新造一条资金事实。核验缺失消费者已应用、已成功消费者无重复业务效果，以及延期/隔离/资金冻结是否需要进一步处置，再关闭工单。

这个闭环是受控运维补投，不是每个消费者ACK的全局投递账本。当前拓扑无法还原短于采样周期的全部历史漂移；处置仍需要工单影响区间及业务恢复核验。

## 专属隔离验证

```powershell
python scripts/verification/messaging-topology-tests.py
# 不跑Maven：全新MySQL/RabbitMQ/Exporter/Prometheus，实际生产规则firing/恢复
python scripts/verification/messaging-recovery.py
# 需全工程Maven独占窗口；同一隔离fixture内执行5项JVM测试
python scripts/verification/messaging-recovery.py --maven
```

每次使用新`.local/messaging-recovery-<随机值>`目录、随机回环端口与随机凭据；先核验PID后代和全部监听，再接触依赖。不会复用`.local/rabbitmq`、业务数据库、固定监控实例，也不连接服务器。脚本只结束登记且映像/创建时间一致的进程。原始配置包含秘密，不作交付材料。

8项Python回归覆盖完整拓扑、部分扇出缺失、非持久交换机、额外TTL、有效policy、缺队列、额外绑定固定标签和未知采样NaN。真实隔离脚本使用monitor读取、真实Exporter、真实Prometheus及生产`alerts.yml`，删除一条退款绑定但保留另一个目标，观察告警firing并恢复消退。

5项Java测试使用真实生产`MessagingRuntime`监听容器、`MessageReceiver`、publisher、事务及Inbox：数据库前三次失败导致NACK/requeue及Broker redeliver；提交后断开连接导致ACK丢失和Inbox去重；绑定恢复后SENT原文补投并验证审计/guard/旧租约；缺原文/租约/审计故障/guard拒绝；完整Broker进程shutdown后同盘重启，禁用definitions再导入，验证持久原消息ID/正文及监听应用。

合成handler只记录测试业务效果，guard使用计数/明确拒绝替身；这些测试证明共同基础设施不会跳过guard，不替代真实资金guard正确性、七服务端到端、注册中心、性能或生产灾备验收。实际执行结果与证据目录见下文。

## 本地实测记录

- Python拓扑契约8项：通过（无网络）。
- 独立真实监控预检：`.local/messaging-recovery-fbbb4e2faf6849fabdf6d60aa3c87307/`通过。`topology-result.json`记录monitor写入拒绝、单条部分扇出绑定漂移、生产规则firing、恢复后消退及无效凭据拒绝；`result.json`记录全部自建监听回环、最终监听为空及所有自建依赖停止。
- 首次预检`.local/messaging-recovery-5ae0c0b8deb14dc18c776d90a097b817/`因Prometheus布尔参数语法错误失败；已修正且依赖全部停止。此轮不计入通过。
- 完整独占演练：`.local/messaging-recovery-90c6031493734e36b95db5704273d078/`通过；5项Java测试0失败、0错误、0跳过，原始`MessagingRecoveryMysqlTest.xml`与`maven.log`保存在本次目录。
- `listener-nack.json`：前三次本库失败，第四次接收成功，业务效果1份；Broker redeliver计数增加。`listener-lost-ack.json`：事务提交后真实消费连接关闭，容器报ACK通道关闭并重建，Inbox接收2次、业务效果1份、Broker redeliver计数增加。
- `partial-binding-recovery.json`：缺售后绑定时仍SENT且订单已应用；恢复绑定后不自动重发，经平台原消息补投后订单及售后效果各1份，guard调用2次，原wire摘要一致、旧claim拒绝回写、审计operationId存在。
- `replay-rejections.json`：RUNNING拒绝、SENT缺冻结原文拒绝、审计写失败整体回滚、补投guard拒绝且无新消息。`broker-restart.json`与`restart.complete`：节点进程实际PID27108关闭、同节点PID16164重启，同盘持久原ID/正文恢复，不重新导入definitions；恢复消息随后由生产监听容器应用并ACK。
- 同次完整演练再次验证真实生产Prometheus告警firing及恢复消退。`result.json`记录`brokerRestarted=true`、`ownedServerStopped=true`、`remainingOwnedListeners=[]`；所有自建监听均仅回环。未部署、未连接服务器、未访问既有业务依赖。

## 平台入口补充验证

三个服务新增`PlatformMessageActionAdviceTest`，各6项验证SENT冻结原文候选动作、缺冻结标志拒绝、非Outbox隔离、运行状态、原snake_case元数据及GET白名单。此补充未由分工自行运行Maven，由主线串行package统一验证。web-admin类型检查通过。

`p0-browser.mjs`新增第14阶段，点击真实平台入口补投原PaymentSucceeded，检查空原因禁提交、真实POST返回原eventId及operationId、GET恢复原SENT且相同聚合/事件类型的编号集合不变。可独立运行`--only sent-replay`；此模式需`seeds.orders.replayPaymentNo`为当前buyer所属且已真实成功的原支付号。主线按报告中的operationId对隔离库只读核验审计及冻结wire摘要，不扩生产审计读取接口。准备脚本不等于浏览器实测通过。
