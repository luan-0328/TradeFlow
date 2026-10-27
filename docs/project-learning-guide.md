# TradeFlow 代码阅读导读

当前已具备学习、演示和简历讲解所需的业务闭环：用户与商家、商品库存、分商家交易、模拟支付、履约、售后、模拟结算、对账及异常恢复。先理解现有实现，暂不继续堆组件。

这不代表工程整体已满足正式交付标准。CI、完整部署拓扑、七库备份等已确认缺口见[工程完整性复核](project-engineering-readiness.md)；学习业务与补齐工程交付可以分阶段进行。

本地验收见 [P0报告](integration/2026-10-26-p0.md)。支付和出款仍是模拟通道；生产部署、容量、高可用和完整灾备没有在该报告中宣称完成。测试实例已清理，报告通过不代表服务当前正在运行。部分配置保留历史 `B8_OBSERVABILITY_UNTESTED` 阶段标识，不能据此判断当前测试结果。

## 第一天：先读通一条交易

建议先掌握前三步，不要求一天读完所有源码。

1. 阅读 [架构](architecture.md) 的服务边界和调用图，能解释八个 JVM、七个逻辑库，以及 common / contracts / common-web 为什么不单独运行。
2. 顺着登录读取 UserController → AccountService → UserSecurity / JwtSupport，再读 ApplicationExceptionHandler。分清业务代码、Spring Security 认证、JWT 签名校验与业务权限。新用户[邮箱注册](email-registration.md)另走 EmailRegistrationService → EmailCodeStore / RegistrationMailer → RegistrationAccountWriter：Redis验证原子消费，SMTP和BCrypt在事务外，账号与普通角色通过独立Bean的注解事务提交。
3. 追踪一次“选择商品 → 创建订单 → 预占库存 → 支付 → 库存确认 → 发货 → 收货”。先画正常路径，再标出每次数据库提交和远程调用，最后查看失败时留下的持久任务。

## 代码入口

以下路径均相对项目根目录。每个主题先看 Controller 的接口，再看用例和 Mapper/XML；不要从工具类逐个背起。

| 主题 | 主要入口 | 读完应能回答 |
| --- | --- | --- |
| 登录与权限 | `tradeflow-user/.../api/UserController.java`、`application/AccountService.java`、`infrastructure/UserSecurity.java`；公共 `JwtSupport`、`MerchantSelection` | JWT谁签发、谁验签？验签成功为什么还要查账号版本和本店成员权限？ |
| 全局异常 | `tradeflow-common-web/.../web/ApplicationExceptionHandler.java`、各服务 `api/DatabaseErrors.java` | 业务代码抛什么？400/401/403/409/503怎样区分？哪些错误发生在安全过滤链？ |
| 商品与库存 | `tradeflow-product/.../application/ProductService.java`、`QuoteService.java`、`ReservationService.java`、`RestockService.java` | 为什么公开缓存不参与权威报价？预占、确认、释放各改变什么余额？ |
| 下单与跨店交易 | `tradeflow-order/.../application/OrderService.java`、`TradeGroupService.java`、`CreateOrderWorker.java` | 幂等键绑定了什么？一个子单失败会影响其他子单吗？未知结果为何不能换键？ |
| 支付与履约 | `tradeflow-payment/.../application/PaymentService.java`；订单 `OrderPaymentService.java`、`FulfillmentService.java` | 如何区分支付已受理、成功和结果未知？支付成功后库存确认失败怎么办？ |
| 售后与退款 | `tradeflow-aftersale/.../application/AftersaleService.java`、`ReturnService.java`、`AftersaleWorker.java`；支付 `RefundService.java`；订单 `EntitlementService.java` | 商品权益与资金额度为何分两层？先补偿再退货最多还能退多少？ |
| 可靠消息 | `tradeflow-common-web/.../messaging/MessageOutbox.java`、`MessageReceiver.java`、`MessageOperations.java`、`MessageStore.java` | 本地提交后崩溃怎么办？ACK丢失如何去重？租约围栏防什么？SENT为何不等于所有消费者成功？ |
| 模拟结算 | `tradeflow-settlement/.../SettlementService.java`、`SettlementRules.java`、`SettlementMapper.java` | 结算后退款如何冲正？负债怎样从后批抵扣？为什么不能称为真实出款？ |
| 前端恢复 | `web-shared/src/client.ts`、命令日记相关代码、`web-user/src/pages`、`web-admin/src/pages/PendingTasks.vue` | 网络失败后如何保留原键？商家或账号切换后怎样阻止旧响应覆盖新页面？ |

`...` 表示对应模块的 `src/main/java/com/tradeflow/<包名>`：例如订单为 `order`，模拟通道为 `paymentsimulator`。公共 Web 包直接为 `com/tradeflow/web` 和 `com/tradeflow/messaging`。

## 用一个例子贯穿售后和结算

