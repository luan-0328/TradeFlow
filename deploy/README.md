B12更新：独立应用Compose已增加两套前端静态容器，入口与边界见[前端交付](../docs/b12-development.md)。仍未执行容器构建或部署。

# 部署入口

当前完整独立容器配置见 [七服务部署说明](README.application.md) 和 `compose.application.yaml`，包括迁移、最小权限及持久卷。监控本机运行见 [B8说明](../docs/b8-development.md)。当前没有Docker可执行文件，容器部署与恢复演练未执行。

以下 `compose.yaml` 为早期基础设施模板，不能当作完整七服务启动入口；原V1拆分、73表初始化说明均为历史背景，当前92张表已由分阶段Flyway实际执行，不得重写或重复全量导入。

# 基础设施与迁移

全套 Compose 是待验证模板。已实际部署的远程 Nacos/Sentinel 使用单独的 [remote/compose.yaml](remote/compose.yaml)，使用方法见 [远程环境说明](remote/README.md)。不要将下列全套模板直接启动到已有业务服务器。

1. 复制 `.env.example` 为 `.env`，设置全部密钥，不提交仓库。
2. Rabbit模板需先运行 `python scripts/Prepare-RabbitMQ.py export-compose` 生成受保护的.local/rabbitmq拓扑和配置。原生节点与容器使用相同5678/15678端口，先停另一种实例；此动作只导出配置，不启动节点。然后在项目根目录执行 `docker compose --env-file deploy/.env -f deploy/compose.yaml --profile infra up -d`。
3. 检查日志与健康，初始化 Nacos 管理账号，在 `TRADEFLOW` group 创建每个服务的同名 YAML。私网地址和密钥通过受保护环境注入。
4. MySQL 容器首次初始化只创建 6 个空库，不创建 73 张业务表。按阶段审核并执行服务迁移，再配置独立账号。
5. `application.env.example` 记录 Java 所需变量，不自动导入，不包含有效口令。
6. 单个服务通过 `java -jar ... --spring.profiles.active=prod` 切换真实依赖。缺密钥或配置启动失败，不回退假实现。

Flyway 默认关闭。首次开发阶段从资料包导入的完整 V1 属于**未发布目标基线**，当前未执行，可以在接入数据库前按实际开发批次拆成多版迁移；一旦执行发布则不可改写，后续必须追加版本。不要先手工执行全量 SQL 后让 Flyway 无核验 baseline。

`databaseReference` 与初始账号模板以开发资料包为准。使用迁移账号建表，正常业务账号不拥有 DDL 或不可变流水修改权限。

Nacos 3 的控制台端口与客户端 HTTP/GRPC 端口分开，本模板依次为 18880、18848、19848。客户端 GRPC 外部端口保持 HTTP + 1000。Redis 16379、MySQL 13306、RabbitMQ 5678（管理15678），避免默认数据库端口冲突。

Compose 只做本地基础设施模板，尚无完整业务容器、自动灾备、真实压测或生产高可用承诺。日常停止用 `docker compose ... stop`，不要用删除持久化卷代替重启。

B5本机原生Rabbit部署和恢复说明见[消息说明](../docs/b5-development.md)。历史accounts.sql.example对应最终目标模型，不是当前分阶段B5的可执行授权脚本；本机实际逐表/列权限由scripts/Prepare-Dev.py生成。Compose仍未运行验证，不把原生启动当容器验收。
