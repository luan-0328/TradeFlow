package com.tradeflow.order.infrastructure;

import com.tradeflow.order.domain.OrderRows.*;
import com.tradeflow.order.api.OrderModels.UnitView;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 仅订单库SQL；快照/幂等/任务同事务，任何Mapper方法都不调用远程。 */
@Mapper
public interface OrderMapper {


    /** 用户+操作+键唯一，受理即固定orderNo，不让并发请求产生多个报价/预占编号。 */
    int claimRequest(@Param("userId") String userId, @Param("idempotencyKey") String idempotencyKey, @Param("requestHash") String requestHash, @Param("orderNo") String orderNo);
    /** 只在受理短事务持有幂等行锁，不携带该锁调用报价。 */
    RequestRow lockRequest(@Param("userId") String userId, @Param("idempotencyKey") String idempotencyKey);
    /** 按当前买家和固定操作查原键，不跨用户查找；普通一致读查不到不代表迟到请求不会提交。 */
    RequestRow requestByKey(@Param("userId") String userId, @Param("idempotencyKey") String idempotencyKey);
    /** 抢先终止尚未受理的原键，与幂等行同事务保存无地址、无任务的取消墓碑。 */
    int sealedIntent(@Param("orderNo") String orderNo, @Param("userId") String userId, @Param("paymentNo") String paymentNo, @Param("reservationNo") String reservationNo, @Param("requestHash") String requestHash);
    /** 只有应用层确认商家归属与履约状态后读取，禁止用于列表或跨服务导出。 */
    com.tradeflow.order.api.OrderModels.ShippingAddress shippingAddress(@Param("orderNo") String orderNo);
    /** 结果状态与订单本地推进同事务，快照不保存地址或凭据。 */
    int requestResult(@Param("orderNo") String orderNo, @Param("state") String state, @Param("resultCode") String resultCode);
    /** 报价前先持久化恢复材料，不创建伪造零金额订单。 */
    int intent(@Param("orderNo") String orderNo, @Param("userId") String userId, @Param("paymentNo") String paymentNo, @Param("reservationNo") String reservationNo, @Param("requestHash") String requestHash, @Param("requestPayload") String requestPayload);
    /** 本人进度查询使用普通一致读，不阻塞任务执行。 */
    Intent intentByNo(@Param("orderNo") String orderNo);
    /** 工作任务先锁租约行，再锁意图和订单；取消只锁意图/订单，不反向等待任务。 */
    Intent lockIntent(@Param("orderNo") String orderNo);
    /** 订单快照已落地后清空临时地址材料；明确拒绝/取消也清空，不无限重复保存PII。 */
    int intentState(@Param("orderNo") String orderNo, @Param("state") String state, @Param("resultCode") String resultCode);
    /** 创建订单、明细、购买件、地址、状态日志必须一起提交。 */
    int createOrder(Map<String,Object> row);
    /** 商品名称/规格取不可变报价，不能从当前商品表跨库读取补造。 */
    int item(Map<String,Object> row);
    /** 每件固定成交价与unitNo，为后续按件退款权益提供真实来源，最多99件/行。 */
    int units(@Param("orderItemId") String orderItemId, @Param("paidAmountCents") long paidAmountCents, @Param("units") List<Integer> units);
    /** 地址只能在下单时写入一次，重试不能替换收货人。 */
    int address(@Param("orderNo") String orderNo, @Param("receiverName") String receiverName, @Param("receiverPhone") String receiverPhone, @Param("region") String region, @Param("detailAddress") String detailAddress);
    /** 详情读取不代表授权，应用必须匹配买家编号。 */
    Header order(@Param("orderNo") String orderNo);
    /** 用于条件迁移前重读权威状态；先取得创建意图锁保持一致顺序。 */
    Header lockOrder(@Param("orderNo") String orderNo);
    /** 预占成功仅能推进仍在创建且未过截止时间的订单，关闭不能被迟到成功逆转。 */
    int pending(@Param("orderNo") String orderNo);
    /** CREATING未获得支付授权，关闭先持久RELEASE决策，再在事务外释放。 */
    int closing(@Param("orderNo") String orderNo, @Param("reason") String reason);
    /** 只有库存释放或明确拒绝事实已确认才完成关闭；不能用RPC超时关闭。 */
    int closed(@Param("orderNo") String orderNo);
    /** 明细稳定排序，返回内部引用ID的字符串表示。 */
    List<Item> items(@Param("orderNo") String orderNo);
    /** 购买件只读展示，后续售后必须经过权益授权事务变更。 */
    List<UnitView> unitsByItem(@Param("orderItemId") String orderItemId);
    /** 买家归属过滤在SQL中执行，禁止通过外部merchant/user请求头扩大范围。 */
    List<Header> list(@Param("userId") String userId, @Param("size") int size, @Param("offset") long offset);
    /** 分页总数使用与列表相同的买家归属条件。 */
    long count(@Param("userId") String userId);
    /** 状态日志仅追加，与真正状态变化同事务，不记网络尝试或虚构成功。 */
    int log(@Param("orderNo") String orderNo, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus, @Param("operation") String operation, @Param("actorId") String actorId, @Param("requestId") String requestId, @Param("reason") String reason);
    /** 任务与受理意图同事务；恢复参数只需业务号，不将地址放入任务错误或日志。 */
    int task(@Param("taskNo") String taskNo, @Param("businessNo") String businessNo);
    /** 超时扫描只针对创建中订单，待支付超时须等待B3支付屏障，不能自行释放库存。 */
    List<String> expiredCreating();
    /** 待支付超时只能请求支付关闭屏障，不能直接宣布未支付并释放。 */
    List<String> expiredPending();
    /** 与成功通知竞争同一订单行；关闭先赢则以后只走关闭/迟到支付分支。 */
    int closingPaidWindow(@Param("orderNo") String orderNo, @Param("reason") String reason);
    /** 已成功支付被订单接受才写COMMIT，重复通知不重复推进或确认库存。 */
    int paid(@Param("orderNo") String orderNo, @Param("paidAt") Instant paidAt);
    /** 库存确认成功后才能标记确认；后续发货必须检查此字段。 */
    int stockConfirmed(@Param("orderNo") String orderNo);
    /** 支付终态关闭证据仅对CLOSING有效，迟到支付标记不能被旧关闭结果覆盖。 */
    int channelClosed(@Param("orderNo") String orderNo);
    /** 不可再成功的通道FAILED/CLOSED允许待支付订单进入释放阶段。 */
    int failedPayment(@Param("orderNo") String orderNo, @Param("reason") String reason);
    /** 关闭先赢后的真实成功不能改成PAID，也不能谎报已退款，保留异常资金义务。 */
    int latePayment(@Param("orderNo") String orderNo);
    /** 合法同号通知必须比较事实摘要，未验真请求不会写入该表。 */
    String paymentFactHash(@Param("eventId") String eventId);
    /** 通知证据与业务状态/后续任务同事务，仅追加，调用方不能更新既有事实。 */
    int paymentFact(@Param("eventId") String eventId, @Param("paymentNo") String paymentNo, @Param("orderNo") String orderNo, @Param("payloadHash") String payloadHash, @Param("status") String status, @Param("channelVersion") long channelVersion);
    /** 取消及人工恢复审计与本地决策同事务，只存关联号与状态摘要。 */
    int audit(@Param("actorType") String actorType, @Param("actorId") String actorId, @Param("action") String action, @Param("businessNo") String businessNo, @Param("requestId") String requestId, @Param("traceId") String traceId, @Param("reason") String reason, @Param("changeSummary") String changeSummary);
    /** 已持有订单业务事务时读取最新真实版本，供同事务审计事件标记聚合版本。 */
    long currentVersion(@Param("orderNo") String orderNo);
}
