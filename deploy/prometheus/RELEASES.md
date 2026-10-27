# 固定本机官方发行包

- Prometheus 3.15.1 windows-amd64.zip：`ba25b5e590520cb9fb028edef99bdc8523244d2d489b8c629e0d453d94fb030b`，来源 https://api.github.com/repos/prometheus/prometheus/releases/tags/v3.15.1 对应asset digest。
- Grafana OSS 13.2.4 windows-amd64.zip：`0eaddf3dbafa636e9da92e15fe29062958061dc30a73aa1761a7a36d7d019823`，来源 https://dl.grafana.com/oss/release/grafana-13.2.4.windows-amd64.zip.sha256。

执行 `python scripts/Prepare-Observability.py prepare` 下载校验、增量配置、仅导入专用Rabbit只读监控账户；随后 `start` / `status` / `stop`。安装到 `.local/tools`，数据与受保护凭据在 `.local/observability`。Grafana登录信息在受保护grafana-admin.json；不在终端输出。promtool只检查配置/规则语法，不执行规则测试。Prometheus19090、Grafana13000、exporter15698均仅127.0.0.1。无外部告警通知。查看 `/d/tradeflow`。

Exporter仅使用专用监控用户（management标签）GET八队列管理元数据，空AMQP资源权限禁止发布/消费/声明。management标签配合仅/tradeflow权限限制管理可见范围；不授予可跨vhost查看节点/连接的monitoring标签。采集器进一步硬编码唯一vhost与八队列。每30秒全批采样，任何错误该批NaN且success=0，最后成功时间保留。Prometheus内置up与独立成功/陈旧告警覆盖不可达与采样失败。

2026-10-24读取GitHub官方latest元数据后固定：Prometheus v3.15.1 published_at 2026-10-21T16:15:57Z；Grafana v13.2.4 published_at 2026-10-13T11:41:03Z。运行准备工具仅使用上述固定版本URL与摘要，不解析latest。
