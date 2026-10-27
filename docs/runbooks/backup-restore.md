# 独立容器部署备份与隔离恢复

2026-10-25已完成离线拒绝条件回归和原生MySQL 8.0.46六个合成fixture库的备份/空实例恢复，见[验证记录](../integration/2026-10-25-backup-restore.md)。尚未执行真实Compose六库业务数据恢复，不能据此声称生产恢复验收通过。备份器只适用于 `compose.application.yaml` 的独立 `tradeflow-b8` 项目，不读取开发实例。备份含数据库个人资料、JWT私钥、数据库与内部服务凭据，应留在受保护目录，并使用组织批准的加密存储保存离线副本；校验和不能代替加密或证明来源真实性。

## 停写与备份

先公告维护窗口，禁止外部写入、迁移、后台管理任务和手工SQL。停止七个业务容器（包括通道模拟器及全部后台恢复工作线程），保留MySQL/Rabbit运行：

```powershell
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml stop gateway user product order payment aftersale payment-simulator
python scripts/Backup-Deployment.py --confirm-quiescent
```

备份工具固定 `-p tradeflow-b8` 和本仓库Compose路径，不接受DOCKER_HOST/DOCKER_CONTEXT/TLS环境覆盖；先inspect当前context，只允许本机Unix socket或Windows本机npipe，后续调用固定该端点。它核对MySQL容器项目/service标签、只读deployment-id挂载源等于当前.local/deployment、credentials和挂载UUID及MySQL持久卷首次初始化UUID完全一致；备份过程中反复核对容器ID与卷。manifest记录实际context/endpoint、部署UUID、容器ID/镜像ID和卷身份。无标记旧卷或身份不符拒绝备份，不能通过复制标记自动认领。

确认停写必须覆盖整个备份窗口。工具同时要求Compose没有运行中的业务容器、六个app数据库账号连接数为零，并在dump前后及归档后重复核对；不会主动断开连接。操作员还必须确认没有迁移/管理员写入与其他项目连接。单次 `mysqldump --single-transaction --databases` 同时覆盖六个InnoDB业务库；窗口内不得DDL。逻辑备份不复制MySQL系统库或服务器账号，恢复账号应由独立部署流程准备。

输出为 `.local/backups/<UTC时间>/`：`databases.sql`、`protected-config-evidence.zip`、`evidence-index.json`、`manifest.json`。ZIP包含部署凭据、JWT、Rabbit拓扑和完整证据目录；v2 manifest记录实际归档字节及dump、证据索引的SHA256。证据索引在同一停写窗口从售后库导出全部UPLOADED/BOUND文件键、长度、散列；离线校验要求每条有效引用都在归档中且长度/散列相同，允许保留未引用或墓碑文件。生成最终目录前也执行相同校验。中途失败保留 `.incomplete`，禁止当成功备份使用。工具不备份Rabbit消息；停写前必须按正式维护流程确认队列已安全处理，或者另行安排broker级一致性备份。Outbox持久证据不等同于broker全量恢复保障。

## 可执行的只读校验与隔离计划

Python需3.12或更高版本。以下校验不连接Docker/MySQL/Nacos/MQ、不解压、不导入、不读取当前部署秘密，仅读取指定备份自身：

```powershell
python scripts/verification/backup-verify.py <备份目录>
python scripts/verification/backup-tests.py
```

校验拒绝`.incomplete`、旧格式、缺失/损坏文件、manifest成员集合不一致、大小写冲突、绝对路径/盘符/备用数据流/路径穿越、ZIP链接或非普通文件、宿主路径符号链接/junction、部署标记/credentials UUID不一致及有效证据缺失/字节不符。不会输出秘密。manifest须从可信离线来源取得；攻击者同时修改数据和manifest不在SHA256防护范围内。检查保证配套秘密字节和部署身份一致，不验证JWT公私钥数学配对，也不解析SQL证明它完整包含六库；证据索引可信度依赖原备份停写窗口，恢复后还要查真实数据库重新关联。

可再提供人工审查的JSON计划；路径必须是不存在的绝对新路径，端点必须是带端口的明确回环地址，MySQL拒绝3306/13316，禁止复用原部署身份/项目/registry/namespace/MQ：

