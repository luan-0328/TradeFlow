# 用户与商家升级接口

本模块实现 U1 账号、地址和站内通知、U2 入驻与商家管理、U4 平台用户库操作审计查询。U3 交易组、U4 资金与争议事实属于其他服务；通知不是交易终态的权威来源。本轮按要求只开发和补测试源码，未执行测试、Maven、迁移、服务启动或远程操作。

## 统一约定

响应沿用 `ApiResponse<T>`，下表为 `data` 内容。分页 `Page<T>={items,page,pageSize,total}`，page 默认1、pageSize默认20，范围1～100。主键对外为字符串，版本为非负整数。版本冲突409 `VERSION_CONFLICT`；无资源404，越权403。新增成功201，其余成功200。所有 `/api/v1` 接口均要求有效用户JWT，权限基于最新账号、全局USER/PLATFORM_ADMIN及每店权威成员职级复核。

商家编号为 `merchant_[a-z0-9_]{1,48}`，保留 `merchant_demo`。商家子路径要求 `X-Merchant-Id` 与路径一致，权限仅取最新 `AuthContext.merchantRoles[id]`：普通业务接受ADMIN/OPERATOR，资料与成员管理仅ADMIN；全局商家角色不再授权。平台跨商家管理仅走`/admin`专用入口。AuthContext新增`merchantRoles:Map<String,String>`，旧五参Java构造器保留但返回空映射，不推断授权。停用商家的有效历史成员继续返回，新经营命令另查经营状态。Capabilities同样新增merchantRoles，allowedActions只供导航，动作以当前商家职级为准。

## 外部接口

| 方法与路径（共同前缀 `/api/v1`） | 输入 | data / 规则 |
| --- | --- | --- |
| POST `/users/me/password` | `{oldPassword,newPassword}` | null；旧密码验证及新BCrypt均在写事务外；原哈希+tokenVersion CAS改密并递增撤销版本；全部旧会话包括当前会话失效，必须重新登录 |
| POST `/users/me/sessions/revoke-all` | 无body | null；撤销全部旧令牌，当前会话也失效 |
| GET `/users/me/addresses` | 无 | 地址数组，默认地址优先，再id降序 |
| GET `/users/me/addresses/{id}` | 无 | 地址对象，他人资源404 |
| POST `/users/me/addresses` | AddressInput，version必须0 | 新增地址对象，201 |
| PUT `/users/me/addresses/{id}` | AddressInput，version为当前版本 | 更新地址对象 |
| DELETE `/users/me/addresses/{id}` | query `version`必填 | null；软删除；删除默认地址后允许没有默认地址，不隐式设置他条地址 |
| GET `/users/me/notifications` | page,pageSize | Page&lt;Notification&gt;，id降序 |
| POST `/users/me/notifications/{id}/read` | 无body | null；重复已读幂等且保留首次时间，他人资源404 |
| POST `/merchant-applications` | `{merchantId,name,description}` | Application，201；商家编号全局唯一，拒绝后使用新编号重新申请 |
| GET `/merchant-applications` | page,pageSize | 本人 Page&lt;Application&gt; |
| GET `/admin/merchant-applications` | page,pageSize | 平台 Page&lt;Application&gt; |
| POST `/admin/merchant-applications/{id}/review` | `{approved:boolean,reason,version}` | Application；仅PENDING可审核；通过同事务建商家、申请人本店ADMIN成员，撤销申请人旧令牌并生成站内通知；拒绝也生成通知 |
| GET `/merchants` | 无 | Merchant数组，仅本人有效成员关系对应的商家，包含停用商家 |
| GET `/merchants/{id}` | X-Merchant-Id | Merchant |
| PATCH `/merchants/{id}` | X-Merchant-Id，`{name,description,version}` | Merchant；停用商家409 MERCHANT_DISABLED |
| GET `/merchants/{id}/members` | X-Merchant-Id | `[{userId,status,role,version}]`，包含历史停用成员 |
| PUT `/merchants/{id}/members` | X-Merchant-Id，`{userId,merchantId,status,reason,role?:ADMIN|OPERATOR}` | 成员数组；仅本商家，path与body商家ID须一致；维护本店职级和状态，role缺省OPERATOR，不赋予全局角色，撤销目标用户旧令牌 |
| GET `/admin/merchants` | page,pageSize | 平台 Page&lt;Merchant&gt;，包含停用商家 |
| PATCH `/admin/merchants/{id}/status` | `{status,reason,version}` | Merchant；平台启停不删除历史成员 |
| GET `/admin/audits` | page,pageSize，可选actorId,businessNo（各≤64） | 平台 Page&lt;Audit&gt;，id降序；范围仅用户库审计 |

