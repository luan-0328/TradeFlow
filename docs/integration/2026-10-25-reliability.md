# 2026-10-25 本地可靠性实测

## 结论

本轮覆盖的库存、退款、幂等、租约、消息持久交接和缓存不变量通过实测；**完整工程验收尚未通过**。六个遗留应用启动测试全部在上下文初始化时报错，真实七服务/Nacos端到端、Broker重启及性能容量未验收。不能将组件用例通过等同于系统已经具备生产可靠性。

另外，实测确认消息扇出存在明确前提：只要一个目标仍然可路由，Broker ACK 和 mandatory 就不能发现另一个预期绑定缺失。恢复绑定不会自动重新投递已经标为 SENT 的原事件。这不是本轮已修复的能力。

全程本地执行，未连接服务器、未部署，未访问现有业务数据库或复用现有依赖实例。使用全新目录、随机回环端口和合成数据，保留日志及数据供复核。本轮自建服务已停止。

## 实际执行结果

| 检查 | 结果 | 证据边界 |
| --- | --- | --- |
| 43项单元/HTTP边界/Mapper绑定测试 | 通过，0跳过 | 包含网关真实临时HTTP上游；不能替代真实注册中心 |
| 库存预占可靠性7项 | 通过 | 真实ReservationService、XML、MySQL、Spring事务；商品/报价/初始余额为合成fixture |
| 商品与退货库存处置5项 | 通过 | 真实Mapper及事务，含商品CRUD、幂等、并发处置、审计失败回滚 |
| 退款可靠性7项、支付租约2项 | 通过 | 真实资金服务/Mapper/事务、签名通知；授权及通道RPC使用Mock |
| 消息7项 | 6项机制通过，1项拓扑风险复现符合预期 | 真实RabbitMQ4.1.8与MySQL；合成业务处理器、直接调用receiver，不是完整监听容器验收 |
| 商品缓存集成5项 | 通过 | 真实Redis7.0.15及StringRedisTemplate；另复跑2项故障回源、2项XML测试 |
| 登录窗口与网关额度 | 通过 | MySQL/Redis真实32并发；网关Lua额度及窗口、登录窗口防倒退 |
| 支付进程中断恢复 | 通过 | 真JVM halt(73)、真MySQL、等待真实租约；通道为持久文件替身 |
| 备份工具11项安全检查 | 通过 | 合成文件及篡改/越界拒绝条件 |
| 六库备份恢复 | 通过 | 两个全新MySQL实例、六个合成库、证据关联与CHECK恢复；不是业务全库灾备或Compose恢复 |
| 六个ApplicationTest | **6项错误，未通过** | test配置与现有业务依赖不匹配；不得计入通过或用禁用生产校验掩盖 |

库存与退款的并发批次用于验证正确性，没有测得或宣称QPS、吞吐量、生产并发规模。

## 核心不变量的实测

### 库存

1. 12个不同订单争5件库存，恰好5个RESERVED、7个REJECTED，可用0、预占5。
2. 12线程重放同一预占号只扣一次，明细及预占流水各一份；同号异参拒绝。
3. 释放先到形成墓碑，迟到预占不能重新扣减。
4. 两个SKU中后一个不足，前一个的扣减、报价绑定、明细、流水一并回滚，再独立保存拒绝终态。
5. 确认与释放同时竞争，执行12轮，每轮只有一个最终决策；重复终态操作不重复生成流水。
6. 在库存余额已改后，通过触发器拒绝流水写入，验证预占、确认及释放均整体回滚，撤除故障后原号可恢复。
7. 从合成INITIAL流水开始逐笔重放available/reserved delta，核对每条after及最终余额，不能只凭“最终不为负”判断正确。

### 退款

1. 支付本金100分，两笔不同来源的80分退款同时竞争，只允许一笔占用成功；另一笔整笔回滚。
2. 同退款号8线程重放只预留一次支付和来源额度，退款记录、任务、审计各一份。
3. 同来源换两个退款号竞争，只允许一个未决退款；支付余额预留也随失败事务回滚。
4. 模拟通道受理后响应丢失，退款进入UNKNOWN，支付与来源额度都保持占用；后续查询原退款号，没有盲目再次提交。
5. 并发签名成功通知与重放只生成一次成功流水和Outbox，并且只转换一次额度。
6. 明确失败释放双额度，允许按新退款号执行合法重试；同号绑定变化、来源授权变化、同通知号异内容被拒绝。
7. 恢复过程中的Mock RPC会检查没有持有本地数据库事务。此检查不证明真实网络和模拟通道已经端到端联调。

### 消息

- 业务副作用与Outbox在同一真实本地事务中一起回滚。
- 同一Inbox唯一键并发消费只产生一次合成业务效果；同ID不同正文隔离，原摘要不被覆盖。
- handler先写后异常，业务先回滚，再持久化DEFERRED原文；入口重复不重做，专门resume入口恢复一次。
- 删除最后一个路由绑定后，**实际收到basic.return回调**，Outbox保留READY，不能误记SENT。
- Broker真实确认后，定点注入SENT数据库回写异常，并断言注入分支确已触发；再次发送保持相同消息ID及完全相同正文，消费效果一次。
- 隔离原文已落库但DLQ绑定缺失时不记发送成功；恢复绑定后可以投递原始非法正文。
- 删除退款事件的售后队列绑定、保留订单队列绑定，发布仍然SENT，订单队列收到，售后队列未收到且没有basic.return。这项是**风险复现**，不是自动容错能力验收。

