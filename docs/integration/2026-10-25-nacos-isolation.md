# 2026-10-25 本地独立Nacos隔离预检

2026-10-26已用官方Nacos **3.3.0-RC**在本地完成全回环监听、鉴权及发行包SDK真实注册/发现/注销/namespace隔离验证，并停止首个成功实例；八服务与业务SDK闭环另行验收。RC只用于本地fixture，不改变正式部署或业务SDK基线。以下2026-10-25记录保留：官方3.0.3的Raft监听为`0.0.0.0`，隔离工具按要求拒绝并停止自建进程，没有以Fake Discovery或跳过测试冒充通过。

## 发行包与执行证据

仅从[官方3.0.3发行页](https://github.com/alibaba/nacos/releases/tag/3.0.3)下载`nacos-server-3.0.3.zip`及官方MD5。首次下载中断，有界断点续传后校验匹配：

- ZIP大小195994435字节；官方MD5：`4e7b5f08e558443e15b808aa085b3c46`。
- SHA256：`801b694fed684daef926915a1632b7df056debbf48449a3ce140a3f4243d4978`。
- 下载包/来源证据保留在`.local/nacos-fixture-b559007640c34871a3516b942c3eaf61/download-provenance.json`。MD5仅用于核对发行字节，不声称是发布者签名。

在`D:/cloud/tradeflow`执行：

```powershell
python scripts/verification/nacos-fixture.py --distribution .local/nacos-fixture-b559007640c34871a3516b942c3eaf61
python -m py_compile scripts/verification/nacos-fixture.py
.\mvnw.cmd -B -ntp -pl tradeflow-payment-simulator -am '-Dtest=NacosDiscoveryIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Python隔离预检明确拒绝。最终证据`.local/nacos-run-571658afb9314274bd33e3fcf4e9b92a/result.json`记录`result=blocked`、`mavenExecuted=false`、`ownedServerStopped=true`；Java服务PID12012的真实监听如下：

| 用途 | 地址 |
| --- | --- |
| HTTP | 127.0.0.1:38839 |
| SDK gRPC | 127.0.0.1:39839 |
| 集群gRPC | 127.0.0.1:39840 |
| Console | 127.0.0.1:40839 |
| Raft | **0.0.0.0:37839（拒绝原因）** |

已显式配置server.address、nacos.inetutils.ip-address、nacos.remote.grpc.listen.ip为127.0.0.1，以及命名/admin/console鉴权、随机签名秘密和server identity；未输出随机凭据。工具只复制官方发行包到新UUID目录，拒绝任何非回环监听，未修改官方二进制、主代码或防火墙。首轮发现Oracle javapath会另起Java子进程，已核对专属命令行后关闭两个自建子进程，并改为直接调用`java.home/bin/java.exe`，确保Popen归属与清理可靠。最终进程盘点未发现本任务残留Nacos。

Maven经过窗口协调执行，`BUILD SUCCESS`，新增Java集成测试源码编译通过。Surefire结果为**1条、0失败、0错误、1跳过**；未设置专属fixture环境变量，未连接任何registry、DB或MQ。该结果只能证明编译及默认安全跳过。

## 阻塞与已准备内容

官方Nacos包携带JRaft 1.3.14。[其GrpcRaftRpcFactory源码](https://github.com/sofastack/sofa-jraft/blob/v1.3.14/jraft-extension/rpc-grpc-impl/src/main/java/com/alipay/sofa/jraft/rpc/impl/GrpcRaftRpcFactory.java)的createRpcServer使用`ServerBuilder.forPort(port)`，未使用Endpoint的IP绑定；现场Raft日志中的peer虽为127.0.0.1，操作系统仍显示通配监听。本机无Docker命令；wsl.exe存在，但WSL组件未安装，未新增系统组件。未放宽隔离要求继续测试。

`scripts/verification/nacos-fixture.py`已准备来源/服务器JAR校验、独占新目录/端口、回环监听归属核验、显式鉴权、独立namespace创建、可选受控Maven入口及只关闭自建Java进程。`NacosDiscoveryIntegrationTest`使用真实Nacos SDK发布及Spring发现/负载均衡，设计覆盖无实例拒绝、真实HTTP到达、注销后不可发现、替换实例和namespace隔离；目前仅编译，运行行为尚未验证。集成测试不启动完整业务应用，不代表完整七服务注册与调用验收。

截至2026-10-25，仍需取得可证明隔离的环境或官方绑定修复后才可运行实际用例；当时不能报告真实Nacos发现通过。2026-10-26的官方修复版本与实测结果见下节。未生成任何持续监控或通知。


## 2026-10-26 官方修复版本调查与可复用生命周期

本次只调查和运行自建本地fixture，没有SSH、现有Nacos操作、系统组件安装、防火墙修改或官方二进制补丁。3.0.3的`ProtocolManager.getCpProtocol()`为懒初始化，但命名模块的`ServiceMetadataProcessor`、`InstanceMetadataProcessor`、`PersistentClientOperationServiceImpl`及`SwitchManager`会在构造时注册CP处理器；这些构造路径没有standalone禁用开关。standalone只让配置存储走单机路径，不能据此声称命名Raft被关闭。`JRaftUtils.initRpcServer`直接构造`GrpcRaftRpcFactory`，不提供切换Bolt RPC的发行包配置入口。

官方稳定版3.2.4仍使用JRaft 1.4.0，该版本`GrpcRaftRpcFactory`仍为`ServerBuilder.forPort(port)`。官方3.3.0-RC使用JRaft 1.4.1，后者已改为`NettyServerBuilder.forAddress(new InetSocketAddress(endpoint.getIp(), endpoint.getPort()))`，因此可通过既有`nacos.inetutils.ip-address=127.0.0.1`传递Raft绑定地址。调查依据：

- [Nacos 3.0.3 ProtocolManager](https://github.com/alibaba/nacos/blob/3.0.3/core/src/main/java/com/alibaba/nacos/core/distributed/ProtocolManager.java)
- [Nacos 3.3.0-RC依赖基线](https://github.com/alibaba/nacos/blob/3.3.0-RC/pom.xml)
- [JRaft 1.4.0 RPC工厂](https://github.com/sofastack/sofa-jraft/blob/v1.4.0/jraft-extension/rpc-grpc-impl/src/main/java/com/alipay/sofa/jraft/rpc/impl/GrpcRaftRpcFactory.java)
- [JRaft 1.4.1 RPC工厂](https://github.com/sofastack/sofa-jraft/blob/v1.4.1/jraft-extension/rpc-grpc-impl/src/main/java/com/alipay/sofa/jraft/rpc/impl/GrpcRaftRpcFactory.java)

3.3.0-RC只作为本地验证基础设施候选，不改变业务Nacos SDK、不成为正式部署版本。官方发行资产为`nacos-server-3.3.0-RC.zip`，230078214字节；官方GitHub Release资产API提供的SHA256为`bc0a9e942b32efbc6ecebeaaadee4f823b1f16b008f9ad67e98f06149a7f5879`。fixture同时核对ZIP摘要及解压后的服务器JAR与ZIP内原件一致性。3.3引入的`nacos.plugin.auth.nacos.token.secret.key`与旧别名显式设置为同一随机秘密，避免发行默认空配置盖过旧前缀。

复用入口（默认结束时停自建服务；保留模式最长3600秒）：

```powershell
python scripts/verification/nacos-fixture.py --distribution .local/nacos-fixture-03113ae87d4641adba6ce62f511194b7 --version 3.3.0-RC --sdk-probe
python scripts/verification/nacos-fixture.py --distribution .local/nacos-fixture-03113ae87d4641adba6ce62f511194b7 --version 3.3.0-RC --sdk-probe --hold-seconds 1800
```

启动输出只给专属目录和非秘密endpoint。成功后`ready.json`给出`endpoint`、`namespace`、`credentialFilePath`、`stopFilePath`、`pid`和`processCreatedAtUtc`；凭据内容只留在受限ACL文件中。调用方读取上下文，将endpoint、namespace与凭据注入自己的新fixture，不接触任何已有registry。调用方创建`stopFilePath`指向的空文件即可请求持有Popen句柄的原脚本关闭；超时或任何非回环监听也会关闭。脚本始终只通过本次创建的进程句柄停止，不按名称、端口或历史PID杀进程；结束后移除`ready.json`，`result.json`保留退出结果。

`--sdk-probe`不执行Maven：从同一官方JAR提取原版SDK依赖到专属目录，用Java源码模式创建独立发布者/消费者，实测gRPC临时实例注册、发现、注销以及第二namespace不可见。它仅证明发行包SDK探针，不能代替业务应用现用SDK的Spring发现/负载均衡和八服务真实闭环。要运行已有业务SDK集成测试，须在另一已协调的构建窗口将`TRADEFLOW_NACOS_FIXTURE_DIR`设为正在保留运行的专属目录，再执行前文`NacosDiscoveryIntegrationTest`命令；不要将默认安全跳过当作通过。


### 2026-10-26 实测证据

完整官方发行包已按上述SHA256验证并原样解压；下载中CDN短读通过校验Content-Range的有界续传完成，最终只以完整ZIP摘要匹配为准。3.3原版`plugins`目录包含必须的认证及Derby数据源实现；fixture从已校验ZIP提取原件，并按官方`startup.cmd`的`-Dloader.path=<home>/plugins,...`布局加载。初始化改为3.3提供的`POST /nacos/v3/auth/user/admin`，未认证命名检查用`GET /nacos/v3/client/ns/instance/list`。显式打开官方旧API兼容开关，`/nacos/v1/auth/login`成功返回token，供业务现用SDK认证；无须改变SDK版本。3.3主发行包未携带legacy-adapter，因此V1 HTTP命名查询不能仅靠开关恢复；调用方应使用V3 HTTP命名接口或原有gRPC SDK。

首个成功的完整执行实例：`.local/nacos-run-8fe7cc70648049708491904d4e160c61/result.json`，`result=passed`、`mavenExecuted=false`、`ownedServerStopped=true`。服务PID30824，操作系统创建时间`2026-10-26T11:39:58.1913412Z`；SDK探针PID11124，创建时间`2026-10-26T11:40:17.8338976Z`，`exitCode=0`、`ownedSdkStopped=true`，SDK进程无监听。

| 监听用途 | 操作系统实测地址 | 归属PID |
| --- | --- | --- |
| HTTP | 127.0.0.1:26150 | 30824 |
| SDK gRPC | 127.0.0.1:27150 | 30824 |
| 集群gRPC | 127.0.0.1:27151 | 30824 |
| Raft | 127.0.0.1:25150 | 30824 |
| Console | 127.0.0.1:28150 | 30824 |

真实结果：未认证命名请求被拒绝；随机凭据登录成功；两个独立namespace创建成功；官方3.3.0-RC SDK两个独立客户端之间的临时实例注册、消费者发现、注销后不可见均成功，另一namespace始终不可见。SDK日志仅留在ACL限制的专属目录，不回显凭据。该探针不启动业务HTTP应用，不代表业务现用3.0.3 SDK或完整八服务闭环已经通过；后者由主验收任务单独执行。

之前三次启动分别因未复制3.3拆分插件、未使用官方loader.path、仍用已移除V1初始化API而停止：`nacos-run-f5e5082492b74a94b6cb7a416b6f6f51`（PID10872）、`nacos-run-6de8a246653248f98e0446446fa4bb7e`（PID7716）、`nacos-run-39aa26ca357148cc8259cad27c4ff124`（PID6168），各自`result.json`均记录`ownedServerStopped=true`。第三轮已观测到五监听全回环，但初始化未完成，未把它算作发现验证通过。成功实例随后经其专属stop文件关闭；为了给八服务及浏览器验收留足时间，主任务要求另建3600秒实例，不能复用已关闭的目录和namespace。


### 供主任务复用的3600秒实例

主任务明确要求另建3600秒保留实例；新实例`.local/nacos-run-0e4cc99e17cd46d7989c4af70134e848/ready.json`于2026-10-26再次完成同样的鉴权、namespace及真实SDK探针，`sdk-probe-result.json`为`passed=true`。endpoint为`127.0.0.1:25785`，namespace为`tradeflow-fixture-e518814880a8472e9db4448062ab96d3`；服务PID32164，创建时间`2026-10-26T11:42:44.8916913Z`，HTTP25785、SDK26785、cluster26786、Raft24785、console27785均为`127.0.0.1`。SDK探针PID5124于`2026-10-26T11:43:04.3990336Z`创建，已exit0停止，无监听。此上下文只在`ready.json`存在时有效；保留脚本持有其直接创建的Java进程句柄，主任务负责用stop文件结束并核对`result.json.ownedServerStopped`。保留期限最长3600秒，不自动续期，不使用历史目录重启。

业务现用SDK与Spring发现验证的命令如下；这是交给主任务在已协调Maven窗口执行的命令，本隔离调查子任务未执行Maven：

```powershell
$env:TRADEFLOW_NACOS_FIXTURE_DIR = 'D:/cloud/tradeflow/.local/nacos-run-0e4cc99e17cd46d7989c4af70134e848'
.\mvnw.cmd -B -ntp -pl tradeflow-payment-simulator -am '-Dtest=NacosDiscoveryIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

关闭当前实例只写它的专属stop文件（不按PID名称查杀），随后等待ready消失与result写入：

```powershell
New-Item -ItemType File -Path 'D:/cloud/tradeflow/.local/nacos-run-0e4cc99e17cd46d7989c4af70134e848/stop-requested'
```