本轮真实链路使用过如下例子，可结合测试和账本理解：

- 商品100元、运费5元，实付105元；10%费率下模拟结算94.50元。
- 仅退款补偿20元，不回补库存；本件还可退款85元。
- 随后退货退85元，按验收处置实物；累计退款105元，不超过实付。
- 已结算的94.50元形成商家负债，后续同额应付款先抵扣，模拟出款为0。

先解释业务数字，再去找购买件额度、退款成功事实、结算冲正和库存流水，不要只背接口名。

## 读测试的方法

- 用户 `UserApplicationTest`：真实HTTP每店角色、撤销旧会话、最后管理员保护。
- 商品 `ReservationReliabilityMysqlTest` / `ProductUpgradeMysqlTest`：真实数据库约束、幂等及回滚。
- 订单 `OrderUpgradeMysqlTest`：交易组并发、原键和金额分摊。
- 支付 `MessagingRecoveryMysqlTest`：真实MQ故障、ACK丢失、原事件补投及Broker重启。
- 结算 `SettlementBusinessMySqlTest`：退款冲正和后批抵扣。
- `scripts/verification/p0-acceptance.py`、`p0-browser.mjs`：实际跨服务与浏览器场景。

测试中的替身只证明对应层次，不能当真实服务调用。普通 Maven 命令明确跳过依赖用例，真实依赖测试必须通过各自新建隔离环境的脚本运行。

## 明天需要运行时

按根 README 和 [Nacos运行手册](runbooks/nacos-discovery.md)准备依赖，优先本地。普通启动不会自动创建Nacos，必须先有可达的注册中心；不要因为开始学习而连接旧服务器。历史fixture端口和临时账号不能当长期开发配置使用。前端同源请求经Vite代理到网关；网关只公开业务路由。

目前项目尚无首个Git提交，已有源码显示为未跟踪文件。开始自己改动前应保存可回溯基线，并核对 `.local`、私钥和凭据都被忽略；本次检查没有代为提交或推送。

掌握标准：能画出主链路，指出每个本地事务的边界，解释关键表的约束，并演示一次失败后的原号恢复。达到这些，比再加一个框架更有助于面试。

## 注解事务对照入口

2026-10-27按学习需求，仅将订单任务领取改为独立的 `application/OrderTaskClaimService.claimNext`，使用 `@Service` 与 `@Transactional`。Worker的 `scan()` 本身不加事务，通过 `claimNextTask()` 的有限重试调用注入的服务代理；领取提交后才执行远程业务。领取查询、30秒租约、版本围栏、预算、错误码及3次锁竞争重试保持原协议。其余精确片段继续使用 `OrderTransactions`，没有全项目替换事务方式。

注解方法必须经Spring代理调用；同类内部调用或自己 `new` 一个服务对象不能当成事务已生效。`OrderTaskClaimServiceTest`专门使用真实事务代理验证边界和重试，真实数据库回滚与提交另见 `OrderUpgradeMysqlTest`。学习时先看这两个类，再对照剩余的 `tx.write` 片段，两种方式都依赖Spring事务管理器。

本次修改实际验证：订单及共享依赖测试通过，新增6项代理/重试边界测试全部通过；全新隔离MySQL套件46项全部通过、失败/错误/跳过均0，其中新增2项验证领取实际提交和读回失败时实际回滚。日志分别为 `.local/annotation-task-claim-tests.log` 和 `.local/annotation-task-claim-mysql.log`，隔离数据库进程已停止。没有部署或连接服务器。

### 第二批：容易明确事务范围的本地方法

随后将 `UserWorkspaceService` 的9个写入口改为public方法注解事务：地址保存/删除、通知已读/投递、入驻申请/审核、商家资料/经营状态/成员维护。该类只访问用户库，原锁顺序、版本校验、授权、会话撤销与审计保留；控制器通过Spring Bean代理调用，内部私有辅助方法参与入口事务。去掉该类的TransactionTemplate字段及事务管理器构造参数，补充关键查询/更新的中文说明。

`SettlementService.reconcile` 与 `audit` 两个仅访问结算库的方法也改用注解事务；远程授权仍在控制器调用前完成，结算导入/出款模拟的跨服务多阶段run流程保留编程式短事务。本批共转换11个方法，未给整个Worker或跨服务编排加事务，也未改动默认传播方式及数据库迁移。

本批验证：用户与结算普通测试通过；全新隔离MySQL/应用HTTP套件50项全部执行通过、0失败/错误/跳过，数据库进程已停止。新增真实失败回滚验证覆盖清除旧默认后插入失败、入驻审核末步审计失败、本地结算核对末步写失败，并验证注解审计/核对提交；原结算远程调用不在事务内的断言仍通过。证据为 `.local/annotation-local-services-tests.log` 和 `.local/annotation-local-services-mysql.log`。手工构建对象的测试已改为装配Spring事务代理，避免裸new对象使注解无效。