MQ接收用例直接调用生产MessageReceiver及合成handler；未启动MessagingRuntime完整监听容器，因此没有验证真实监听ACK/NACK、断连重投或Broker重启持久性。支付/退款publisher guard在消息传输用例中为无操作替身，其资金一致性由另组测试覆盖。

售后源码另有按原退款号查询的恢复任务，但本轮没有验证“绑定缺失+七服务查询补偿”的组合，不能据此断言售后必然卡死或已经自动恢复。仍需要拓扑核验、告警与恢复演练。

### 缓存与进程恢复

真实Redis验证正/负缓存TTL、双实例热点互斥、提交后代际切换、旧重建写回围栏、租约owner校验，以及命中元数据时仍读取最新库存。Redis故障时元数据loader并发有界；不宣称整个公开查询的数据库并发都在该额度内。

支付JVM在执行中halt(73)，第二个JVM尊重原租约，等其自然到期后按原支付号查询恢复。结果：支付指令1次、查询3次、成功流水1份、退款账户1份、Outbox1份。真实Service/Mapper/MySQL，通道是文件持久化替身。

## 发现与修正

1. 公共ServiceBoundaryTest沿用早期无监控配置的骨架假设，先因缺监控token失败；补充该隔离用例的明确范围。它还错误地用聚合health判断纯存活，Rabbit不可用时503是合理行为，现改为真实liveness组。3项边界测试复验通过，没有关闭生产安全校验。
2. 六个完整应用测试仍未完成适配：用户缺AccountMapper，订单缺OrderMapper，售后缺JdbcTemplate；商品、支付及模拟通道缺相应测试服务凭据。test配置排除了数据库，但测试仍加载真实业务Bean，断言还停留在SKELETON。此次保留失败，不删除测试、不放宽生产认证、不宣称标准测试命令全绿。
3. 新MQ隔离工具运行中修正了本机EPMD实际路径、OTP26 cookie目录、重复home参数、管理API就绪期字段以及Windows启动壳/子进程归属核验。测试ObjectMapper按Boot生产默认使用ISO文本时间，避免裸Jackson数字时间戳违反事件契约。最终7项用例0失败/错误/跳过，所有最终实例监听均为回环。
4. 对先前失败的8轮自建fixture补做归属清理核验；发现的自建MySQL残留均在核对进程映像、命令行目标及@@datadir后停止。修正工具跟踪所有依赖的后代PID，并通过创建时间+映像防止PID复用误停；没有按进程名停止其他服务。

## 原始证据

- `.local/reliability-unit-20261025.log`：43项通过。
- `.local/verification/return-mysql-0saeqn0z/`：库存及商品/退货12项、16份迁移与中文COMMENT检查。
- `.local/reliability-refund-20261025.log` 与 `%TEMP%/tradeflow-fault-mysql-h9bl0my1/`：退款7项、支付围栏2项及6项恢复单测。
- `.local/messaging-fixture-decb5c9717044012bf1e18735cf4a014/`：最终7项MQ测试、回环监听和全部停止证明。
- `.local/verification/entry-redis-fguxft7l/`：真实Redis脚本及缓存JVM测试。
- `.local/verification/entry-mysql-5j9knbea/`：真实登录窗口SQL。
- `.local/payment-crash-5b3e3b56b827428b9ec0a2bc2d1cf916/`：实际JVM中断恢复。
- `.local/reliability-backup-20261025.log` 与 `.local/backup-fixture-ab69a2aafb1a4d0f9588745679b23a00/`：备份工具及合成恢复。
- `.local/reliability-application-20261025.log`：六项启动错误原始证据。
- `.local/reliability-fixture-cleanup-audit.json`：早期独占fixture清理核验。

不要公开fixture受保护目录中的cookie、配置密码及秘密文件；原始配置不属于发布材料。

## 复现与剩余验收

```powershell
# 可独立执行的单元/边界/绑定测试；此命令明确不包含完整应用启动测试。
.\mvnw.cmd -B -ntp '-Dtest=*Test,!*ApplicationTest,!*MysqlTest,!*IntegrationTest,!PaymentCrashRecoveryTest,!CatalogCacheRedisTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
$env:TRADEFLOW_MYSQL_BIN='D:/mysql-8.0.46-winx64/bin'
python scripts/verification/return-mysql.py
python scripts/verification/fault-mysql.py
python scripts/verification/messaging-fixture.py --maven
python scripts/verification/entry-redis.py --catalog-jvm
python scripts/verification/entry-mysql.py
python scripts/verification/payment-crash.py
python scripts/verification/backup-tests.py
python scripts/verification/backup-mysql-fixture.py --mysql-bin D:/mysql-8.0.46-winx64/bin

# 此命令目前失败，专门暴露遗留启动测试的配置与依赖缺口。
.\mvnw.cmd -B -ntp -fae '-Dtest=*ApplicationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Maven必须串行运行，避免共享target互相覆盖。后续验收应先补齐带隔离依赖的真实应用启动测试，再验证七服务与Nacos、监听容器重投、Broker重启、拓扑漂移恢复及实际压测。真实Nacos仍受此前本机Raft通配监听隔离限制，本轮未重启尝试，也没有计入通过。未执行生产部署、生产备份恢复或性能容量验收。
