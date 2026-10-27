# B13 统一构建与运行产物交付

2026-10-24。补齐现有Java/前端分开构建的交付流程，不修改交易业务、数据库或状态机，不执行测试、SSH、推送或远程CI。

## 构建入口

Windows在源码根目录执行`scripts/Build-Release.ps1`；项目运行中默认拒绝替换被占用文件。显式`-Restart`只停止项目记录且PID/创建时间匹配的Java/前端，按Maven跳过测试 → npm ci → 两端TypeScript/Vite → 打包顺序执行，全部成功后恢复原来运行的服务。构建失败不启动混合版本，保留日志及可能的partial排查。MySQL/Rabbit和其他进程不被停止。

Linux或CI依次执行`./mvnw -B -ntp package -Dmaven.test.skip=true`、`npm ci --no-audit --no-fund`、`npm run build`、`python scripts/Package-Release.py`。打包器自身不会重建产物，单独调用前应确保产物来自当前完整构建；它不保证旧产物与当前源码一致。

## 交付包

默认输出到Git忽略的.local/releases，使用UTC时间+随机后缀防止覆盖旧包。ZIP白名单包含七个启动JAR、两端index/assets、固定部署脚本及配方、必要说明；不遍历打包整个工作区，不包含.local、数据库、证据、密码、日志、源码或node_modules。文件及祖先存在符号链接/目录联接时拒绝，避免白名单被链接引向私有目录。

包内Compose仅把两个前端Dockerfile切换为dist交付版，project、服务名、端口、卷和权限不变。部署机器不需要JDK/Maven/Node，仍需要Docker、Python/cryptography和镜像联网能力；不是Docker离线镜像包或数据备份。

manifest.json与SHA256SUMS记录实际写入字节的哈希，外部.zip.sha256记录整个包；失败不将partial改名为成功包。完整性不是身份认证，无代码签名/供应链认证结论，也不代表业务或容器验收通过。JAR已压缩，ZIP采用STORE避免重复压缩开销；下载体积以实际生成结果为准。

## CI

原verify.yml扩展Java、固定Node24.15.0和npm锁构建，两端类型/生产构建后打包上传运行产物，保留7天，最低contents:read权限；自动化测试明确跳过，不生成部署秘密，不运行Compose或自动发布。工作流仅落盘，本轮没有推送或触发GitHub Actions，不能声称CI远端通过。

## 验证边界

本轮本地完整构建、打包、重启及文件生成结果见verification.md。没有自动化或业务/故障/并发/性能测试，没有Docker或Nginx运行验收；交付包不等于最终项目验收通过。后续仍需在用户允许后补完整业务联调、真实中间件并发与故障、容器部署及备份恢复演练。
