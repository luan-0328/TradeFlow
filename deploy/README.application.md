# 独立业务与前端容器交付

`compose.application.yaml` 是全新独立部署入口，不与旧 `compose.yaml` 合并。使用dev独立依赖模式：MySQL、RabbitMQ、七个Java应用及两个前端静态服务，当前源码必须连接已准备的Nacos，网关入口保护和商品展示缓存使用Redis，应用Compose已提供独立Redis及就绪依赖，Sentinel仍非此开发拓扑必需。配置准备前先设置容器可达的NACOS_SERVER_ADDR与注册中心客户端凭据，namespace/group保持一致，见[统一发现手册](../docs/runbooks/nacos-discovery.md)。该模式用于独立本机演示，不声称完整prod配置或高可用。Windows开发机无Docker；2026-10-24已在独立服务器构建镜像并启动全部容器，但约1.7GiB内存下交易接口持续超时，完整远程验收未通过。实际限制和操作见[部署记录](../docs/runbooks/server-demo.md)，备份恢复尚未验证。

## 准备与启动

宿主需要Java21、Maven Wrapper依赖、Python及cryptography、Docker Engine和支持service_completed_successfully的Compose V2。先在仓库根目录：

```powershell
./mvnw.cmd -B -ntp package -Dmaven.test.skip=true
python scripts/Prepare-Deployment.py
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml up -d --build
```

Linux将首条换为 `./mvnw -B -ntp package -Dmaven.test.skip=true`。准备器只写受保护 `.local/deployment`，首次独立生成数据库、MQ、用途token、监控token和3072位RSA JWT秘密，不读取开发环境数据；重复执行保留现有秘密。JWT不成对或已有状态丢失JWT时拒绝静默换钥。不要运行 `docker compose config` 后将展开的秘密内容粘贴到日志/工单。Docker客户端/daemon管理员仍能读取容器环境。

准备器增量保存部署UUID并生成只读deployment-id；MySQL只在全新卷初始化时将同一身份写入持久卷。备份器强制本机端点和固定project，并校验挂载源、容器与卷归属，旧卷缺失标记时拒绝自动认领。

顺序为：MySQL全新卷bootstrap创建六个逻辑库及每库独立迁移/运行账号 → 六个一次性Flyway容器各迁本库 → runtime-grants统一逐表/逐列授权 → 业务应用。任一迁移失败都会阻止授权和业务启动。运行账号不含DDL、迁移秘密、root或Rabbit管理员秘密；授权快照来自Prepare-Dev，并保留消息/账本/证据列保护。Rabbit镜像首次启动加载全拓扑、独立账户和routing-key权限，八队列健康后业务publisher才启动。应用和Gateway采用HTTP readiness，不能仅将进程已创建当作就绪。

七应用端口8080–8086和管理端口18080–18086只在容器网络内部；Gateway `127.0.0.1:8080` 和B12前端 `127.0.0.1:18088/18089` 发布到宿主。容器命令显式覆盖dev段server.address及management.server.address，模拟器通过SPRING_DATASOURCE_URL覆盖原dev硬编码回环地址。管理指标要求独立Bearer，网关不得转发管理路径。Rabbit只读monitoring账号另行随机生成并保留在deployment自身credentials，拓扑授予monitoring标签且configure/write/read全部为空；专用rabbit-monitor.env只供后续基础设施采集使用，不注入业务应用。当前模板没有连接另一套宿主Prometheus；如需采集，应单独配置受保护采集凭据和同一容器网络，不能公开发布管理端口。

用户服务只挂载自己私钥；其他应用只挂公钥。售后证据存于 `.local/deployment/evidence`，数据库与Rabbit分别使用独立命名持久卷。容器JRE固定Java21 release；Dockerfile使用root读取宿主600挂载秘密，尚未实现非root secrets交付。Rabbit定义/config在Linux仅文件644以供镜像专用UID读取，其父目录保持700；Windows使用当前用户ACL。发布到多用户服务器前需落实daemon权限与非root秘密挂载策略。

首次随机platform_admin引导密码只存 `.local/deployment/credentials.json`，不回显；请通过受保护方式取用并按正式账号流程修改。没有固定演示密码、演示业务数据或自动业务请求。后续关闭引导时在user.env将TRADEFLOW_BOOTSTRAP_ENABLED改false（再次运行准备器会按模板恢复true，启动器仅在无平台账号时创建，不用于密码轮换）。

## 维护与排障

```powershell
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml ps -a
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml logs --tail=100 migrate-order runtime-grants
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml stop
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml start
```

迁移升级先停写/备份，再按受审查版本重建一次性迁移与授权容器并确认成功，然后重建应用；不要依赖旧已完成job自动重新迁移。MySQL首次初始化失败时保留卷和日志，人工查明部分初始化，不重复建库、不自动baseline、更不能删除旧卷重试。宿主8080冲突只调整本模板loopback发布端口；两个前端端口18088/18089也仅loopback发布，其他端口未发布。Windows Docker Desktop需允许D盘文件共享，Linux需检查目录权限和容器挂载，避免放宽整个.local权限。网络拉取镜像失败不应改为latest。

dev已要求Nacos发现；若改为prod，仍需落实Nacos持久化、namespace/group和Sentinel可达地址，不应只切SPRING_PROFILES_ACTIVE。CI默认只package并明确 `-Dmaven.test.skip=true`；构建成功不代表测试通过。备份与隔离恢复见 [手册](../docs/runbooks/backup-restore.md)，未提供自动删除卷或既有数据库恢复命令。

## 前端入口（B12）

完整up命令现在包含web-user和web-admin，分别在 http://127.0.0.1:18088/ 和 http://127.0.0.1:18089/ 。前端由固定摘要的Node构建、Nginx非root只读运行，/api/v1同源代理到gateway，不携带部署秘密。详情、增量更新与未验证边界见[B12说明](../docs/b12-development.md)。本机原生Vite5173/5174与服务器Nginx18088/18089是两套独立入口，后者需SSH转发；可用性限制见服务器部署记录。
