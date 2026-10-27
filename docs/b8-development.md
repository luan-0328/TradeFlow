# B8 可观测性与交付整理

本批实现独立监控端点、标准与业务指标、结构化日志、Prometheus规则、Grafana看板、独立容器交付配置及安全备份工具。用户要求不运行测试，因此**完整B8验收仍未完成**：没有全端烟测、故障矩阵、并发/压测、Docker部署或备份恢复演练，也没有用户端/商家后台前端应用交付。

## 本机入口

| 组件 | 地址 | 身份与范围 |
| --- | --- | --- |
| 业务网关 | http://localhost:8080/ | 原JWT业务权限 |
| Prometheus | http://localhost:19090/ | 仅回环，目标/规则/只读指标查询 |
| Grafana | http://localhost:13000/d/tradeflow/ | 随机管理员密码，受保护本机监控资料 |
| 队列只读exporter | http://127.0.0.1:15698/metrics | 独立监控Bearer，仅项目8队列元数据 |
| 应用管理端口 | 18080–18086 | 依次对应Gateway/user/product/order/payment/aftersale/simulator |

管理默认只监听127.0.0.1，不经网关路由。`/actuator/prometheus`仅GET及独立`TRADEFLOW_MONITORING_TOKEN`有效；业务JWT和服务凭据不能代替。令牌在受保护`.local/dev-env.json`，Prometheus使用独立文件读取，不写入仓库、命令行或日志。Grafana管理员凭据在受保护`.local/observability`中，按该目录的凭据文件取用，不提供固定默认密码。

```powershell
# 应用先构建并准备本机DB/Rabbit，再启动；只package，跳过测试。
.\scripts\Start-Local.ps1 -Build
# 首次下载固定官方安装包并校验SHA256，生成受保护监控配置。
python scripts/Prepare-Observability.py prepare
python scripts/Prepare-Observability.py start
python scripts/Prepare-Observability.py status
# 仅关闭本项目可确认归属的监控进程，保留数据。
python scripts/Prepare-Observability.py stop
```

已有Java运行时重构建前先执行`Stop-Local.ps1`，避免Windows占用JAR。普通应用停止保留MySQL、RabbitMQ、监控进程及数据；监控停止不停止业务服务。安装器拒绝接管已有端口，按PID、内核创建时间、可执行文件及实际回环监听归属操作。下载只从官方固定release地址，必须SHA256一致；版本和摘要记录见[RELEASES](../deploy/prometheus/RELEASES.md)。本批固定Prometheus3.15.1、Grafana13.2.4，安装到项目`.local/tools`，不替换系统安装。

## 存活、就绪与HTTP指标

健康检查已从业务808x端口移到对应1808x管理端口：

- `/actuator/health/liveness`只含应用存活状态，MQ暂时不可用不应触发进程反复重启。
- `/actuator/health/readiness`包含应用就绪和本模块DB；order/payment/aftersale另包含Rabbit。Gateway只检查自身就绪。它不代表完整交易、所有下游或业务正确性验收。
- `Start-Local.ps1`轮询管理端口readiness，Compose也使用对应地址；健康详情不公开。

Micrometer暴露标准JVM/GC、进程、Hikari连接池和HTTP请求计数/错误/直方图。HTTP直方图桶包含50ms/100ms/300ms/1s/3s，可按实际采集计算P95；无业务流量时没有可证明的P95，不补造数据。HTTP URI标签有100种硬上限，业务编号不作标签。

## 业务指标语义

所有应用公共标签`application`固定为模块名。业务采样只查询所属库，不跨库、写数据、修复余额或参与资金决策。后台使用独立单线程，每30秒采集，单SQL超时2秒，抓取HTTP只读内存快照，不执行SQL，不占用业务恢复调度线程。

| 指标 | 含义 |
| --- | --- |
| tradeflow_business_backlog{kind} | 固定类别未决数量，人工待办以_manual分开 |
| tradeflow_business_oldest_age_seconds{kind} | 当前未决记录最老创建时长，空集合0 |
| tradeflow_business_due_lag_seconds{kind} | READY计划重试/到期点或RUNNING租约截止的最老逾期，未逾期0 |
| tradeflow_metrics_collection_success{collector="business"} | 六个Servlet模块完整采样是否成功；Gateway无业务SQL采样器 |
| tradeflow_metrics_last_success_unixtime_seconds | 最近完整成功采样时间 |
| tradeflow_metrics_collection_errors_total | 采样失败次数，不含异常正文或业务参数 |
| tradeflow_database_row_lock_waits{scope="instance"} | MySQL实例当前InnoDB行锁等待，来自只读SHOW GLOBAL STATUS |
| tradeflow_rabbitmq_queue_messages/…_ready/…_unacked/…_consumers | Rabbit实际队列消息、就绪、未确认和消费者元数据 |
| tradeflow_rabbitmq_collection_success/…_last_success_unixtime_seconds | 队列采集成功与陈旧时间 |

