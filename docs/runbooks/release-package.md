# TradeFlow 运行产物交付包

这是七个Java启动JAR、两套前端dist及部署配方，非源代码包、数据备份或Docker离线镜像包。构建和校验和不代表业务、权限或容器验收通过。当前加固版本必须重新构建七个JAR和两端前端后打包，不能复用旧ZIP。包内无账号密钥与业务数据，勿直接覆盖任何已有部署目录或数据库卷。

## 文件核验与准备

在解压前核对旁边.zip.sha256与ZIP哈希，解压后按SHA256SUMS核对文件；manifest.json列出载荷文件路径、字节数和哈希。清单本身由SHA256SUMS覆盖，ZIP整体由外部.sha256覆盖。哈希用于完整性检查，不是发布者数字签名或来源认证。解压到全新目录，保留原包；不要直接展开到现有服务目录。

部署机器需要Python3.12、cryptography、Docker Engine及Compose V2，并能拉取固定镜像。当前源码强制Nacos服务发现，准备前须设置容器可达的NACOS_SERVER_ADDR及实际客户端凭据、namespace/group，详见docs/runbooks/nacos-discovery.md。应用Compose不会自动创建Nacos。部署不需要本地JDK、Maven或Node，前端dist已构建，Java在JRE容器运行。需要安装Python依赖时在单独venv中安装，不修改系统依赖。

从交付包根目录执行：

```powershell
python scripts/Prepare-Deployment.py
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml up -d --build
```

准备器仅在本机受保护.local/deployment生成新部署秘密；不要打印或分享credentials、env和Compose展开内容。已有部署升级须沿用其原有身份、密钥和持久卷，并按停写、备份、迁移和授权流程操作；不能在新目录盲目生成另一套密钥后挂载旧数据卷。具体恢复边界见docs/runbooks/backup-restore.md。本机开发与独立容器部署的账号、数据分离。

先准备可达Nacos，之后启动顺序为MySQL/Rabbit/Redis → 六库迁移 → runtime-grants → 七个Java应用 → 两端Nginx。前端分别在http://127.0.0.1:18088/、http://127.0.0.1:18089/，网关127.0.0.1:8080；均不对公网开放。受控跨机访问需要HTTPS及两个独立origin，不能仅改监听地址就用于公网。

包内Compose已选择deploy/Dockerfile.web.release，以dist构建静态镜像，不使用源码版Node构建阶段。后端Dockerfile仍从各模块target复制对应JAR。本包不带数据库、MQ消息、证据、日志、引导密码或其他.local内容。前端/后端镜像尚需联网拉取；本包不是完全离线安装器。

## 状态与维护

首次平台账号platform_admin的随机密码位于运行准备器生成的受保护.local/deployment/credentials.json。不要把该文件放到Web目录、交付包或代码仓库。不包含演示商品、订单或余额。

```powershell
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml ps -a
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml logs --tail=100 migrate-order runtime-grants
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml stop
```

迁移失败保留日志和卷，按原因修复；不自动baseline或删除卷重来。健康UP不是业务验收，备份脚本不是恢复演练通过。当前交付状态及已知限制见docs/delivery-status.md、docs/b12-development.md、docs/b13-development.md。
