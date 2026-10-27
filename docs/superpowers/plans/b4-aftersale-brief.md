# B4售后模块任务

只改D:/cloud/tradeflow/tradeflow-aftersale/**，完整原V1先归档docs/database-target/aftersale-full-V1.sql，再按本批拆分首次V1。报告写docs/superpowers/plans/b4-aftersale-report.md。AGENTS必须遵守，不再委派代理。用户要求不用测试：不测试、不构建、不运行服务/业务请求，不远程，不读取.local，不commit/push。根任务统一编译迁移启动。

实现未发货整单售后闭环，不支持金额仅退款或退货退款（B6）。固定RefundContracts与订单内部接口见2026-10-22-b4.md，先读该计划固定契约和全局约束段；root已实现订单端。所有响应ApiResponse。

用户POST /api/v1/aftersales，body {orderNo,type,reason}，type只接受UNSHIPPED_FULL；必需Idempotency-Key，作用域用户+操作+键，同键异参409。先查询最新用户身份、订单aftersale-intent核对本人，再持久CREATING售后和AUTHORIZATION任务，后续事务外申请订单Reserve(aftersaleNo,orderNo,userId,type)。as_+32hex售后号、rfd_+32hex首次退款号在首次受理事务固定；用户输入不接受金额，权威金额取订单授权。

用户GET /api/v1/aftersales与/{no}只本人。商家GET /api/v1/merchant/aftersales和/{no}过滤merchant_demo及最新有效商家成员权限，不输出买家地址。用户POST /api/v1/aftersales/{no}/cancel可CREATING/PENDING_REVIEW，先持久CANCELLING不可与审核批准同时赢，订单terminate允许先到墓碑，再CANCELLED。商家POST /api/v1/merchant/aftersales/{no}/review body {approved,reason}，可选必需Idempotency-Key，批准仅原金额，持久REFUND_PROCESSING再请求订单activate（授权EXECUTING后不可普通撤销）随后支付submit。拒绝先REJECTING终止授权后REJECTED。

持久任务至少领取30秒租约+claim_version、过期RUNNING回收，事务外RPC，应用结果短事务围栏，恢复预算30次转MANUAL_REVIEW。授权拒绝业务错误正确转REJECTED，未知先查原号，不假装失败释放。查询既有授权404可重放reserve；activate响应丢失查同授权；退款POST/GET使用固定refundNo，不因未知换号；GET不存在404 REFUND_NOT_FOUND才提交。成功后等待订单Authorization COMPLETED（表示退款和库存回补都完成），再售后COMPLETED。FAILED只记退款执行失败，不释放订单权益/履约锁，继续REFUND_PROCESSING+明确refundStatus=FAILED和lastError。商家POST /api/v1/merchant/aftersales/{no}/retry-refund必需Idempotency-Key+reason，仅确认旧FAILED且来源EXECUTING时，事务内生成并绑定新退款号和新尝试，未知不允许换号；旧领取必须被claim_version失效。人工恢复可做平台任务列表/原号retry，但必须最新PLATFORM_ADMIN+原因审计，不能假造退款终态。

独立trade_aftersale，账号变量TRADEFLOW_AFTERSALE_DB_USER/PASSWORD，迁移同前缀MIGRATION_USER/PASSWORD；独立DatabaseMigration入口com.tradeflow.aftersale.infrastructure.DatabaseMigration，业务Flyway关闭。单application.yml公共/dev/test/prod，dev13316数据源，JWT_PUBLIC_KEY/ISSUER，各服务凭据只TRADEFLOW_AFTERSALE_SERVICE_TOKEN，内部头caller=aftersale；配置USER_SERVICE_URL默认8081/ORDER_SERVICE_URL默认8083/PAYMENT_SERVICE_URL默认8084。复用common-web JwtSupport，最新身份auth-context防撤销；HTTP超时1s/3s，无隐式写重试，事务内RPC禁止。

字段与表中文COMMENT，业务方法/关键Mapper/恢复分支中文解释状态、事务、幂等、失败恢复；资金权限与权益归订单，不直接访问其它库。不要实现文件证据上传，本批申请只原因（后续B6补）。代码按application/api/infrastructure分层，避免把所有逻辑放一个巨类。

输出具体新增表/运行权限/env/API/body，供root集成脚本与文档；完整自审后简短回报。源码不断可变，编译由root最后统一处理。
