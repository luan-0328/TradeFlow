package com.tradeflow.product.infrastructure;
import com.tradeflow.contracts.RefundContracts.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 原预占锁下记录不可变回补依据，唯一原预占防不同收据重复入库。 */
@Mapper
public interface RestockMapper {
    /** 返回原收据绑定，重复不能借新source扩大库存。 */
    RestockView byReservation(@Param("reservationNo") String reservationNo);
    /** 头、余额与流水同事务，插入失败必须整体回滚。 */
    int insert(Restock command);
}
