# B2 下单、预占与创建恢复

B2已实现幂等受理、报价前意图、成交/地址/购买件快照、多SKU原子预占、创建取消与恢复。本轮未执行业务请求、并发或故障测试，构建及健康不等于业务验收。

## 使用

本机启动仍为 `scripts/Start-Local.ps1`，会迁移用户、商品、订单三个独立库。商品V2追加预占头/明细及报价来源/元数据；订单V1/V2启用9张表，保留完整目标在docs/database-target。所有已发布迁移只追加，不能改写。

先按B1准备普通用户、商家商品及有流水的库存。用户登录后，向网关 `POST /api/v1/orders`，Header为Bearer Token和稳定Idempotency-Key：

```json
{"items":[{"skuId":"已有规格ID","quantity":2,"expectedUnitPriceCents":9900}],"address":{"receiverName":"收件人","receiverPhone":"13800000000","region":"浙江省杭州市","detailAddress":"示例地址"}}
```

返回202和稳定businessNo（ord_...）及queryUrl。**202只是已落地受理，不等于下单成功。** 请求中的重复SKU按同预期价格合并，单SKU最多99件、最多20个SKU；地址与数量/预期单价共同参与摘要，同键修改任何一项409。

- GET `/api/v1/orders/requests/{orderNo}`：本人创建进度，QUOTING、CREATING、PENDING_PAYMENT、CLOSING、CLOSED或明确拒绝；resultCode解释PRICE_CHANGED、INSUFFICIENT_STOCK等结果，recoveryState表示自动恢复或人工状态。
- GET `/api/v1/orders`：本人分页订单，列表不展开units。
- GET `/api/v1/orders/{orderNo}`：已落地本人订单及完整购买件编号。报价前没有虚构零金额订单，请用进度URL。
- POST `/api/v1/orders/{orderNo}/cancel`：B2仅QUOTING/CREATING取消，body reason最多255字。待支付取消在B2明确409，需B3支付关闭屏障后再开放。
- GET `/api/v1/admin/order-tasks`：平台分页查恢复任务。
- POST `/api/v1/admin/order-tasks/{taskNo}/retry`：平台带reason重启MANUAL_REVIEW原任务，不允许重新执行已经成功的业务。

内部商品接口 reserve/query/release 只供order独立凭据调用，不经网关。预占Command包含原订单/预占/报价/买家编号、原支付截止时间、有序SKU数量与共同指纹。报价返回不可变商品名称、商品ID和规格，旧B1报价缺少快照时拒绝复用，不能拿当前商品补造过去成交。

## 事务与故障边界

1. 受理事务保存唯一用户幂等键、固定订单/支付/预占号、恢复意图和任务。报价发生在提交之后，进程在报价前崩溃仍有记录可继续。
2. 报价成功后，订单短事务保存CREATING、所有商品行、购买件及地址，清空临时恢复地址材料。任一步失败整体回滚。
3. 库存先锁预占头，校验原报价来源/数量/有效期并唯一绑定订单；按SKU顺序锁库存，可用转预占与明细/流水一起提交。任一SKU不足整体回滚，再用新事务记录REJECTED，不能覆盖并发已成功或释放的事实。
4. RPC不确定时查询原预占号；已有RESERVED重试先返回成功，不能因报价后来过期否定已经成立的预占。订单仅在CREATING且未过期限时推进待支付。
5. 释放可以先到，建立RELEASED墓碑，迟到预占不能重新占用；重复释放不重复增加库存。库存自身不因过期就无条件释放。
6. 任务领取提交后才RPC，应用结果前验证owner、claim_version与有效租约；旧执行者不能覆盖新任务。网络副作用靠稳定业务号幂等，不靠租约宣称只执行一次。

任务最多10次/30分钟后进入MANUAL_REVIEW；只有平台带原因重启原任务，重置本轮预算并递增栅栏。已人工的任务在用户取消或创建超时后仍需平台重启才能继续释放，不能把CLOSING展示成已关闭。地址不进入任务payload、异常摘要或公开进度。

B2的待支付超时与取消尚未接支付屏障，不能安全宣称关闭完成；B3将扩展这一边界。B2现有单仓上限进一步约束可用+预占合计不超过10亿，正向调整也要给后续合法释放留足容量。

## 实际验证范围

跳过测试的clean package、三个库Flyway、七进程启动/健康、实际Swagger与元数据、只读静态审查。未执行下单、取消、支付、并发/故障演示；没有自动创建假商品或订单，没有SSH操作。

> B3已扩展待支付取消、支付关闭屏障与库存确认；本文B2范围是历史批次说明，当前行为见b3-development.md。
