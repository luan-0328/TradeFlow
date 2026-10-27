package com.tradeflow.order.infrastructure;
import java.util.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.tradeflow.contracts.RefundContracts.Allocation;
/** B6关键SQL只访问订单库；调用者先锁订单、商品行、购买件，再锁授权。 */
@Mapper public interface EntitlementMapper {
 /** 完成期限以数据库UTC当前时间固定七天判定，等号边界已过期。 */
 int eligible(@Param("orderNo") String orderNo);
 /** 同来源只比较首次规范摘要，避免分配重排产生假冲突。 */
 String digest(@Param("sourceNo") String sourceNo);
 /** 摘要只在首次授权事务写入。 */
 int digestSave(@Param("sourceNo") String sourceNo, @Param("allocationHash") String allocationHash);
 /** 分配一经保存不可改写；唯一购买件键拒绝重复输入。 */
 int allocation(@Param("sourceNo") String sourceNo, @Param("allocation") Allocation allocation);
 /** 结算读取首次不可变金额明细，禁止仅凭当前占用合计掩盖购买件错绑。 */
 List<Allocation> allocations(@Param("sourceNo") String sourceNo);
 /** 权威购买件仅在未占用且未退货时预留剩余额度。 */
 int reserve(@Param("id") long id, @Param("sourceNo") String sourceNo, @Param("reservedRefundCents") long reservedRefundCents, @Param("returnState") String returnState);
 /** 不可变分配与商品快照生成收据，SKU不采用售后自报值。 */
 List<Map<String,Object>> lines(@Param("sourceNo") String sourceNo);
 /** 每个来源只有一张固定收据。 */
 Map<String,Object> receipt(@Param("sourceNo") String sourceNo);
 /** 收据事实和入库任务在同一本地事务建立。 */
 int receive(@Param("receiptNo") String receiptNo, @Param("sourceNo") String sourceNo);
 /** 处置与收据同事务保存，首次记录后不得通过重试更改商品去向。 */
 int disposition(@Param("sourceNo") String sourceNo, @Param("disposition") com.tradeflow.contracts.RefundContracts.ReturnDisposition disposition);
 /** 规范排序读取原验收去向，重复登记和商品入库均比较这份不可变事实。 */
 List<com.tradeflow.contracts.RefundContracts.ReturnDisposition> dispositions(@Param("sourceNo") String sourceNo);
 /** 验收不可逆，即使支付失败也不释放实物事实。 */
 int receiveUnits(@Param("sourceNo") String sourceNo);
 /** 创建独立入库义务，不依赖资金任务。 */
 int task(@Param("sourceNo") String sourceNo);
 /** 领取先锁任务，提交后才允许RPC。 */
 Map<String,Object> due();
 /** 新租约递增围栏使旧工作者不能回写。 */
 int claim(@Param("sourceNo") String sourceNo);
 /** 围栏校验先锁任务，再锁订单，不反向等待其他任务。 */
 Map<String,Object> fence(@Param("sourceNo") String sourceNo, @Param("claimVersion") long claimVersion);
 /** 成功或预算耗尽均保留持久任务。 */
 int finish(@Param("sourceNo") String sourceNo, @Param("claimVersion") long claimVersion, @Param("state") String state, @Param("lastError") String lastError);
 /** 商品已幂等入库才登记APPLIED。 */
 int applied(@Param("sourceNo") String sourceNo);
 /** 资金成功与库存成功合取才完成授权；仅退款无需库存。 */
 int complete(@Param("sourceNo") String sourceNo);
 /** 平台查看脱敏任务快照。 */
 List<Map<String,Object>> list(@Param("size") int size, @Param("offset") long offset);
 /** 人工仅恢复原义务并递增围栏，不改变收据。 */
 int restart(@Param("sourceNo") String sourceNo);
}