```json
{
  "sourceDeploymentId": "从manifest抄录原部署UUID",
  "destinationDeploymentId": "新生成的独立UUID",
  "project": "tradeflow-restore-drill",
  "mysqlDataDirectory": "D:/cloud/tradeflow/.local/restore-drill-new/mysql",
  "evidenceDirectory": "D:/cloud/tradeflow/.local/restore-drill-new/evidence",
  "mysqlEndpoint": "127.0.0.1:23471",
  "registryEndpoint": "127.0.0.1:23472",
  "registryNamespace": "独立恢复演练namespace",
  "brokerEndpoint": "127.0.0.1:23473",
  "networkEgressDenied": true,
  "applicationsDisabled": true,
  "bootstrapDisabled": true,
  "freshVolumesOnly": true
}
```

```powershell
python scripts/verification/backup-verify.py <备份目录> --plan <恢复计划.json>
```

成功输出中的`static-preconditions-passed`仅表示计划通过静态拒绝规则。此工具不探测端口归属、不确认实例为空、不创建目录/网络/卷，也不实际实施阻断外联。现场须用实例datadir、容器/卷身份及空库查询再次证明目标全新归属；必须检查Compose最终配置和网络策略，不能凭计划声明直接导入。目录从检查到人工使用期间不得被替换。

## 仅在全新隔离实例恢复（人工步骤）

1. 保留原实例/卷和原备份只读，不对其导入或删除。记录备份时间、版本、镜像、停写人员与完整性校验结果。先运行上述只读校验，确认可信manifest来源，再在受保护的全新目录展开ZIP；逐文件验证校验和，禁止路径越界和符号链接。旧备份没有证据索引须另行审计，不能伪造v2声明。
2. 使用独立Docker project name、全新MySQL/Rabbit卷和隔离网络。不要启动业务容器，不发布端口到公网。恢复时必须单独准备恢复Compose：仅起固定MySQL 8.0.46，使用新的管理员秘密，不挂载bootstrap.sql，空实例中不得预先创建六个业务库。不要运行普通完整Compose启动序列，否则bootstrap/迁移会先建库，与dump的CREATE DATABASE冲突。
3. 人工确认实例确实为空且属于恢复演练，再将 `databases.sql` 标准输入导入该空实例。禁止将此文件导入任何已有业务库。导入后逐库核验Flyway历史、约束和业务不变量；真实业务库的这些步骤仍为未来验收项。
4. 根据受保护的原credentials重新生成独立运行/迁移账号（六库各自归属），授予迁移账号本库DDL；不要再次执行bootstrap中的CREATE DATABASE。已恢复Flyway历史必须校验，不自动baseline。按同版本迁移入口执行validate/migrate后，再人工执行同版runtime-grants.sql逐表逐列授权。
5. 恢复实例需按隔离恢复变更记录确认新的容器/卷归属；只有人工审计通过后才能为全新恢复卷安装对应部署标记，备份器不会自动为旧卷补标记。恢复证据目录与原JWT签名身份和内部HMAC秘密；确认数据库证据引用与文件哈希一致、容器可以读写证据。Rabbit恢复全拓扑后才允许publisher启动；不要将仍运行的原broker和恢复实例互联。全新broker缺失原队列消息的边界必须单独评估与验收。
6. Nacos改造后，不得直接使用归档的七个服务env启动：其中可能指向原registry、原namespace和原MQ。先阻断恢复网络到原MySQL/MQ/Nacos及公网的外联；准备独立Nacos实例、独立非空namespace及group，显式覆盖全部服务的NACOS_SERVER_ADDR/NAMESPACE/GROUP和广播IP，保持Nacos config禁用。恢复实例绝不能向原namespace注册或从原注册中心发现服务；仅换端口不能形成隔离。MQ也必须是独立broker/vhost，检查所有启动环境、路由及发现结果后，才以隔离地址启动七服务，检查health/readiness、日志和指标。真正恢复验收还需业务核验、重复任务安全、约束与消息恢复演练。记录结果、RPO/RTO实际数据，不预填成功。

没有生产自动restore命令、DROP库、删卷或强制终止流程。日常重启使用 `docker compose ... restart`；保留持久卷。可选的`backup-mysql-fixture.py`只创建UUID专属全新目录及两个原生MySQL实例，连接前核对datadir，只关闭自己创建的进程，保留fixture数据/报告；它不使用现有实例和真实秘密，不能替代Docker部署与业务恢复验收。
