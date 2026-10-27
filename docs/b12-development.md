# B12 前端独立容器交付

2026-10-24。为现有独立部署Compose增加web-user、web-admin两个静态容器，补齐前端镜像、SPA路由和同源API代理。未修改业务服务、数据库、旧迁移或部署身份；现有project仍为tradeflow-b8，避免更名误建另一组数据卷。

## 构建和启动

全新环境先按deploy/README.application.md完成Java构建及Prepare-Deployment.py准备，再执行原有完整Compose命令。其默认服务现在包含两个前端；容器会在Gateway健康后启动。

已有这套独立部署、仅更新前端时，在仓库根目录执行：

```powershell
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml build web-user web-admin
docker compose -p tradeflow-b8 -f deploy/compose.application.yaml up -d --no-deps web-user web-admin
```

第二条命令以原Gateway及其数据库/迁移依赖已正常运行为前提。没有既有部署时不要用它绕过依赖启动流程；不要与早期compose.yaml合并，也不要把容器编排命令当作已在本机执行。

| 入口 | 宿主地址 | 容器端口 |
| --- | --- | --- |
| 用户端 | http://127.0.0.1:18088/ | web-user:8080 |
| 商家/平台后台 | http://127.0.0.1:18089/ | web-admin:8080 |
| 网关 | 原有http://127.0.0.1:8080/ | gateway:8080 |

本机Vite开发入口仍为5173/5174，两种启动方式互相独立；本轮没有启动18088/18089。容器部署使用自己的账号、密钥和数据，不复用.local开发实例。两端不同端口构成不同origin，浏览器会话及命令日记不混用；更换域名/端口时旧浏览器日记不会自动迁移，不能因此认定原交易不存在。

## 镜像与运行边界

deploy/Dockerfile.web使用Node24.15.0-alpine在构建阶段npm ci及两端类型检查/构建，两个最终target只复制各自dist。运行镜像Nginx1.28.3-alpine，二者固定Docker Hub标签对应的多架构摘要；标签与摘要元信息已实际读取，不代表镜像在本机已拉取或完成漏洞扫描。

前端Dockerfile专用ignore白名单只发送根manifest/锁、三套前端源码及Nginx配置。.local、后端、JAR和宿主node_modules/dist不进入前端上下文。根.dockerignore同时阻止后端构建发送本机秘密，保留后端Dockerfile需要的各模块最终JAR。npm锁中下载URL沿用现有npmmirror，Linux可选native依赖也在锁中；构建环境需能访问锁中源和固定镜像仓库。

Nginx以nginx用户监听8080，根文件系统只读、去除全部capability、禁止提升权限，临时文件和PID写入32MiB的/tmp tmpfs。前端不挂载JWT私钥、数据库、内部服务、管理或引导秘密。Java容器仍沿用B8既有权限边界，本批不声称所有容器已完成非root改造。

## 转发与页面策略

- 仅/api/v1/转发到Compose gateway:8080，保留原URI、Authorization和Idempotency-Key，清理外部伪造的内部调用身份头。Docker DNS动态解析，应对网关重建IP变化。
- proxy_next_upstream off，不自动重投未知交易写请求；错误状态和信封原样保留，不用HTML覆盖API错误。网关路由和业务权限仍由后端控制。
- /api其他路径、内部通道/回调/Actuator/Swagger等路径不代理。/assets和缺失脚本/样式返回404；正常Vue历史页面路由回退index.html。
- 响应默认no-store，包括index和API；本版不启用长缓存，减少版本切换时旧入口/私有资料缓存的复杂度。构建资源无需更改才能后续独立优化缓存。
- 最大请求6MiB覆盖5MiB证据加multipart封装；文件类型、实际尺寸、归属与绑定仍由售后服务检查。
- 访问日志只含方法、无参数URI、状态和耗时，不记认证头、原键或正文。CSP限制脚本/连接为同源，允许Element Plus内联样式及本地Blob证据；页面禁止被嵌入。
- /healthz只证明Nginx存活，不能证明网关、数据库和交易可用。网关启动健康依赖也不等于完整业务验收。

默认仅回环监听。跨机器访问需另行部署受控HTTPS入口及两个独立origin，配置网络权限与证书；普通远程HTTP不满足浏览器Web Crypto/Web Locks安全上下文要求，不能简单改成公网监听后就声称可正式使用。当前没有TLS终结或公网部署。

## 本轮结果与待验证

两端TypeScript/Vite本机构建成功；Compose JSON/YAML子集已解析，服务名和依赖静态核对完成。发现本机没有docker/nginx可执行文件，未执行镜像构建、docker compose config、nginx -t、容器启动或业务请求，未安装Docker或SSH到其他服务器替代验收。不能声称Linux镜像、只读运行、Nginx路由/CSP或完整部署已经验证通过。

后续真实环境需核对镜像构建、非root与只读启动、SPA深链接、静态404、JWT和原键透传、上传限制/CSP及网关重建；完整交易、权限、并发、故障、备份恢复和性能仍待用户允许后验收。