采样器任何查询失败，整个业务快照变为无效，业务值为NaN，保留最近成功时间且success=0；不会把数据库故障显示成“积压已清空”。看板不把缺失/NaN填0，告警另行检查采集失败、陈旧及每个预期模块指标缺失。

kind覆盖：Outbox自动/人工、延期、消息隔离业务处置；订单创建/退款回补/退货入库及人工、库存待同步；支付UNKNOWN、退款UNKNOWN/执行占用及资金冻结、对账run/action/差异/完整性冲突；售后恢复；商品预占；通道支付/退款推进和回调恢复。确切白名单见`BusinessMetricCatalog`，无业务ID、用户号、原始URL或错误原文标签。

UNKNOWN和退款占用时长采用“当前处于该状态的原业务创建时长”，不是精确进入状态的时间；不能因每次恢复更新updated_at就重置告警。MySQL行锁值是实例级，六库共享一个实例时用`max`聚合，不求和。Hikari与行锁是运行信号，不是压测瓶颈结论。

Rabbit exporter使用独立只读管理账户和受保护凭据文件，AMQP configure/write/read均为空，不使用broker管理员账户。只读取`/tradeflow`下4个业务队列和4个DLQ，关闭代理与重定向，失败显示NaN和采集失败；不读消息正文、不确认/重放消息。数据库消息隔离记录与Rabbit DLQ实际积压分别观察，不能把一项当作另一项。

## 告警与看板

Prometheus规则为初始演示阈值，未经压测，不代表生产SLO：Outbox最老未发送>60秒、恢复逾期>300秒、UNKNOWN创建存续>300秒、完整性冲突或支付/退款人工冻结>0、DLQ>0；另含目标缺失、采集失败与90秒陈旧。没有配置外部通知接收器，规则状态在本机Prometheus查看，不会向他人发送通知。

Grafana已提供固定UID `tradeflow`，数据源UID `tradeflow-prometheus`。看板覆盖HTTP请求率/错误/P95、JVM堆/GC、Hikari、行锁等待、业务积压/最老时长/逾期、退款占用、对账差异、队列/DLQ和采集健康。规则和看板不证明故障场景已触发或恢复；只读目标UP与规则语法检查属于配置/启动确认。

## 日志与关联

控制台使用Logstash JSON格式，包含service、时间、级别和安全requestId/traceId。同步Feign、JDK HTTP及商品RestClient传播安全关联头，继续独立校验原服务身份，不传递任意外部身份头。HTTP日志只记方法、状态和耗时，不打印正文、地址、Authorization或服务token。

支付/退款事实、售后任务进度、对账任务终态、Outbox追加与消息实际应用在本地事务提交后输出有限业务字段。回滚不输出“已提交”，日志运行异常不会把已提交资金转换为失败。字段白名单包含orderNo/paymentNo/refundNo/aftersaleNo/taskId/eventId与状态；异步以原事件trace及稳定业务号关联。当前不是W3C分布式Tracing/Span或独立Trace存储产品，数据库审计、原事实和任务记录仍为权威证据。

## 独立容器交付与备份

[独立部署说明](../deploy/README.application.md)提供另一套独立生成的凭据、JWT、MySQL/Rabbit和七个应用：bootstrap→六个一次性迁移→逐表逐列运行授权→应用readiness。迁移入口支持MYSQL_HOST，当前本机默认不变；业务JVM无迁移/root秘密。只有Gateway loopback8080发布，容器管理端点仅内部网络；此模板未连接本机监控，不可把宿主127.0.0.1抓取配置直接挂进容器。

当前没有Docker可执行文件，未构建镜像/启动容器；应用容器仍以root读取受保护挂载，完整prod模式、非root秘密挂载与镜像资源需求均未验收。早期`deploy/compose.yaml`仅基础设施历史模板，不是完整应用入口。

[备份与隔离恢复手册](runbooks/backup-restore.md)及`Backup-Deployment.py`只针对独立容器部署，要求显式停写、七应用停止与零app数据库连接；固定本机Docker端点、项目、容器、目录和部署/卷身份，避免混合备份。单次六库一致性dump加配置/证据归档和SHA256，不自动恢复、删库或删卷。未执行备份/恢复演练，无RPO/RTO成绩；Rabbit消息的恢复边界单独记录。

CI默认`package -Dmaven.test.skip=true`，跳过测试编译与执行。原`Smoke.ps1`与旧骨架测试是历史入口，本批未适配或执行，不能作为当前系统验收。真实验收及后续工作见[交付状态](delivery-status.md)。
