# 单商家单仓库加固（2026-10-25）

本轮综合Maven最终命令选择 ReturnDispositionPolicyTest、ServiceEndpointsTest、GatewayBoundaryTest、EntryProtectionFilterTest、EntryProtectionPolicyTest、LoginProtectionTest、LoginAdmissionTest、OrderRecoveryFaultTest、PaymentRecoveryFaultTest，共29项、0失败/错误/跳过，11模块BUILD SUCCESS，日志`.local/hardening-tests-final.log`。后续仅优化入口限额提示文案，相关4类测试再次通过，见`.local/hardening-entry-final.log`。两端vue-tsc/Vite构建成功，仍有既有大包提示，日志`.local/hardening-web-build.log`。

真实数据库/进程验证分别记录：商品退货MyBatis/Spring事务4项，16份迁移及中文COMMENT；支付围栏MySQL2项（故障报告的8项中另6项为Mock编排）；支付跨JVM halt后自然租约到期恢复1项（通道为持久文件替身）；登录窗口原子SQL与Redis原子Lua并发；备份11组安全回归和新MySQL六个合成库恢复；Redis专用生命周期5个检查。证据总索引`.local/hardening-evidence.json`，详细范围见[加固交付](project-hardening.md)。这些测试存在重叠，不能简单相加作为全场景覆盖量。

真实Nacos未完成隔离前置检查，SDK集成用例实际跳过1项；未执行本轮前端真实业务交互、MQ完整故障矩阵、真实Compose业务数据恢复或压测。没有SSH或改动服务器、没有迁移现有数据库、没有重启已有业务服务或重打旧发布包。以下章节为历史记录。

# 统一Nacos发现本地增量（2026-10-25）

本轮仅本地修改。7个Feign接口移除url，网关固定lb服务名，其余HTTP与模拟签名回调通过LoadBalancer选取Nacos实例；模拟器补注册依赖。七服务统一namespace/group和注册IP参数，LoadBalancer关闭隐式重试。配置生成器及Compose不再设置下游直连地址，现要求预先准备可达Nacos。未修改数据库、未连接服务器、未重启已有服务、未重新打包旧发布JAR或部署。

