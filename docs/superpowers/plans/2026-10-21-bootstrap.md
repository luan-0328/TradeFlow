# TradeFlow 可启动工程骨架 Implementation Plan

> 执行方式：本会话直接实施（superpowers:executing-plans）。用户已明确要求按文档搭建并启动查看，不再增加审批环节。

**Goal:** 建立可构建、可独立启动、可通过网关检查的 Java 21 微服务骨架。
**Architecture:** 五个业务服务、Gateway、独立模拟器；common/contracts/common-web 仅提供基础契约和 MVC 支持，网关保持 WebFlux 隔离。
**Tech Stack:** Java 21、Boot 3.5.16、Cloud 2025.0.3、Alibaba 2025.0.0.0、Maven Wrapper。
**Spec:** D:/cloud/TradeFlow-开发资料/docs/01-TradeFlow完整开发文档.md；本批对应 B0，不实现交易业务。

## Global Constraints

业务方法中文注释；数据库字段中文 COMMENT；每服务独立数据库；不得伪造交易成功。当前 local 模式不连接 MySQL/Nacos/MQ/Redis，infra 模式显式配置后连接。只归档迁移，不自动建库，不动已有 MySQL。

## Review Focus

- Gateway 不混入 Servlet；各业务服务可独立启动。
- 客户端身份头不能透传为可信身份。
- 管理入口和未来业务接口默认拒绝，公开信息不泄露配置/密钥。
- 停止脚本只停止本次项目进程，端口占用时拒绝覆盖。
- local 启动无外部依赖；infra 配置不静默回退假数据。

## Task 1：模块、版本和构建

- [x] 创建父 POM、10 个模块、Maven Wrapper、项目约定和目录说明。
- [x] 先写共用 MVC/网关 HTTP 契约测试，运行并记录缺失入口的失败。
- [x] 实现统一响应、追踪、模块信息、健康与默认拒绝策略。
- [x] 运行模块测试验证。

## Task 2：七个独立进程与网关

- [x] 添加启动类、中文用途说明、local/infra 配置、服务路由和依赖边界。
- [x] 网关真实 HTTP 测试验证前缀改写、身份头清理和未知/内部路径拒绝。
- [x] 各服务添加最小启动/健康烟测。

## Task 3：部署与开发入口

- [x] 将迁移按所属服务归档，默认不执行；提供基础设施 Compose 和配置样例。
- [x] 提供 Windows 一键构建/启动/停止/烟测，记录 PID 和日志；Unix 提供构建命令说明。
- [x] 补 README、阶段状态及后续开发入口。

## Task 4：实际查看与验收

- [x] Maven verify 全部通过。
- [x] 在空闲端口实际启动七个进程，逐个健康及网关路由检查。
- [x] 提供可查看的本地入口与项目路径，保留用户查看所需进程。

## 执行记录

新建 D:/cloud/tradeflow，当前无既有 Git 项目或附着 worktree，直接初始化 feat/bootstrap，不建立多余 worktree。不复制或连接旧测试数据库。配置脚手架不单独编写镜像式测试，HTTP 与安全边界有实测。


执行完成：11 项自动化测试通过；七进程启动/停止/重启及烟测通过。独立评审无阻断问题。Docker/真实中间件联调属于后续批次，不作为本批已完成结果。
