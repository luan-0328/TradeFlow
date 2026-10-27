# TradeFlow

源码仓库：[luan-0328/TradeFlow](https://github.com/luan-0328/TradeFlow)。公开内容仅包含源码、配置模板和脱敏文档；本机 `.local`、邮件授权码、密钥、数据库、日志和构建产物均不纳入版本控制。

> 工程交付状态：业务P0已通过不等于整个项目已可正式交付。[工程完整性复核](docs/project-engineering-readiness.md)确认CI测试门禁、完整八服务部署配置及七库备份仍有阻断项；监控和生产运行保障还需补齐。Git版本基线在2026-10-27源码首发时建立。

> 2026-10-26 U1–U4及四项P0已完成本地分层验收：[业务升级说明](docs/u1-u4-development.md)、[最新P0验收报告](docs/integration/2026-10-26-p0.md)。新增多商户、交易组/运费、模拟结算及平台争议；早期[U1–U4报告](docs/integration/2026-10-26-u1-u4.md)保留当时43项隔离实测与端到端尚未验收的历史边界。本批未部署。

分布式交易履约与售后平台。当前实现 **B1 用户商品、B2 下单预占、B3 独立模拟支付与关单屏障、B4 发货收货和未发货退款、B5 消息可靠交付、B6 金额仅退款与退货退款、B7 冻结账单双向对账，以及 B8 可观测性与交付工具**，并提供用户端和商家/平台工作台。本轮已补退货逐件库存处置、入口保护、人工待办及恢复验证，见[加固交付记录](docs/project-hardening.md)。本机联调已执行，详见[联调报告](docs/integration/2026-10-24-local.md)；服务器部署及实际验收边界见[部署记录](docs/runbooks/server-demo.md)。完整故障、压测与备份恢复验收仍未完成。

P0四项已完成本地验收：每商家独立成员角色、真实Nacos/RabbitMQ/Redis八服务联调、Chrome完整业务验收、消息拓扑告警及原事件恢复。44项真实MySQL/应用HTTP、38项整套链路、15项浏览器检查通过，详见[P0验收报告](docs/integration/2026-10-26-p0.md)。权威身份与capabilities追加`merchantRoles`，成员API追加`role`；升级需追加用户库V7迁移，保留最后有效本店ADMIN与平台恢复入口。角色规则见[用户权限说明](tradeflow-user/docs/upgrade-u1-u4.md#p0-每商家职级与管理员恢复)。本轮未部署；支付/出款仍为模拟通道，本地Nacos RC隔离验证不代表生产选型。

新注册已改为[邮箱验证码注册](docs/email-registration.md)：QQ SMTP发信、Redis验证码/冷却/错误次数限制；邮箱作为新账号登录名，历史用户名登录兼容。运行用户服务前需追加V8迁移并提供SMTP配置。本轮不部署、不改现有开发数据库。

## 打开与运行

IDEA打开 `D:/cloud/tradeflow`，导入根pom.xml，JDK21。需要本机MySQL8.0.46与Erlang26.2.5.15的bin在PATH、Python3及cryptography（隧道另需paramiko），并先准备可达的Nacos。当前U1–U4源码支持多商家，每商家使用默认逻辑仓库warehouse_main；旧部署仍按其历史单商家版本理解。服务间调用现已统一使用Nacos发现，配置见[运行手册](docs/runbooks/nacos-discovery.md)。

```powershell
cd D:/cloud/tradeflow
.\mvnw.cmd -B -ntp -Dmaven.test.skip=true clean package
.\scripts\Start-Local.ps1
```

启动脚本准备专用MySQL `127.0.0.1:13316`、专用RabbitMQ `127.0.0.1:5678`和拓扑，以及网关入口保护及商品展示缓存专用Redis `127.0.0.1:16379`，单独执行Flyway，然后启动八个JVM。独立管理端口18080–18087，启动器检查readiness；Prometheus指标需受保护监控Bearer，业务808x不再提供Actuator健康。RabbitMQ首次从官方固定release安装到.local/tools，不修改系统Rabbit安装。数据在 `.local/mysql/data`，不复用已有MySQL、文档验证数据或远程业务库。运行账号没有DDL，库存流水/审计没有UPDATE/DELETE。迁移账号只用于短期迁移进程。

需要使用远程Nacos/Sentinel时，显式建立隧道后用 `scripts/Start-Dev.ps1` 代替Start-Local。普通本机启动不主动登录服务器，但同样要求预先准备Nacos。

| 服务 | 入口 | 当前能力 |
| --- | --- | --- |
| 网关 | http://localhost:8080/ | JWT验签、已实现路由、伪造身份头清理 |
| 用户 | http://localhost:8081/swagger-ui/index.html | 注册、登录、本人资料、平台授权与商家归属 |
| 商品库存 | http://localhost:8082/swagger-ui/index.html | 商品/SKU、内部报价、库存调整和流水 |
| 订单 | http://localhost:8083/swagger-ui/index.html | 幂等下单、快照、库存Saga、支付结果、取消与超时恢复 |
| 支付 | http://localhost:8084/swagger-ui/index.html | 支付退款、双额度、签名回调、资金流水、RabbitMQ Outbox和双向对账 |
| 售后 | http://localhost:8085/swagger-ui/index.html | 未发货整单、部分金额仅退款、退货物流验收、证据与退款恢复 |
| 模拟通道 | http://localhost:8086/swagger-ui/index.html | 独立支付退款事实、模拟通道本金预算、关闭墓碑及异常场景 |
| 模拟结算 | http://localhost:8087/swagger-ui/index.html | 规则快照、结算批次、退款冲正、负债抵扣与核对 |

外部业务统一走网关，内部接口不公开路由；用户、商品、订单、支付和售后服务各自验签，并复核当前权限。各服务 `/system/info` 显示实际能力。

首次平台账号 `platform_admin`，随机初始密码在受保护的 `.local/dev-env.json` 的TRADEFLOW_BOOTSTRAP_PASSWORD字段。JWT私钥、数据库账号及服务凭据只在.local保存，不提交仓库。注册仅获得USER；商家权限来自本店有效成员职级ADMIN/OPERATOR，可由平台专用入口或本店ADMIN维护，入驻审核通过授申请人本店ADMIN。旧全局商家角色不再授予任何店铺权限。

```powershell
# 停止八个Java服务，保留MySQL/RabbitMQ与数据。
.\scripts\Stop-Local.ps1
# 修改代码后构建重启，跳过测试。
.\scripts\Start-Local.ps1 -Build
# Java服务停止后优雅关闭专用数据库，保留数据。
python scripts/Prepare-Dev.py stop
```

IDEA单独调试时，先准备数据库，再将.local/dev-env.json中必要环境变量导入对应Run Configuration，激活dev；不能提交含密钥的IDEA配置。详情见B1说明。

## 资料与结构

首次理解项目从[代码阅读导读](docs/project-learning-guide.md)开始；当前验收以[P0报告](docs/integration/2026-10-26-p0.md)为准。

- [B2下单说明](docs/b2-development.md)、[B3支付说明](docs/b3-development.md)、[B4履约退款说明](docs/b4-development.md)、[B5消息说明](docs/b5-development.md)、[B6售后说明](docs/b6-development.md)、[B7对账说明](docs/b7-development.md)、[B8监控交付说明](docs/b8-development.md)。
- [B1使用说明](docs/b1-development.md)：实际接口、商家授权、商品与入库请求。
- [架构与边界](docs/architecture.md)、[构建及运行记录](docs/verification.md)。
- [目标OpenAPI](docs/contracts/openapi.json)：操作标记x-implementation-stage；运行时Swagger仅列实际接口。
- [远程Nacos/Sentinel](deploy/remote/README.md)。

common、contracts、common-web是共享代码，不单独启动。Gateway为WebFlux，不能依赖Servlet的common-web。业务服务按api/application/domain/infrastructure分层，Mapper仅访问所属库。

每服务一个application.yml，包含公共和dev/test/prod段。dev/prod统一启用Nacos发现；Feign只写服务名、网关使用lb路由，其他HTTP及模拟回调同样按服务名选实例；不再支持下游固定地址。test用于隔离测试，真实数据库与应用HTTP需由专属fixture显式提供依赖；prod为配置模板，不是已部署的生产环境。

原完整73表模型仍为目标；当前源码迁移包含117张业务表，但包含分阶段新增恢复/权限表，数量不代表目标模型全部实现。当前七个逻辑库的源码业务表定义为用户12、商品18、订单30、支付20、模拟通道12、售后17、结算8，另由Flyway记录迁移历史。完整基线归档在docs/database-target；售后已启用独立数据库。已执行迁移不可改写，后续追加版本。

版本：Java21、Boot3.5.16、Cloud2025.0.3、Alibaba2025.0.0.0、MyBatis-Plus3.5.17、MySQL8.0.46、Springdoc2.8.17、Maven Wrapper3.9.11。

B9已实现[用户端与管理控制台](docs/b9-development.md)，本机入口见下文；本机浏览器交易与金额仅退款验收见联调报告。

B8已实现受控指标、结构化日志、初始告警与看板、独立七服务Compose和备份工具；[交付状态](docs/delivery-status.md)明确尚未执行的验收与已知限制。

本机Rabbit管理页：http://localhost:15678/，独立管理员凭据位于受保护.local/rabbitmq-admin.json；业务MQ账号按服务分离，迁移/管理员环境变量不传给业务JVM。停止专用broker：`python scripts/Prepare-RabbitMQ.py stop`（先停业务）。

本机监控：[Grafana看板](http://localhost:13000/d/tradeflow/) · [Prometheus](http://localhost:19090/)。准备/启动：`python scripts/Prepare-Observability.py prepare`、`python scripts/Prepare-Observability.py start`；随机Grafana管理员凭据仅在受保护`.local/observability/grafana-admin.json`。观察入口不替代业务测试，也无外部通知接收器。

完整独立容器配置为[compose.application.yaml](deploy/compose.application.yaml)，说明见[七服务部署](deploy/README.application.md)；早期compose.yaml仅基础设施模板。服务器演示使用额外的[低内存覆盖配置](deploy/compose.server-demo.yaml)，操作与验收记录见[部署手册](docs/runbooks/server-demo.md)，备份恢复尚未演练。

## 前端运行（B9）

在项目根目录使用Node 24（最低22.12）执行：

```powershell
npm.cmd ci
npm.cmd run build
.\scripts\Start-Web.ps1
# 停止本项目记录的两个前端Node进程
.\scripts\Stop-Web.ps1
```

用户端：http://127.0.0.1:5173/ ，商家与平台控制台：http://127.0.0.1:5174/ 。开发服务器仅监听本机，/api/v1经同源代理访问Gateway8080。前端进程与Java分别管理；后端用Start-Local.ps1启动。不要把.local中的内部密钥复制到前端。首次平台账号获取方式见上文；商家需有效的本店成员职级；同一人可在A店为ADMIN、B店为OPERATOR，维护权限不能跨店继承。前端不自带假商品或订单。

生产构建产物分别在web-user/dist、web-admin/dist。Vite preview不代理API；容器部署由Nginx提供两套静态入口、SPA回退及/api/v1网关路由。服务器演示入口为 http://127.0.0.1:18088/ 和 http://127.0.0.1:18089/ ，需保持SSH转发；它们与本机Vite开发环境独立。

B10补齐[履约资料与下单恢复](docs/b10-development.md)：商家按需查看收货资料；购物袋可凭原下单键恢复已受理订单，或安全阻止尚未受理请求。

B11增加[支付及售后申请原键恢复](docs/b11-development.md)：订单详情可找回原支付/售后，售后申请页支持原申请查询；只读取已受理记录，查不到继续保留原键。

B12增加[前端独立容器交付](docs/b12-development.md)，部署后前端端口18088/18089；当前机器无Docker，实际运行入口仍为Vite5173/5174。

B13增加[统一构建与运行交付包](docs/b13-development.md)：Windows执行`.\scripts\Build-Release.ps1`，项目已运行时可显式加`-Restart`；构建成功后白名单ZIP及SHA-256输出至.local/releases。该流程跳过测试，不自动部署，CI配置同步包含两套前端但本轮未推送触发。

B14完成[交付前静态修正](docs/b14-development.md)：消息重试记录编号、弹窗迟到响应、预览代际和购物袋账号切换隔离。该历史阶段尚未执行浏览器或业务并发验收，后续结果见本轮P0报告。

已执行[本机联调测试](docs/integration/2026-10-24-local.md)：覆盖购买履约、20元补偿后退货80元、退款竞争、模拟通道超时/重复回调、消息原ID重放、库存竞争与对账，并修复售后代理字段空指针和页面终态轮询问题。部署未执行，B13/B14旧交付ZIP未包含本轮修复。

商品展示缓存、缓存三灾防护及Mapper/XML约定见[缓存与Mapper说明](docs/catalog-cache-and-mapper.md)。

历史[2026-10-25可靠性实测报告](docs/integration/2026-10-25-reliability.md)记录当时库存、退款、消息及缓存隔离验证通过，以及六项遗留启动失败和MQ部分绑定缺失风险。启动用例、原事件补投及后续八服务/浏览器验收结果见[2026-10-26 P0报告](docs/integration/2026-10-26-p0.md)，不沿用历史失败作为当前状态。