已有 `POST /admin/merchant-members` 现在接受上述商家ID格式，要求商家真实存在；平台可在此跨商家管理成员。既有 `/admin/users/{id}/roles` 仍是全局角色管理入口，商家权限只取本店成员职级，全局MERCHANT_ADMIN/MERCHANT_OPERATOR仅保留历史兼容字典。

对象字段：

- AddressInput：`recipient`非空≤64，`phone`非空≤32，`region`≤255（旧输入缺省空，新页面必须填写），`address`非空≤500，`defaultAddress:boolean`，`version`≥0。地址响应 `{id,recipient,phone,region,address,defaultAddress,version}`。设置默认地址时锁定所属账号并清除其他默认地址，生成列唯一键作第二道并发约束；清除默认会递增该旧地址版本。
- Merchant：`{merchantId,name,description,status,version}`。name非空≤120，description非空≤500，status为ACTIVE/DISABLED。
- Application：`{id,userId,merchantId,name,description,status,version}`，列表额外返回可空reason。status为PENDING/APPROVED/REJECTED。申请和审核返回不包含reason，客户端可刷新列表获取审核原因。
- Notification：`{id,title,content,readAt?,createdAt}`，时间为UTC数据库时间；未已读时readAt为null或省略。
- Audit：`{id,actorType,actorId,action,businessNo,requestId,traceId,reason,changeSummary,result,createdAt}`。changeSummary是脱敏JSON文本；审计只追加。
- reason非空≤500；商家成员userId为1～19位正整数字符串。新密码须12～72个UTF-8字节，含大小写、数字和符号，密码DTO字符串化脱敏。主动改密/撤销会话与审计同一用户库短事务提交。

## 内部接口与调用授权

内部接口使用 `X-Caller-Service` 与 `X-Service-Token`，**不使用用户JWT替代服务凭据**。允许调用方：product、order、payment、aftersale、settlement。凭据分别读取 `tradeflow.internal.{caller}-token`，现有四服务配置不变；settlement新增环境变量 `TRADEFLOW_INTERNAL_SETTLEMENT_TOKEN`。凭据错误401 INVALID_SERVICE_CREDENTIAL，未知调用方403 SERVICE_FORBIDDEN。

| 方法路径 | data / 输入 |
| --- | --- |
| GET `/internal/v1/users/{id}/auth-context` | AuthContext：`{userId,status,roles,merchantIds,tokenVersion,merchantRoles}`；merchantRoles为商家ID到ADMIN/OPERATOR的映射，服务凭据新增settlement授权 |
| GET `/internal/v1/merchants/{id}` | Merchant；不存在404 MERCHANT_NOT_FOUND。停用商家返回200、status=DISABLED，调用服务按新经营/历史处置目的分别判断 |
| POST `/internal/v1/users/{id}/notifications` | body `{businessKey,title,content}`，成功200 data=null。businessKey为`[A-Za-z0-9_:.-]{1,120}`，title非空≤120，content非空≤1000；按`userId + caller:businessKey`幂等，重复键保留首次通知，不覆盖已读状态 |

关键通知应在源业务提交后的可靠任务/Outbox中投递，复用原businessKey重试。通知失败不回滚资金/订单事实；本模块没有假称已接入所有交易事件。入驻审核通知已在用户库同事务提交。

## V6迁移与账号授权

追加 `V6__user_merchant_upgrade.sql`；无旧表删除、无历史ID替换，新增表和每个字段均有中文COMMENT。仅为`merchant_demo`创建明确历史商家事实；其他无法证明归属的旧成员不猜测建商家，应升级前核验。

| 表 | 运行账号需要权限 | 数据影响 |
| --- | --- | --- |
| merchant | SELECT,INSERT,UPDATE | 新表，历史merchant_demo回填；资料和状态版本更新 |
| merchant_application | SELECT,INSERT,UPDATE | 新表，申请编号唯一，审核状态CAS |
| user_address | SELECT,INSERT,UPDATE | 新表，地址软删，禁止依赖DELETE |
| user_notification | SELECT,INSERT,UPDATE | 新表，唯一通知键、首次已读更新 |
| user_account/user_role/merchant_member | 沿用SELECT,INSERT,UPDATE | 改密与成员变更撤销版本；不删除历史成员 |
| authorization_guard | SELECT | 保留既有授权锁锚点 |
| audit_log | SELECT,INSERT | 平台分页读取、敏感变化追加，仍无UPDATE/DELETE |

迁移账号才拥有DDL。运行授权需由部署脚本按上表同步，未在本轮执行迁移或授权。V6之前的JAR不认识merchant经营状态；数据库虽为追加兼容，行为回退仍须停写审核，不能声称仅换旧JAR即可安全回滚。

