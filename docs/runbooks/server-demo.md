# 服务器演示部署（2026-10-24）

目标服务器YOUR_SERVER_HOST，Compose项目tradeflow-b8，发布目录`/opt/tradeflow/releases/20261024-092613`。本次为独立演示环境：MySQL、RabbitMQ、七个Java应用及两套Nginx前端；独立模式不依赖Nacos/Sentinel注册发现。不等同于prod或高可用部署。

## 访问与账号

服务器端仅发布回环端口，通过当前电脑的SSH隧道访问：用户端 http://127.0.0.1:18088/ ，商家/平台端 http://127.0.0.1:18089/ 。隧道停止后，在本机终端执行以下命令并自行输入SSH密码（不将密码写进命令）：

```powershell
ssh -N -L 18088:127.0.0.1:18088 -L 18089:127.0.0.1:18089 root@YOUR_SERVER_HOST
```

平台用户名`platform_admin`，初始密码在服务器发布目录`.local/deployment/credentials.json`的`bootstrap`字段；演示验收账号保存于同目录`demo-accounts.json`。仅通过受保护SSH读取，不提交仓库、不写进文档。服务器数据独立，不导入开发机已有用户和交易。

## 资源与运维

服务器2核、约1.7GiB内存，使用`compose.server-demo.yaml`限制JVM与连接池，逐个启动。演示JVM使用Serial GC、业务服务16–48MiB堆（网关16–64MiB）、单处理器预算、一级即时编译、24MiB代码缓存、业务服务8MiB直接内存（网关16MiB），非网关Tomcat最多8线程、数据库连接池最多2个连接。新增4GiB交换文件`/opt/tradeflow/runtime.swap`，保留原交换空间；因此只适合功能演示，没有吞吐量或响应时间承诺。

经用户授权暂停flashslot的web/app/redis/rabbit/mysql容器，以及chentu-wensheng的web/api容器，保留其配置和数据；未自动恢复这些项目，避免内存争用。启动模拟器时出现严重换页和数据库健康检查超时，进一步暂停本模式不依赖的tradeflow-nacos、tradeflow-sentinel，保留其配置与数据；随后模拟器恢复healthy。不要在当前资源限制下同时重启这些项目与基础设施。

维护时必须使用相同项目名和两个配置，避免丢失低内存参数：

```bash
cd /opt/tradeflow/releases/20261024-092613
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml -f deploy/compose.server-demo.yaml ps -a
# 只停服务，保留数据
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml -f deploy/compose.server-demo.yaml stop
```

不要执行`down -v`，不要将初始SQL重新导入已有库。业务升级前仍需独立安排停写、备份、迁移及恢复验证。

## 发布溯源与实际修正

原始ZIP为`tradeflow-0.1.0-20261024T092613Z-3bec0ed7.zip`，592188917字节，SHA-256为`ed1a4e89c3c195e82ae0c40fd760e96d50d1e4a473bb5f9f59126d3f010377be`。上传后验证压缩包及manifest全部载荷字节。现场仅修正部署配方，JAR和前端dist未改：

- 修正Temurin摘要拼写，使用镜像已有curl，增加后端构建上下文白名单。
- Linux初始化SQL及root密码文件采用root:999、0640，父目录仍0700。MySQL首次初始化中断后，确认六个业务库及账号均不存在，保留系统卷后补齐初始化；审计在发布目录`initialization-recovery.json`。
- 初始平台密码生成满足大小写、数字和特殊字符政策；确认平台账号尚未创建后修复本次新环境凭据，不做已存在账号的隐式密码轮换。
- 添加低内存Compose覆盖配置。原始ZIP保留溯源，不能重新解压覆盖已修正配方。

运行镜像：Java21.0.12+8、MySQL8.0.46、RabbitMQ4.1.8-management、Nginx1.28.3-alpine。六个Flyway任务及runtime-grants均Exit0。

初版128MiB堆及默认GC即使全部容器healthy仍发生首次登录504、商品权限查询503和持续换页，不能作为验收通过。已保留历史失败与启动日志。80MiB堆仍出现写接口超时，最终使用48MiB业务堆与64MiB网关堆。

## 最终验收结果：未通过

- 11个常驻容器healthy，六个迁移及一次运行授权任务Exit0；最终检查所有容器RestartCount为0，未发生OOMKilled。
- 通过SSH转发验证两端根页、SPA路由和入口JS/CSS为200，未登录订单接口401，内部接口、Actuator和缺失资源404，共16项HTTP检查通过。
- 浏览器可显示服务器真实创建的两条演示商品，并可打开管理工作台登录页。商品加载曾多次超时，手动刷新后显示；不能声称页面流畅稳定。
- 最终业务脚本通过平台/买家/商家登录、商品创建与库存调整，并成功下单到PENDING_PAYMENT；支付接口三次相同幂等键请求仍504，完整支付、退款与库存回补验收未通过。原请求存于受保护`.local/deployment/acceptance-requests.json`，不创建新支付键重试。
- 演示订单`ord_4810c999ddda41d6800676a850c15e56`，预分配支付号`pay_3123484ca3484510ad35e3acca01d0ff`；结束前只读核验时订单待支付，支付库与模拟通道均无该支付号记录，资金流水0。此为查询时刻的事实，不通过修改数据库伪造终态。
- 失败报告`remote-acceptance.json`、日志`/opt/tradeflow/remote-acceptance.small.log`、订单查询`final-order-status.json`均保留。原始失败与恢复尝试未删除。
- 系统采样存在持续换页，单次采样I/O等待73%；这属于资源诊断，不是压测成绩。压缩GC/堆后仍无法提供稳定业务响应，停止进一步缩小内存。

部署产物、配置和数据已保留，可在扩容后使用相同Compose配置重启并重新验收。建议至少4GiB内存重新验证；如同时启用Nacos、Sentinel及监控，建议8GiB起再按实际占用调整，此为容量建议而非已验证性能保证。未执行服务器压测、完整故障矩阵或备份恢复。
