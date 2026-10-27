> 当前库存处置已在2026-10-25加固中升级：验收认可不再等于可售，新增逐件处置及不可售余额，见[交付记录](project-hardening.md)。以下B6说明为历史批次范围。

# B6 金额仅退款与退货退款

本批沿用已确认的开发资料03/04/06，实现已发货逐件金额补偿与退货退款。按用户要求未运行测试、业务演示、模拟消息、并发或故障验证；下列说明是实际代码行为与边界，不能当作业务验收报告。

## 业务规则

| 类型 | 金额与前提 | 审核后 | 库存 |
| --- | --- | --- | --- |
| UNSHIPPED_FULL | PAID未发货，全部件和原实付 | 退款执行 | 成功退款后独立取消销售回补，保留原CONFIRMED预占 |
| REFUND_ONLY | 已发货或收货后7天内；每件1分至剩余实付 | 退款执行 | 不入库，不改变发货收货事实 |
| RETURN_REFUND | 同上；所选件全部剩余实付，不能退已补偿金额 | 等待退货、一次验收 | 验收通过即形成独立入库义务，不依赖退款成功 |

例如某件实付10000分，先REFUND_ONLY补偿2000分，成功后该件剩余8000分；后续RETURN_REFUND只能申请8000分。已全部退完金额的件不能再通过退款型售后退货，实物需求人工处理。unitNo是账务归属序号，不是商品防伪码，实物身份由商家结合说明、图片和验收确认。

同件同一时刻只能有一个有效权益预留；不同件可分别售后。商家仅同意原申请金额或拒绝，变更金额需要用户重新提交。订单完成时间及七天边界使用数据库UTC时间；申请后期限届满不撤回已建立的退款义务。

## 用户与商家接口

统一经网关 http://localhost:8080 访问，携带Bearer令牌。所有敏感用例在本地事务前查询最新账号、撤销版本及商家成员关系。

| 方法与路径 | 内容 |
| --- | --- |
| POST /api/v1/aftersales/preview | 本人逐件剩余实付预览，不占权益 |
| POST /api/v1/aftersales | 原键幂等创建，保存分配/证据与授权任务 |
| GET /api/v1/aftersales/{no} | 保留原售后状态查询 |
| GET /api/v1/aftersales/{no}/materials | 原申请分配、证据键、物流、验收、退货截止与收据 |
| POST /api/v1/aftersales/{no}/return-shipment | 七天内本人一次登记退货物流 |
| GET /api/v1/merchant/aftersales/{no}/materials | 所属商家查询同一套材料 |
| POST /api/v1/merchant/aftersales/{no}/review | approved与reason，批准原金额或拒绝 |
| POST /api/v1/merchant/aftersales/{no}/return-inspection | accepted、reason、evidenceKeys，一次整张验收 |
| POST /api/v1/merchant/aftersales/{no}/retry-refund | 仅权威旧尝试明确FAILED时新退款号重试 |
| POST /api/v1/evidence-files | multipart字段file，返回受控objectKey |
| GET /api/v1/evidence-files/{key} | 本人、所属商家或平台受控下载 |

创建、审核、提交退货物流、验收和退款重试要求Idempotency-Key；相同键同规范参数返回原资源，不同参数409。上传每次产生独立随机文件键，无资金副作用，重复未绑定文件由清理任务处理。不能因为HTTP超时换申请号或退款号。

预览请求示例（文档示例，未发送到服务）：

```json
{"orderNo":"ord_...","type":"REFUND_ONLY","allocations":[]}
```

空分配预览当前可申请购买件的全部剩余金额，返回`units`、`allocations`、`amountCents`。客户端选择件并将金额改为协商金额后提交。主键使用字符串，金额整数分；最大1980件、总额100000000分。预览不保证实际申请时仍有权益。

```json
{
  "orderNo":"ord_...",
  "type":"REFUND_ONLY",
  "reason":"外壳轻微划痕，双方协商保留商品补偿20元",
  "allocations":[{"orderItemId":"123","unitNo":1,"amountCents":2000}],
  "evidenceKeys":[]
}
```

`REFUND_ONLY`和`RETURN_REFUND`创建必须带用户确认的逐件分配。`UNSHIPPED_FULL`兼容原空分配请求；显式传入时必须覆盖全部购买件及原实付。分配按商品行/件序规范排序，重排不会改变幂等摘要，同件重复直接拒绝。