待统一验证：新增测试源码与Mapper XML绑定、账号改密CAS与撤销竞争、真实MySQL默认地址并发/唯一约束、申请重复审核、审核事务回滚、双商家越权、停用商家历史处理、通知重复投递及已读、超过100条分页遍历、服务凭据矩阵。Mock测试不替代真实数据库事务/并发验收。

## 配套页面

`web-user/src/views/Account.vue` 提供地址新增编辑删除与默认、改密/全设备退出、通知分页与已读、入驻申请和本人申请/商家列表。建议路由 `/account`。改密与全会话撤销明确成功后调用共享logout；密码不进入命令日记摘要。

`web-admin/src/pages/Merchants.vue` 提供平台申请审核/商家分页启停，以及本人商家选择、资料和成员维护。建议平台路由 `/platform/merchants` 使用PLATFORM_USERS能力，商家路由 `/merchant/settings` 使用MERCHANT_PRODUCTS或专用能力。两页沿用现有设计、共享会话/useLoad/useCommand，账号和商家切换/离开页面隔离迟到结果。路由和菜单由主线统一接入，本模块未修改共享组件或应用壳。前端构建和浏览器验证均留统一测试阶段。

## 完整应用启动测试的独立fixture

六个既有 `*ApplicationTest` 现在明确要求 `TRADEFLOW_FULL_APPLICATION_FIXTURE=true`；普通Maven阶段跳过它们，由真实隔离fixture脚本显式选择执行，不能把默认跳过描述为全应用通过。fixture先迁移六个所属库，测试只连接 `127.0.0.1:${TRADEFLOW_UPGRADE_MYSQL_PORT}` 的 `trade_user`、`trade_product`、`trade_order`、`trade_payment`、`trade_aftersale` 和 `trade_payment_simulator`，使用专用临时实例root空口令，拒绝缺失或非法端口配置；不使用开发或远程业务数据。

JWT公私钥路径、监控/内部服务/通道凭据及回调密钥由fixture通过子进程环境提供。测试恢复真实DataSource和Mapper、保留应用安全链及参数校验，关闭Nacos、消息监听和SQL观测采样；迁移由fixture预先执行，应用内不再次运行Flyway。每个应用使用随机业务端口和独立随机管理端口，验证实际服务身份、当前阶段和管理readiness中的真实本库连接。

这些测试证明的是完整Spring应用、真实本库启动与HTTP边界；没有启动Nacos或Broker，不证明注册发现、MQ送达、七服务端到端或浏览器完整业务。执行结果由统一验证报告记录，源码就绪或普通测试阶段跳过均不代表此fixture已通过。

## P0 每商家职级与管理员恢复

追加V7迁移为merchant_member.role新增中文COMMENT和CHECK(ADMIN,OPERATOR)。仅历史有效成员且存在有效全局MERCHANT_ADMIN角色关联者回填ADMIN，其余OPERATOR；新增成员缺省OPERATOR，不再继承全局职级。本人商家列表每项追加role，单商家members列表追加role。

降级或停用成员、全局停用用户均保护该账号是最后一个可登录本店ADMIN的情形，返回409 LAST_MERCHANT_ADMIN；经营停用也保留该保护。所有授权写在同一authorization_guard锚点下串行，计数只含账号ACTIVE且成员ACTIVE的ADMIN，防止并发交叉移除。先给另一ACTIVE账号授ADMIN再撤销原账号；历史迁移后无管理员的店，可由平台POST /admin/merchant-members补授有效账号ADMIN恢复。给停用账号授ADMIN不会计入有效管理员，须先平台启用账号。

角色/成员修改撤销目标全部旧Token，审计与成员写同事务；用户停用同事务撤销并审计。运行账号需merchant_member.role列SELECT/INSERT/UPDATE权限（现有整表权限已覆盖），V7仅迁移账号运行。本节描述源码与迁移规则；验证与部署状态以主线独立报告为准，不将契约同步视为整体验收通过。

管理动作按所选商家职级细分：商品/SKU/图片写、库存调整与处置、运费规则写均须ADMIN；发货和售后业务允许OPERATOR；模拟结算只读允许ADMIN/OPERATOR。capabilities可从任一有效ADMIN成员聚合管理导航，但A店ADMIN不能据此管理B店。旧Java五参构造器保持编译兼容时merchantRoles为空，生产授权不能退回roles+merchantIds组合推断。更新已有成员时省略role也会归一为OPERATOR，客户端编辑应始终携带当前role，最后ADMIN的隐式降级仍返回409。
