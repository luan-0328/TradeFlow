package com.tradeflow.product.infrastructure;
import com.tradeflow.contracts.RefundContracts.*;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 所有读写均在原预占头锁内，收据唯一及购买件唯一约束防跨收据重复回补。 */
@Mapper
public interface ReturnRestockMapper {
    /** 原收据绑定不可改变，重复调用必须核对完整明细。 */
    RestockView find(@Param("receiptNo") String receiptNo);
    /** 返回固定排序的购买件身份，用于重试内容比较。 */
    List<ReturnLine> lines(@Param("receiptNo") String receiptNo);
    /** 与未发货全量回补共用预占锁，存在任一退货事实后禁止全量回补。 */
    int count(@Param("reservationNo") String reservationNo);
    /** 汇总原销售下已回补数量，不以调用方请求数量为依据。 */
    int returned(@Param("reservationNo")String reservationNo,@Param("skuId")String skuId);
    /** 收据头、购买件、余额和流水一起提交，任何约束失败整体回滚。 */
    int insert(ReturnRestock c);
    /** 全订单购买件唯一防同件换收据入库，SKU来自订单权威收据。 */
    int item(@Param("command")ReturnRestock command,@Param("line")ReturnLine line);
}