审核通过的退货进入WAITING_RETURN并记录七天物流截止。提交`{"carrier":"承运商","trackingNo":"单号"}`后进入RETURN_IN_TRANSIT；截止与提交使用同一售后行锁仲裁，截止后即使扫描未运行也不能提交。已寄出不自动判定丢失、退款或取消。

商家验收`{"accepted":true,"reason":"全部商品验收为可再售","evidenceKeys":[]}`，先保存RETURN_RECEIVED及固定`RET:{aftersaleNo}`，后台交接订单后进入REFUND_PROCESSING。拒收需`accepted:false`、原因及至少一张验收图片，终止原权益后RETURN_REJECTED；返还用户的物流由人工处置，系统不声称自动完成返还物流。

## 证据存储

本地目录默认项目根目录下`.local/evidence`，可通过TRADEFLOW_EVIDENCE_DIRECTORY指定持久目录；生产/容器需挂载持久卷并随元数据备份。文件名服务端随机生成，拒绝路径穿越及符号链接；按文件头识别PNG/JPEG/WebP，单文件最多5MB，不信任文件名或客户端Content-Type，不解压图片。

每张售后申请与验收合计最多6张；退货申请初始最多5张，为后续拒收验收保留至少一个位置。证据绑定核验上传者及状态，持有售后锁后按文件键排序加锁；一个文件只能绑定一次。下载`no-store`、`nosniff`，校验文件尺寸和摘要。未绑定一天后先提交TOMBSTONED再删文件，失败按原键重试；BOUND文件不会自动清理。写盘成功但数据库提交不可确认时保留文件，未登记文件需人工核对；不能因为数据库结果未知删除可能已登记的证据。

## 权益、资金与实物恢复

- 订单短事务按订单→商品行→购买件→授权顺序锁定，保存分配摘要及不可变明细、逐件预留。商品行汇总从购买件重算，不会清除其他售后的预留。
- 退款成功先核验明细摘要、件数、每件预留及金额合计，然后预留转成功并绑定唯一settled_refund_no；不同事件号也不能重复结算。仅退款此时完成订单授权，不操作库存。
- 退货验收在订单保存RECEIVED及独立return_stock_task，之后才允许激活退款；验收后普通终止被禁止。退款FAILED保留权益与退款义务，入库任务照常执行。
- 商品事务外以product身份读取订单权威验收收据，不接受售后自报SKU数量；商品本地事务锁原销售预占，再按SKU顺序回补。原购买件唯一约束防跨收据重复入库，按SKU累计回补不超原销售，且与未发货整单回补互斥。
- 退款成功与入库APPLIED共同成立，订单授权才COMPLETED；售后查询权威结果后同事务发布AftersaleCompleted。付款与来源双额度沿用B4，支付只接受已激活权威授权。
- RPC均在业务事务外；任务短事务领取、30秒租约与claim_version围栏，独立入库最多30次自动尝试后转人工；人工恢复沿用原收据，不改数量或伪造成功。

终止先到时订单保存`TOMBSTONE + TERMINATED`，阻止迟到创建复活来源；终止任务只按订单、来源及终态确认，不错误要求未知申请的金额分配。

平台入口：`GET /api/v1/admin/return-stock-tasks`，`POST /api/v1/admin/return-stock-tasks/{source}/retry`请求`{"reason":"已确认依赖恢复，按原收据继续"}`；最新平台身份、人工任务条件及原因审计共同约束。售后AUTHORIZATION/RECEIVE/REFUND/TERMINATE恢复仍使用原售后任务管理接口；资金矛盾冻结与原有消息恢复见B4/B5。

## 迁移与部署边界

新增商品V4、订单V6、售后V3，旧已执行迁移不修改。新增10张表：商品2、订单3、售后5；当前六库业务表7/15/23/14/9/15，共83张，另六张Flyway历史表。数量不等于目标字典覆盖度。

分配、物流、验收、证据关联、商品收据与流水均只授予SELECT/INSERT；证据元数据只额外UPDATE(state)，订单收据只额外UPDATE(status)。订单新增核验product内部凭据，启动脚本只传必要的服务凭据。内部预览/收据/入库接口不公开网关路由。

重启使用根目录`scripts/Start-Local.ps1 -Build`，跳过测试；停止仅关闭Java，保留MySQL、RabbitMQ和证据目录。Docker及完整故障、并发、资金正确性验收没有运行，不把编译、迁移、健康UP或静态审查当作对应验收通过。对账仍在B7。
