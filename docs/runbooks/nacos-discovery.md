# 统一Nacos服务发现（2026-10-25）

> 本文保留初次发现改造的实施记录。2026-10-26后续已完成真实Nacos八服务及浏览器验收，当前结果见[P0报告](../integration/2026-10-26-p0.md)。本地fixture使用官方RC的隔离边界见[专项说明](../integration/2026-10-25-nacos-isolation.md)；普通启动仍须先准备可达注册中心。

当前源码所有服务间业务调用都通过Nacos发现实例：五个核心业务服务、独立支付模拟器和网关均注册；网关仅转发已显式声明的公开路径，不自动公开内部接口。此轮仅本地修改，没有连接服务器、导入远程配置、重新部署或重启现有进程。

## 调用方式

- OpenFeign只声明`@FeignClient("tradeflow-product")`等服务名；同一目标存在多个客户端时保留不同contextId。没有url直连参数。
- 网关固定使用`lb://tradeflow-user`等目标，包括模拟器模块信息路由。旧UPSTREAM配置不再使用。
- 其余HTTP客户端及模拟器签名回调由共享`ServiceEndpoints`按服务名选择实例，不在启动时固定实例地址；保留原请求正文、调用凭据、超时和签名。无实例时返回依赖未确认，恢复任务沿用原业务号，不回退到localhost。
- Feign及LoadBalancer均不新增隐式请求重试。服务发现不替代业务幂等、Outbox、本地事务和Saga恢复；RPC仍不得处于业务数据库事务内。

## 本机运行

先自行准备Nacos，或在用户明确需要远程开发时手动建立已有隧道。本轮不会为本地改代码而发起SSH。Nacos默认`127.0.0.1:18848`，gRPC默认使用该端口加1000，即19848，两端口都必须可达。

启动前在当前PowerShell环境配置（账号密码通过受保护方式设置，不提交配置文件）：

```powershell
$env:NACOS_SERVER_ADDR = '127.0.0.1:18848'
$env:NACOS_GROUP = 'TRADEFLOW'
# NACOS_USERNAME、NACOS_PASSWORD使用注册中心实际客户端凭据。
# NACOS_NAMESPACE使用命名空间ID；空值为public，不能填写显示名称。
$env:NACOS_DISCOVERY_IP = '127.0.0.1'
.\scripts\Start-Local.ps1 -Build
```

同一业务环境所有服务必须使用相同namespace/group；本机与服务器建议使用不同namespace，防止跨环境选错实例。本机应用仅绑定回环地址，所以只适用于同机调用；跨机器运行时须调整server.address与注册IP，使调用方能实际访问注册的业务端口。

应用dev/prod默认启用服务发现；prod默认Nacos端口8848，需显式配置实际地址。test profile仅关闭注册与Nacos连接，用于隔离测试，没有保留业务直连模式。缺少实例不能视为业务失败或库存未预占，应保留未知结果并恢复。

Nacos配置中心与服务发现分开：仅需要注册发现时不必创建七份外部配置。配置中心由TRADEFLOW_NACOS_CONFIG_ENABLED及NACOS_CONFIG_IMPORT显式启用；Sentinel仍单独配置，不因启用发现而自动启用。

## Compose与配置生成

新的`Prepare-Deployment.py`要求先设置容器可达的NACOS_SERVER_ADDR，并按实际注册中心设置客户端凭据、namespace/group。应用Compose不自动部署或初始化Nacos；可使用单独准备的注册中心，或显式准备基础设施模板中的Nacos。不能把另一Compose网络中不可达的`nacos`主机名直接当成可用地址。

准备器不再生成下游IP/端口。容器配置将NACOS_DISCOVERY_IP留空，由客户端探测容器地址，适用于所有业务容器在同一Docker网络内互相调用；跨主机需逐服务配置实际可达的注册IP和端口。

此改动让Nacos成为运行前置依赖。旧服务器演示停用了Nacos，且原始ZIP内是旧版直连JAR；本轮没有更改它。以后部署必须重新构建七个JAR并打包，不能只替换YAML或把旧健康状态当作新版本验收。原低内存服务器的资源限制与失败报告仍然成立。

## 本轮验证

7项定向测试通过：4项实例解析测试覆盖实例切换、编码保留、无实例拒绝、事务边界及固定服务名限制；3项网关测试通过本机临时实例验证负载均衡路由、伪造身份头清理、未登录401/已登录仍禁止访问内部路径403、无实例503。测试注册表使用SimpleDiscoveryClient隔离Nacos连接，不是生产直连回退。七服务共11个Maven模块编译及测试源码编译通过，只执行上述7项测试。另检查Feign无url、配置继承、部署脚本语法及Compose不再覆盖直连路由。

初次发现改造仅离线验证；后续加固已尝试在全新目录启动本地Nacos，但Raft通配监听未通过隔离前置检查，未执行注册发现验证，详见../integration/2026-10-25-nacos-isolation.md。未连接远程Nacos，离线测试和编译不等于真实联调通过。现有运行进程及旧JAR不会自动切换到本轮源码。