`mvnw.cmd -B -ntp "-Dtest=ServiceEndpointsTest,GatewayBoundaryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：11模块BUILD SUCCESS，定向7项测试全部通过（4项实例解析、3项网关本机HTTP边界），其余测试未执行。首轮网关测试旧预期把未登录访问写成403，实际当前安全策略为401；修正测试为未登录401、有效测试身份403，不修改生产安全策略。首轮日志保留`.local/nacos-verification-first.log`，最终日志`.local/nacos-verification.log`，证据摘要`.local/nacos-change-evidence.json`。

七份YAML、namespace/group继承、Feign无url及部署脚本静态检查通过，PowerShell语法检查通过。未启动或连接真实Nacos，未验证七服务注册与真实Nacos业务联调；现有服务器部署和旧JAR保持原状。当前运行方式见[发现手册](runbooks/nacos-discovery.md)。以下章节为历史验证记录。

# 本机联调测试增量（2026-10-24 16:22–16:46）

用户本轮明确授权测试，部署暂不做，覆盖范围和详细证据见[integration/2026-10-24-local.md](integration/2026-10-24-local.md)。主套件61项、证据3项、消息重放4项、库存竞争4项均通过，6项数据库只读核验违规计数0；浏览器真实走通用户/商家20元补偿流程，保存完成页截图。首轮售后500失败证据保留，修复后重跑通过。

修复AftersaleStore代理字段直读空指针（Java构建后本机重启）、COMPLETED终态轮询及退货5张证据提示/限制（两端构建、浏览器复测）。售后重启后ERROR日志0；5个临时商家/平台身份权限收回，两端浏览器退出，测试数据和本机服务保留。

未执行远程/容器部署、性能、备份恢复、完整JUnit/Testcontainers或全部故障矩阵。历史B13/B14交付包是修复前版本，尚未更新；源码与本机运行版本包含修复。下文“不运行测试”为历史批次范围，不代表本轮授权。

# B14 本轮增量记录（2026-10-24）

- 静态根因核对MessageOperations返回字段与retry主键、六个管理页Web Lock等待后的generation、商家证据动作切换、售后预览错误/结束代际、购物袋watch执行顺序，完成对应代码修正。没有声称实际重现或竞态通过。
- `npm.cmd run build`两端vue-tsc/Vite成功；用户主JS150.91KB，后台主JS1030.24KB，后台大包提示仍存在。未改Java/SQL或依赖锁，不重复后端构建和服务重启。
- `python scripts/Package-Release.py`实际生成`.local/releases/tradeflow-0.1.0-20261024T081559Z-59d645f1.zip`，592188537字节，38载荷文件；SHA-256 `8544ca53b77d483762540e30d58ab32a4f4133b586c09c709cba9f7b512d465f`。旧B13包保留；当前包沿用B13未变Java产物和本轮前端产物。
- 仍未运行自动化、业务请求、浏览器重现、并发/故障/恢复/性能测试、Docker/Nginx运行、SSH或提交推送。项目完整验收仍未完成。

# B13 本轮增量记录（2026-10-24）

- 实际执行`.\scripts\Build-Release.ps1 -Restart`，只按本项目PID/创建时间停止Java和前端；11模块Maven BUILD SUCCESS、测试编译/运行跳过；npm ci安装72包，两端TypeScript/Vite构建成功；构建后七服务readiness UP、5173/5174静态HTML 200。数据库/Rabbit未停止。
- 实际生成`.local/releases/tradeflow-0.1.0-20261024T080649Z-aff2b18b.zip`，592184833字节（约564.75MiB），ZIP SHA-256 `f8c9a6d3962d36e303b0e7b453be8bb40c2e3ede7112c2eb2e6479ff9d297a92`，外部.zip.sha256已生成。
- ZIP有37个载荷文件，加manifest.json/SHA256SUMS共39个条目；元信息读取确认七JAR、两端dist和发布版前端Dockerfile，未出现.local、env、pem、key或log条目。哈希覆盖打包时实际写入字节；没有数字签名/来源认证结论。
- CI YAML语法解析成功，流程包含Java、固定Node/npm ci、两端构建和白名单打包上传；只落盘，未提交/推送/触发远端CI。Docker/Nginx仍不可用，未构建镜像或启动容器。
- 未运行自动化、登录/交易/退款/管理等业务请求、浏览器交互、并发/故障/恢复/性能测试；未SSH或部署。交付包和健康状态不代表项目业务验收通过。

# B12 本轮增量记录（2026-10-24）

- 新增前端多阶段Dockerfile、专用上下文白名单、根.dockerignore及Nginx配置；现有独立Compose增加web-user/web-admin，两端loopback18088/18089，project与数据卷不改名。JSON/YAML子集解析成功，共18个服务（含一次性迁移授权）。
- 已读取Docker Hub标签元信息：Node24.15.0-alpine摘要d1b3b4da11eefd5941e7f0b9cf17783fc99d9c6fc34884a665f40a06dbdfc94f；Nginx1.28.3-alpine摘要a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236。只是标签元信息，不是镜像拉取/安全扫描结果。
- `npm.cmd run build`两端TypeScript/Vite构建成功；后台仍有大于500KB提示。未改Java，不重复执行Java构建或重启业务。
- 静态核对服务gateway命名、loopback端口、健康依赖、非root只读/tmp、API转发与禁用自动重试、API错误不回退HTML、无秘密上下文、缺失资源404及SPA回退。
- 本机Get-Command未发现docker/nginx，未执行镜像构建、Compose配置命令、Nginx语法运行或容器启动；未业务请求、自动化/故障/并发/性能测试、SSH、安装Docker、远程部署或提交推送。当前Vite/Java仍为上批已启动版本，不将其作为容器验证证据。

# B11 本轮增量记录（2026-10-24）

- 支付与售后申请新增原键只读查询；单SQL关联当前主体/固定操作/原键/订单，未找到保持未知。新增用户订单详情及售后申请页恢复组件，不新建业务表或迁移。
- `npm.cmd run build`两端TypeScript/Vite成功；用户主JS150.87KB，后台主JS1030.24KB，大包提示仍存在，不作加载性能结论。
- `mvnw.cmd -B -ntp -Dmaven.test.skip=true package`11模块BUILD SUCCESS（15:54:03），未编译或运行测试。构建前按项目记录停止Java，完成后七服务readiness均UP，两个前端进程继续运行。
- 只读支付/售后`/v3/api-docs`确认两个新接口已注册，5173/5174静态根HTML均HTTP200；没有调用新业务接口。静态核对网关路由和鉴权、所属服务鉴权、查询参数绑定、原键/会话/卸载保护以及未知日记不清理。
- 未运行自动化、业务/登录/支付/售后/退款请求、浏览器交互、并发故障或压测；未SSH、远程部署或提交推送。完整业务及权限恢复验收仍未完成。

# B10 本轮增量记录（2026-10-24）

- 新增本人原下单键查询、未受理请求封键、商家按需履约地址接口及页面。复用既有表，无新增或改写Flyway迁移；同步网关路由/鉴权、订单鉴权与OpenAPI。
- 两端`npm.cmd run build`成功（TypeScript + Vite）；后台Element Plus大包提示仍保留。最终产物用户主JS约148.69KB、后台主JS约1030.22KB，无加载性能结论。
- `mvnw.cmd -B -ntp -Dmaven.test.skip=true package`最终11模块BUILD SUCCESS（15:48:27）；测试编译及运行均跳过。
- 最终重启七服务readiness均UP；订单服务只读`/v3/api-docs`确认三个新接口已注册，两端根静态HTML均200。未调用三个业务接口。
- 静态检查核对封键/迟到下单共享唯一行、墓碑无任务、既有订单不被seal取消、原键不入URL、商家最新身份/归属/状态、PII不入审计明文和持久存储；补齐网关地址路由与页面会话迟到响应保护。
- 未执行下单、封键、地址查看、登录或其他业务请求；未测试浏览器交互、并发/故障窗口、自动化或压测。未SSH、远程部署或提交推送。部署仍为本地开发环境。

# B9 本轮增量记录（2026-10-24）

- `npm.cmd install`安装72个包并生成根package-lock；两端`vue-tsc --noEmit && vite build`成功。最后收尾构建见下方B9说明；Element Plus主包仍有大于500KB警告，不影响构建结果。
- `mvnw.cmd -B -ntp -Dmaven.test.skip=true package`：11模块BUILD SUCCESS，测试编译及执行均跳过。先按记录停止本项目Java，构建后Start-Local.ps1启动；七个管理readiness返回UP。
- Start-Web.ps1启动本机5173/5174；两端根静态HTML均200并含app挂载节点。不是浏览器渲染或业务请求验收。
- 主代理静态检查补齐API相对路径、Blob账号代际、跨标签页命令与原键清理协调、后台页面懒加载、Vite源码可访问范围限制。独立审查代理因工具额度未完成，不能称独立评审通过。
- 未登录/注册、未运行交易/管理操作、未生成假数据、未自动化/故障/并发/性能测试，未SSH或部署，未提交推送。业务验收与恢复体验限制见[b9-development.md](b9-development.md)。

下文是各历史批次记录，须结合日期和范围阅读。

# 实际验证记录

## B8可观测性与交付工具（2026-10-24，当前实现，完整验收未完成）

- 遵照用户“不用测试”，没有编写或运行自动化、Smoke、业务演示/消息、故障注入、并发、压测、备份或恢复演练。无SSH、commit或push。只进行了构建/迁移/启动、监控配置语法、只读健康/指标/监控元数据及静态审查；这些不等于完整B8或项目最终验收。
- 实现七服务Micrometer/Prometheus、独立管理端口18080–18086及只读Bearer、readiness/liveness分离；JVM/Hikari/HTTP直方图和固定kind业务采样。SQL只读本库，独立线程、不在抓取中查询；失败NaN及采集状态、最后成功时间，不伪装为零。MySQL行锁为实例级SHOW GLOBAL STATUS，未加业务库跨库写权。
- Logstash结构化应用日志、安全HTTP/RPC关联传播，支付/退款、售后/对账任务、Outbox/消息提交后有限业务上下文；不输出正文、地址或凭据，业务号不作Prometheus标签。没有宣称完整W3C Trace/Span存储或端到端追踪验收。
- 固定官方Prometheus3.15.1/Grafana13.2.4，官方SHA256验证后安装到.local/tools；来源与摘要见deploy/prometheus/RELEASES.md。专用只读Rabbit exporter关闭代理/重定向，仅GET项目8队列元数据，不使用管理员、不读/确认消息。
- 独立Compose交付含MySQL/Rabbit、六次性迁移、逐表列运行授权与七应用；独立部署准备器保留既有秘密不自动轮换。CI默认package并跳过测试。备份器固定本机Docker端点、project、容器和部署/持久卷身份，显式停写与零应用连接前提，六库单dump及配置/证据归档；没有自动恢复或删卷。当前Docker不可用，这些工具与恢复流程未实跑。
- 独立静态审查发现并修复：StartLocal仍读业务健康端口导致启动失败的P1；独立部署缺Rabbit监控环境键的P1；备份可能混合其他Docker实例与当前本地秘密的P2。限定复核关闭，管理安全链未发现绕过原业务JWT的问题。
- 首次实际只读采样出现BadSqlGrammarException；固定指标SELECT证实MySQL1064定位到保留字LAG别名，SHOW GLOBAL STATUS成功。改为due_lag及相同读取键后六个业务采样器实际成功。Rabbit真实GET证实disable_stats会省略consumers，已移除参数并读取完整元数据。
- 最终实际 `mvnw.cmd -B -ntp -Dmaven.test.skip=true package`：11模块BUILD SUCCESS，2026-10-24 10:56:43 +08:00，`.local/build-b8.log`；测试源码编译和执行均跳过。既有Flyway校验/迁移入口成功，无新增业务表或迁移；六库7/15/23/20/12/15共92业务表，另六历史表，表COMMENT缺失0、字段COMMENT缺失0。
- 最终七个管理端口readiness及liveness全部UP，网关概览B8_OBSERVABILITY_UNTESTED。受保护指标真实只读抓取：六个Servlet模块collection_success=1，Gateway仅标准指标；实际固定kind数量商品1/订单13/支付19/售后8/通道8，用户无业务恢复kind。未制造HTTP业务流量或虚填P95。
- Prometheus实际8/8目标UP（七应用+exporter），业务collector六条均1，Rabbit collector=1；8个队列consumer值4个1、4个0。11条规则实际加载；代理执行promtool配置/规则语法校验通过，未运行规则测试或制造告警场景。Grafana实际数据库健康，UID tradeflow看板18面板，数据源tradeflow-prometheus指向本机19090。三监控进程状态owned（PID/创建时间/映像/实际回环监听）并保留运行。
- 资金冻结payment_frozen/refund_frozen指标已存在，完整性告警同时覆盖两者与reconciliation_conflict，新表达式运行health=ok。初始阈值、无外部通知接收器、无驱动/大账单/故障/并发/性能/SLO验收；前端用户端及后台尚未交付，容器非root秘密交付和完整prod保障仍有缺口。详见[b8-development.md](b8-development.md)、[delivery-status.md](delivery-status.md)。下方为历史批次，不用历史骨架测试替代当前业务验收。

## B7冻结账单与交易对账（2026-10-23，历史批次）

- 遵照用户“不用测试”，未编写或执行自动化、Smoke、业务演示、账单冻结/造差/修复请求、模拟消息、并发、故障注入或压测。仅静态审查、跳过测试构建、迁移、启动与只读健康/Swagger/数据库元数据；无SSH、commit或push。
- 实现模拟通道同RR一致视图冻结账单、原请求幂等、稳定分页、计数/成功金额/规范摘要及开发投影模式；支付侧先下载并流式验证完整性，再双向扫描，不完整账单进入INCOMPLETE且不产生缺失差异或资金修复。
- 实现稳定差异指纹、每轮不可变观察、动作预先持久、实时查询、统一支付/退款结果应用、缺失唯一流水受控补记、独立再次查询复查、人工原号恢复和定向核验。内部退款账户/来源/逐笔汇总不一致冻结不重算；不会凭账单造单、再次提交资金命令或自动解除冻结。
- 每日UTC 00:10稳定系统命令重扫最近三个完整日账期，旧未决另由持久任务/动作保留。运行SUCCEEDED仅表示扫描完成，差异需独立复查才RESOLVED；修复统计排除纯匹配动作。
- 最终实际执行 `mvnw.cmd -B -ntp -Dmaven.test.skip=true package`：11模块BUILD SUCCESS，2026-10-23 20:09:05 +08:00，日志`.local/build-b7.log`；测试源码编译和执行均跳过。构建前只停止本项目已记录的七个Java进程，MySQL和RabbitMQ保留运行。
- Flyway新增payment V4与payment_simulator V3成功，最终分别1–4和1–3；其余版本沿用user1–4、product1–4、order1–6、aftersale1–3。已执行迁移未原地改写。新9表（支付6、模拟器3），六库业务表7/15/23/20/12/15，共92张，另六张Flyway历史表。
- 实际information_schema检查：表COMMENT缺失0、字段COMMENT缺失0；B7九表整表UPDATE/DELETE授权0，下载行/观察/账单行限列UPDATE授权0，命令原参数非result_no字段UPDATE授权0；动作origin_run_no绑定列UPDATE授权1。任务只额外授予必要状态/检查点列，账本审计仍仅追加。
- 最终启动8080–8086七个健康均UP；网关概览B7_IMPLEMENTED_UNTESTED。只读Swagger核实支付新增11/11路径（12个操作，runs含GET/POST）、模拟器新增4/4路径，无缺失；没有调用这些业务接口。
- 独立静态审查确认T1快照一致视图与完整性门禁；T3补流水改为核对并采用锁定的当前成功事实，避免旧版本成功时间被错误写入。T2发现三项P2：低版本相反终态漏冻结、旧人工动作并行恢复及覆盖新指针、重试耗尽与问题状态/指针不同步。两轮修补复核关闭；合法旧非终态与回调成功交错返回LIVE_STALE，不误冻结。
- 原始完整账单集合改为模拟器每批500行冻结、支付前向流式验证，避免十万行同时保存在应用堆。这里只确认静态实现和编译，未实际验证驱动流式行为、大账单耗时、租约并发或故障恢复。
- 本轮未运行Docker或任何远程操作。MySQL、RabbitMQ和七个Java服务保留运行。使用说明见[b7-development.md](b7-development.md)；B8可观测性与完整交付验收仍待后续。以下均为历史批次结果，不能替代本批业务或资金正确性验收。

## B6金额仅退款与退货退款（2026-10-23，历史批次）

- 遵照用户“不用测试”，未编写/运行自动化、Smoke、业务演示请求或消息、并发、故障注入和压测；本轮仅静态审查、跳过测试编译、迁移、启动、只读健康/Swagger/数据库元数据。无SSH、commit或push。
- 实现REFUND_ONLY逐件1分至剩余实付补偿、RETURN_REFUND剩余实付退货，保留未发货整单流程。订单保存规范分配摘要与明细，七天申请期限，成功结算核验逐件金额/件数，释放与汇总不清除其他售后的占用。100元先补偿20元再退剩余80元是代码规则，未作业务请求验证。
- 售后实现预览、原键申请、物流提交与七天超时竞争、一次全部验收/拒收、证据与材料查询。订单验收RECEIVED建立独立入库义务，商品从权威收据核对购买件、原销售上限及跨收据防重；退款失败仍保留实物与退款义务，资金成功和入库APPLIED共同成立后完成。
- 实际最终执行 `mvnw.cmd -B -ntp -Dmaven.test.skip=true package`：11模块BUILD SUCCESS，2026-10-23 19:34:32 +08:00，`.local/build-b6.log`；测试编译和执行均跳过。初次构建因Windows运行中的网关JAR占用失败，停止本项目七个JVM后构建成功，未操作其他进程。
- Flyway实际成功：user V1–V4，product V1–V4，order V1–V6，payment V1–V3，payment_simulator V1–V2，aftersale V1–V3。新增商品V4、订单V6、售后V3；既有已执行迁移未改写。
- 六库业务表分别7/15/23/14/9/15，共83张，另六张Flyway历史表。新增10张表（商品2、订单3、售后5），表COMMENT缺失0、字段COMMENT缺失0。数量不代表完整目标模型已实现。
- 实际information_schema权限读取：订单验收收据整表UPDATE为0，限列UPDATE(status)为1；证据元数据非state字段UPDATE授权为0。新增分配、物流、验收、文件关联和商品入库事实只追加。订单JVM仅新增必要product服务凭据，迁移/管理秘密不进入业务JVM。
- 最终构建后8080–8086七个JVM健康均UP，网关概览B6_IMPLEMENTED_UNTESTED；只读Swagger确认商品新入库路径1/1、订单预览/收据/恢复路径4/4、售后预览/物流/验收/文件/材料路径5/5，均无缺失。没有调用这些业务接口。
- 独立跨服务静态审查发现证据按阶段计数导致总量可超6张的P2，已改售后主单锁下合计<=6，退货申请最多5张保留验收容量，定向复核关闭。集成中补强逐件结算摘要/金额/件数及影响行数检查，入库恢复业务移入应用服务。未发现其他有明确依据的重要阻断项；静态结论不替代行为验收。
- 文件默认`.local/evidence`，元数据和文件需共同备份；未绑定一天后可清理，已绑定不自动删除。写盘成功但数据库提交结果未知时保留文件供人工核对，不能因异常误删可能已登记证据。生产持久卷、完整Docker部署与灾备恢复未验收。
- MySQL、RabbitMQ及七个Java服务保留运行。详见[b6-development.md](b6-development.md)；交易对账仍待B7。以下均为历史批次结果，不能替代本批业务、资金、并发或故障恢复验收。

## B5可靠消息（2026-10-23，历史批次）

- 按用户要求，未编写或执行自动化测试、Smoke、业务演示请求/消息、并发、故障注入或性能测试。未连接SSH、未提交或推送。
- 实现RabbitMQ交付、事务Outbox、Inbox幂等、数据库延期恢复、原始消息隔离、独立确认发布DLQ，以及平台管理员按原ID/原载荷重放与操作审计。支付与退款不再自动通过HTTP交付，关闭MQ只暂停传输，不降级HTTP。
- 最终实际执行 `mvnw.cmd -B -ntp -Dmaven.test.skip=true package`：11个模块BUILD SUCCESS，2026-10-23 13:11:10 +08:00，日志 `.local/build-b5.log`；测试编译和执行均跳过。
- Flyway成功：user V1–V4、product V1–V3、order V1–V5、payment V1–V3、payment_simulator V1–V2、aftersale V1–V2。新增迁移为order V5、payment V3、aftersale V2，既有已执行迁移未原地改写。
- 本机MySQL 127.0.0.1:13316；六库业务表分别7/13/20/14/9/10，共73张，另有6张Flyway历史表。此73张的组成与原目标字典不同，不表示全部目标功能已实现。只读检查表COMMENT缺失0、字段COMMENT缺失0。
- 运行账号按表/列授权，消息证据及审计不可修改；实际元数据核实支付Outbox整表UPDATE授权为0、消息证据修改授权为0。启动脚本按完整环境精确允许清单为子进程分配变量，业务JVM不继承数据库迁移/管理或broker管理凭据。
- 本机独立RabbitMQ 4.1.8，复用已安装OTP26；AMQP 127.0.0.1:5678、管理127.0.0.1:15678，专用节点tradeflow_local@localhost、vhost /tradeflow。未接管原有RabbitMQ 4.3.2安装。拓扑只读检查8个durable队列（4业务、4DLQ）、14条业务/DLQ交换机绑定（另有8条默认交换机绑定）、4个业务消费者；队列为空，没有构造业务消息。
- 最终构建后七个JVM 8080–8086实际健康UP，网关概览为B5_IMPLEMENTED_UNTESTED；订单、支付、售后Swagger已列出消息管理接口。MySQL、RabbitMQ及七服务保留运行。
- 独立静态审查发现的进程环境凭据继承、事件必填/资金Schema一致性、人工重放与延期锁反序均修补并复核关闭；人工重放再次永久失败恢复QUARANTINED，保留原始证据和独立DLQ状态，限定复核通过。
- Inbox、业务效果与后续任务在同一本地事务提交后ACK；延期/隔离ACK表示已可靠交给本库，不等于已发到broker DLQ。发送确认、重复投递、恢复预算与租约围栏已有实现，但未执行对应业务或故障场景，不能声称可靠性验收通过或exactly-once。
- Docker不可用，Compose仅更新配置，未运行验收。金额仅退款、退货及对账仍待后续批次。使用说明见[b5-development.md](b5-development.md)；以下为历史批次记录。

## B4履约与未发货退款（2026-10-22，历史批次）

- 按用户要求，未写/执行自动化、Smoke、下单退款业务演示、并发、故障注入或性能测试。没有SSH或远程状态核实，也没有提交/推送。
- 实现发货/本人收货、未发货整单权益授权及售后、审核撤销、双额度退款、独立通道退款、退款后销售回补、迟到支付固定来源退款及受控恢复。金额仅退款/退货在B6，MQ在B5，未声称已实现。
- 实际执行跳过测试clean package，后续修补后package：11个Maven模块BUILD SUCCESS，最终时间2026-10-22 17:51:55，.local/build-b4.log。测试编译/执行均跳过。
- Flyway实际成功：trade_user V1–V4；trade_product V1–V3；trade_order V1–V4；trade_payment V1–V2；trade_payment_simulator V1–V2；trade_aftersale V1。既有已执行历史版本没有原地改写。
- 本机MySQL127.0.0.1:13316，六个逻辑库业务表分别7/13/14/10/9/5，共58张，另有6张Flyway历史表。只读information_schema检查：表COMMENT缺失0，字段COMMENT缺失0。
- 运行账户按表/列增量授权，新增refund_account/source/order和通道退款限列UPDATE，售后幂等只限列无操作UPDATE。账本、库存流水、审计、状态历史及退款事实表的UPDATE/DELETE表级授权检查为0。业务JVM不持迁移/root凭据。
- 七个JVM8080–8086实际健康UP，保留运行。实际Swagger路径数：商品14、订单20、支付11、售后10、模拟器8（均包含system/info），只读确认B4业务和内部接口已列出；未通过业务请求验证行为。网关概览为B4_IMPLEMENTED_UNTESTED，JAR不含spring-webmvc。
- 资金任务独立静态spec/quality审查通过。订单/库存审查发现并修复两项重要问题：迟到成功到达后重新开始SETTLE阶段预算；公开授权内容version保持固定。新增受控退款恢复入口、成功事实三重绑定及行预留数量校验已复核。售后和最终跨模块审查未发现阻断项。
- 启动发现旧进程记录的StartTime在进程退出/不可读取时为空，已补脚本try/catch和空值保护；无法确认PID与启动时间时不认领、不停止。修补经独立静态复核，随后启动成功，仅管理本项目进程。
- 协议边界：已在途事件无法撤回，依赖通道终态不可反转。退款或原支付矛盾后冻结自动执行及未发送事件；生产TLS/服务JWT/凭据轮换未完成。当前模拟器创建即固定通道编号，未来外部通道若允许早期空编号的旧事实可能保守转人工，需适配时明确规则。
- 静态审查、迁移成功及健康UP不代表业务、并发、资金正确性或故障恢复验收。使用与范围见[b4-development.md](b4-development.md)。以下均为历史批次记录，不能把旧骨架测试结果用于本批。

## B2下单与B3支付（2026-10-22，历史批次）

- 遵照用户“不用测试”，未编写/运行自动化、Smoke、业务演示、并发、压测或故障注入；仅编译、迁移、启动、只读健康/Swagger/元数据检查及静态审查。
- B2最终clean package（-Dmaven.test.skip=true）11模块BUILD SUCCESS，记录.local/build-b2.log，时间16:57:55；七服务当时健康UP。独立静态审查未发现确定的重要缺陷。
- B3执行跳过测试clean package，后续静态审查修补后重新package；最终记录.local/build-b3.log。测试源码编译和执行均跳过。最终构建时间17:23:44，11模块BUILD SUCCESS；重启七服务8080–8086健康均UP，服务与本机数据库保留运行。
- Flyway成功：user V1–V4，product V1–V2，order V1–V3，payment V1，payment_simulator V1。业务表分别7/12/10/8/5；表与字段缺失COMMENT均为0。既有已执行迁移未改写。
- 本机MySQL8.0.46仅127.0.0.1:13316，数据.local/mysql/data；未连接远程。五库独立账号，业务运行无库级DDL权限，按表/列授权；资金流水、审计只追加。新增凭据增量补齐，旧秘密未轮换。
- 只读Swagger元信息：用户9、商品13、订单9、支付8、模拟器5条路径，包含system/info。实际列出B3意图、结果、支付/查询/关闭、回调、通道与场景入口；没有通过业务调用验证这些接口。
- 网关JAR不含spring-webmvc；公开路由仅外部已实现业务，内部资金/通道/场景不可通过公开网关访问。
- 独立静态审查发现人工冻结后旧终态仍可经内部查询/关闭和HTTP Outbox驱动释放的问题；已补requireTrusted与未发送事件MANUAL_REVIEW隔离，复核确认关闭。已在途事件不能撤回，协议仍要求通道终态不可反转。
- 支付恢复与事件交付最多30次自动网络尝试后保留人工任务，不按失败释放；本批未提供支付/模拟器人工重启API。订单原有10次/30分钟恢复预算及平台重启保留。
- B3是持久HTTP Outbox，RabbitMQ未实现；实际退款、发货收货、对账未实现。LATE_PAYMENT_REFUND_REQUIRED保留人工资金义务，不能当作已经退款或关闭完成。
- 旧骨架的测试通过记录仅适用于当时版本，不能作为B1–B3验收。编译和UP也不能证明支付/并发/重启恢复正确。

## B1用户权限与商品库存（2026-10-21，历史批次）

- 本轮遵照“不用测试”，未写/执行自动化测试、Smoke、业务演示请求、压测和故障注入；构建及以下就绪信息不替代业务验收。
- 最终 `mvnw.cmd -B -ntp -Dmaven.test.skip=true clean package`：11个Maven模块BUILD SUCCESS，测试编译/执行均跳过，日志.local/build-b1.log。
- 本机专用MySQL8.0.46监听127.0.0.1:13316，数据.local/mysql/data；不复用文档验证实例，没有连接远程服务器。
- Flyway实际成功：trade_user V1–V4，7张业务/安全表；trade_product V1，10张表；另有各库Flyway历史表。数据库元信息显示这些业务表字段缺失中文注释数为0。
- 运行账号按所属表授权；无DDL，库存流水和审计只有SELECT/INSERT。迁移由独立Java入口运行，迁移凭据未注入业务JVM。
- 首次平台管理员及审计已初始化，平台有效管理员计数1，商品计数0；没有填充演示商品、订单或伪造库存。
- 最新七个JVM实际启动，8080–8086健康UP。8081/8082实际Swagger列出本批接口，并提供Bearer scheme；网关JAR没有spring-webmvc、tomcat-embed-core或common-web。
- 本批实际实现边界见[b1-development.md](b1-development.md)，目标OpenAPI按x-implementation-stage区分。身份/商家越权、乐观并发、幂等和多SKU回滚仅有代码实现，未作业务验收。
- 现有SSH隧道记录对应进程已不在运行，本轮未重新登录或核实服务器状态。七个服务以Start-Local运行，Nacos/Sentinel关闭；此前远程部署结果仅为下方历史记录。
- 原骨架测试为历史版本，本轮未更新为完整B1集成测试；不能将历史11项通过当作B1测试通过。
- 独立只读静态代码审查未发现有充分依据的重要缺陷；审查未执行测试、业务请求或远程操作，结论不代表并发或故障验收。

## 远程开发环境接入（2026-10-21，后续更新）

- 本轮遵照用户“不用测试”：未执行自动化测试、Smoke、压测或故障注入。下列为构建、部署及基础连接确认，不替代业务验收。
- `mvnw.cmd -B -ntp -Dmaven.test.skip=true clean package` 全11个 Maven 模块构建成功；测试编译与执行均跳过。日志 `.local/build-dev.log`。
- 配置统一为每服务一个 application.yml，含 dev/test/prod；clean 构建已清除旧 local/infra 编译资源。
- 七个 dev JVM 实际启动，健康 UP；六个 Nacos 服务各有一个健康实例；网关经 lb:// 访问订单模块信息成功。
- 远程 Docker 的 tradeflow-nacos、tradeflow-sentinel 已运行；本机 SSH 正向及 Sentinel 反向隧道已接通。已读取客户端实际流控/熔断规则，未制造触发流量。
- Nacos 和 Sentinel 组件账号实际登录成功；Sentinel 控制台收到六个健康客户端心跳，远程经反向隧道读取本机规则成功。
- Nacos 的 TRADEFLOW 组有18项配置；当前保护模块信息查询，业务交易和 Feign RPC 尚未实现。
- 下方为接入前的历史记录；其中11项测试通过属于旧骨架版本，不代表本轮新增接入代码已通过自动化测试。

# 初始骨架历史记录

> 2026-10-21。本批为 B0 中可启动骨架部分，不表示完整 B0 或交易功能已经验收。

## 构建与自动化测试

- JDK 21.0.10、Maven Wrapper 3.9.11。
- 执行 `mvnw.cmd -B -ntp verify`，父工程及 10 个模块均 BUILD SUCCESS。
- 共 11 项测试，0 失败、0 错误、0 跳过。

| 测试组 | 数量 |
| --- | --- |
| com.tradeflow.aftersale.AftersaleApplicationTest | 1 |
| com.tradeflow.web.ServiceBoundaryTest | 3 |
| com.tradeflow.gateway.GatewayBoundaryTest | 2 |
| com.tradeflow.order.OrderApplicationTest | 1 |
| com.tradeflow.payment.PaymentApplicationTest | 1 |
| com.tradeflow.paymentsimulator.PaymentSimulatorApplicationTest | 1 |
| com.tradeflow.product.ProductApplicationTest | 1 |
| com.tradeflow.user.UserApplicationTest | 1 |

- 先记录 MVC 与 Gateway 边界测试失败，再实现并验证通过。
- Gateway 真实 HTTP 上游测试验证路径改写和伪造身份头删除。
- 最终网关 JAR 不含 spring-webmvc、tomcat-embed-core 或 common-web；tomcat-embed-el 仅为校验器的表达式实现，不是 Servlet 容器。

## 实际本地进程

- 已实际启动、停止并重新启动 Gateway、五个业务服务和独立模拟器。
- 7 个健康端点 UP，6 条真实下游网关路由通过。
- 3 个内部/敏感/未启用业务路径明确返回 403。
- 订单 Swagger 页面 HTTP 200；当前生成的 API 仅包含实际 /system/info。
- 启动与停止脚本仅管理记录的 PID 和启动时间，未操作其他 Java 或数据库进程。
- 已保留本项目七个 local 进程运行，入口 http://localhost:8080/ 。状态随用户停止或关机变化。

## 其他检查

- 源 YAML、POM XML、PowerShell 语法检查通过。
- 独立只读代码评审未发现本批范围内的阻断缺陷；补齐验证记录并收窄 B0 完成表述。
- 业务服务迁移与资料包对应文件一致，默认未执行。

## 未验证或未实现

- 没有执行 Docker Compose、真实数据库迁移、Nacos 注册配置、MQ 或 Redis 联调；当前机器没有 Docker。
- JWT 登录、业务资源权限、订单、库存、支付、退款、消息任务、对账与前端业务界面尚未实现。
- 没有压测或生产高可用验证，不将健康 UP 表述为交易能力已就绪。
- CI 工作流文件已提供，本轮未推送或触发远端 CI。

原始摘要：docs/build-results.json、docs/local-smoke-results.json。详细构建及进程日志位于 Git 忽略的 .local*.log 与 .local/logs/。
# 服务器部署实测（2026-10-24，未通过完整业务验收）

目标YOUR_SERVER_HOST，发布目录`/opt/tradeflow/releases/20261024-092613`，Compose项目tradeflow-b8。构建七个Java和两个Nginx镜像、完成六库Flyway及运行授权，最终11个常驻容器healthy、RestartCount均0、无OOMKilled。两端静态/SPA/资源与入口隔离共16项HTTP检查通过，浏览器可显示真实演示商品及管理登录页。

服务器仅约1.7GiB内存。暂停其他两个项目及独立模式不依赖的Nacos/Sentinel、增加4GiB swap、顺序启动并收紧GC/堆/连接池后仍有频繁换页。最终业务验收通过登录、商品入库和下单，支付同键三次仍504，未进入完整退款验收，报告状态FAILED。只读查明演示订单待支付，对应支付号在支付库/模拟通道无记录，资金流水0；未把超时当作业务失败修改数据。

部署配方修正已同步源码，Python语法与JSON配置检查通过；未修改本次上传的JAR和前端dist，不重新声称本地集成测试或压测通过。旧版本章节为历史记录；当前部署事实及操作、账号取用、暂停服务、资源限制详见[服务器部署手册](runbooks/server-demo.md)。没有生产、备份恢复或高可用验收结论。

