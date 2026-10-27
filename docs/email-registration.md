# 邮箱注册与 QQ SMTP

新用户使用邮箱、6位验证码、密码和显示名称注册。邮箱去首尾空格、统一小写并作为登录名；发件方使用QQ邮箱，收件方可为常见ASCII邮箱。历史用户名账号继续登录，管理员初始化不经过公开注册，不伪造邮箱验证状态。

## 实际调用与事务

1. `POST /api/v1/auth/email-code`，正文 `{"email":"buyer@example.com"}`。网关开放路由并归入登录类共享限流。用户服务在Redis Lua内同时检查60秒冷却、每邮箱每小时5次、全局每小时100次；都允许才保存随机盐、验证码摘要及错误计数。Redis Key对邮箱做摘要，注册用途与其他用途隔离。
2. 使用 `spring-boot-starter-mail` 的 `JavaMailSender` 在数据库事务外同步调用QQ SMTP。返回 `retryAfterSeconds=60, expiresInSeconds=300`，只代表SMTP接受发送，不保证收件箱投递。错误或超时返回受控503，不输出底层认证异常，不自动重发，保留原验证码和冷却，防止实际已投递却重复发送。未配置邮件时在领取额度前返回503；历史登录仍可用。
3. `POST /api/v1/auth/register`，正文 `{"email":"buyer@example.com","code":"123456","password":"Example-Password1!","displayName":"买家"}`。先校验参数、在事务外进行BCrypt哈希，再通过Lua原子校验并消费验证码。5次错误作废；成功只允许一个并发请求消费。冷却结束后重发覆盖旧验证码并重置5分钟有效期。
4. `RegistrationAccountWriter.create()` 是独立Spring Bean的注解事务：唯一邮箱登录名、验证时间、USER角色一起提交。新增V8迁移扩大username至254字符、增加 `email_verified_at`；V1–V7不改写，历史账号该字段为空。原用户名唯一索引继续作为最终并发约束。
5. Redis和MySQL之间没有分布式事务。数据库失败时验证码已经消费，不恢复旧码；用户先尝试登录确认结果，确认未创建则冷却后重发。数据库写入不因Redis后置清理失败变成未知结果，因为校验已在写库前完成。
6. 登录接口仍为 `POST /api/v1/auth/login`，字段为 `username/password`，username可为邮箱或历史用户名；BCrypt校验、数据库登录限额、RS256 JWT（15分钟）、token_version及实时授权规则保持有效。

## 本机配置

仅用户服务需要 `TRADEFLOW_MAIL_USERNAME`（完整发件邮箱）和 `TRADEFLOW_MAIL_PASSWORD`（QQ SMTP授权码，不是QQ登录密码）。默认主机 `smtp.qq.com:465`，启用SSL、服务器身份校验和SMTP认证，设置连接/读写超时。IDEA在用户服务运行配置中注入这两个环境变量；不要提交凭据。

`Start-Local.ps1` 可读取 `.local/mail-env.json` 中的这两个白名单配置，文件必须限制为当前用户可读且不纳入Git。SMTP凭据不注入其他服务；用户服务新增共享Redis凭据。部署生成器仅从显式环境变量读取邮件配置，本批不运行部署或改现有开发库。

前端注册提供邮箱、验证码和发送按钮。发送期间禁止重复提交；按后端 `Retry-After` 或成功返回值倒计时，当前标签页刷新保留展示期限；切换邮箱清除旧验证码。倒计时只控制展示，真实限额由后端仲裁。网络或503不自动重发，提示检查收件箱和垃圾邮件。注册成功后使用邮箱密码登录。

验证码只存在Redis临时状态中，Redis故障拒绝发送/注册，不降级绕过验证。当前小时额度是从首次请求起算的固定窗口，不能声称任意滚动一小时内严格限额；阈值为当前项目默认值。正式邮件投递仍应结合提供商发送配额、域名与垃圾邮件策略评估。

## 验证入口与边界

- `mvnw.cmd -B -ntp -pl tradeflow-user,tradeflow-gateway -am test`：编排、SMTP本地协议收发、Mapper和网关回归；真实依赖测试在未提供fixture时显式跳过。
- `python scripts/verification/upgrade-mysql.py`：新建专用MySQL、Redis，执行V1–V8和其他服务迁移，再运行真实Redis并发/过期/错误次数、GreenMail本地SMTP、完整用户HTTP注册登录、历史登录和事务回滚，以及既有七服务应用边界回归。清理仅自建进程，保留证据目录。
- P0八服务fixture同步增加仅回环测试SMTP和邮箱注册种子；它不连接QQ，不使用本机邮件授权码。P0浏览器脚本同步更新登录字段标签。修改脚本不代表本批已经重跑完整八服务验收。
- `node scripts/verification/email-registration-browser.mjs`：独占Vite和Chrome无头浏览器，使用受控API替身验证验证码表单、倒计时、刷新、切换邮箱、429/503及手机布局；不把这项当真实后端端到端验收。
- QQ外网SMTP认证验证不等于收件箱实投；本地GreenMail只能证明SMTP协议和应用链路，不能代表QQ投递率。

## 2026-10-27 本次执行结果

- 全项目 `mvnw.cmd package` 成功：普通阶段151项通过、90项依赖fixture的测试显式跳过，日志 `.local/email-registration-package.log`。
- 隔离MySQL/Redis与七服务应用边界：62项测试全部通过、无跳过，证据 `.local/verification/upgrade-mysql-31u1yvvq/result.json`。其中用户完整应用11项、真实Redis验证码7项；账号和角色回滚、同验证码并发、历史登录和超过旧64字符的邮箱均有验证。自建MySQL/Redis已停止且端口关闭。
- 最终网关专项8项通过，包含匿名发送接口确实命中用户服务、归入login限流分类；日志 `.local/email-registration-gateway.log`。HTTP限流测试禁止客户端自动按429/503重发写请求，避免掩盖首次响应。
- 两套前端TypeScript检查和生产构建通过；Chrome注册表单5项受控API交互检查通过，手机截图已目视检查，证据 `.local/verification/email-browser-ec19e54d-f9d1-4e9d-9cd1-e29d350166f6/result.json`。测试Vite已停止。
- OpenAPI完整规范校验、修改的Python/PowerShell语法检查通过；P0新收件箱脚本已进行回环SMTP收发验证。未重跑八服务Nacos/MQ浏览器交易链路。
- QQ SMTP SSL/TLS连接与授权认证通过，未向真实邮箱发送测试邮件。授权码只存被Git忽略且限制当前用户读取的本机文件，已检查源码/配置模板/文档无该凭据。没有部署、没有修改现有开发数据库。
