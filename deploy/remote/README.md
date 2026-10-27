# 远程开发环境

服务器 `YOUR_SERVER_HOST`，部署目录 `/opt/tradeflow-infra`。仅新增 `tradeflow-nacos` 和 `tradeflow-sentinel`，保留已有项目。Nacos 单机持久化到 Docker 卷，Sentinel 运行官方 1.8.9 Dashboard JAR；这是开发环境，不是生产高可用部署。

## 本机入口

| 组件 | 本机地址 | 用途 |
| --- | --- | --- |
| Nacos 控制台 | http://localhost:18880/ | 注册实例、配置和规则 |
| Nacos 客户端 API | 127.0.0.1:18848 | HTTP API |
| Nacos 客户端 gRPC | 127.0.0.1:19848 | SDK 长连接，API 端口 +1000 |
| Sentinel 控制台 | http://localhost:18871/ | 实例、资源、流控和熔断规则 |

账号在本机 `.local/remote-env.json` 的 NACOS_USERNAME/PASSWORD 与 SENTINEL_USERNAME/PASSWORD 字段，文件仅当前用户可读，忽略 Git。服务器组件密钥在 `/opt/tradeflow-infra/.env`，权限 600。SSH 登录密码不保存在工程中。

## 启动与停止

在项目根目录执行：

```powershell
# 隧道已运行时无需重复启动；断开后才显式重建，交互输入 SSH 密码。
python scripts/Remote-Tunnel.py start --host YOUR_SERVER_HOST
# 加载组件账号，启用 Nacos/Sentinel；网关采用 lb:// 服务发现。
.\scripts\Start-Dev.ps1
# 修改代码后先停止再构建启动。
.\scripts\Stop-Local.ps1
.\scripts\Start-Dev.ps1 -Build
```

若在IDEA调试，先按[B1说明](../../docs/b1-development.md)准备本机数据库和.local/dev-env.json，再保持隧道并导入remote-env.json配置，激活dev。所有业务及网关路由现已统一通过Nacos发现，不再设置UPSTREAM或SERVICE_URL。namespace/group必须一致，所有实例注册地址须对调用方可达；配置中心可选，详见[统一发现手册](../../docs/runbooks/nacos-discovery.md)。密钥不写入YAML或提交IDEA配置。

停止隧道使用 `python scripts/Remote-Tunnel.py stop`，不会停止服务器容器。本机休眠、断网、重启后可能需显式重建隧道。状态与日志位于 `.local/`。脚本依赖 Python 3 与 paramiko，校验已有 SSH known_hosts，不接受未知主机。

服务器容器随 Docker 重启自动启动。维护时在 `/opt/tradeflow-infra` 使用 `docker compose ps` 或 `docker compose up -d`；不要删除持久化卷代替重启。

## Sentinel 如何接通

控制台在服务器，本机客户端须通过反向隧道供控制台访问。服务器回环端口 28719–28724 分别转发到本机 gateway、user、product、order、payment、aftersale 的 Sentinel 通信端口。注册 IP 为 127.0.0.1；这些设置仅适用于当前同机开发拓扑，不可直接用于多机生产。

Nacos 的 TRADEFLOW 组持久化六个服务的同名 YAML、`-flow.json`、`-degrade.json`，共18项。示例网关 info 路由限流20次/秒，业务 `/system/info` 限流50次/秒；异常比例熔断阈值50%，最少5次请求、10秒窗口、熔断10秒。阈值只是开发初值，没有压测依据。

**持久修改规则应编辑 Nacos 对应 JSON。** 原版 Sentinel 控制台改规则只推送客户端内存，不自动回写 Nacos，重启或 Nacos 更新后会被持久配置覆盖。无需额外安装独立“限流服务”；Sentinel 客户端在各 JVM 执行规则，Dashboard 用于管理观察。

当前规则覆盖模块信息资源，不代表订单、支付、退款已有保护。业务 Feign 尚未实现；后续按真实 RPC 补充资源与异常统计。退款结果不确定时仍须查通道事实，不能用熔断兜底返回成功。

独立模拟通道不注册Nacos，也不接Sentinel。远程部署仅包括这两个组件；B1后续增加了本机独立MySQL，未在远程部署数据库、Redis或RabbitMQ。仍不能宣称交易可靠性已完成，未执行自动化、压测或故障测试。
