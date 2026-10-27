package com.tradeflow.contracts;

import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.RefundContracts.*;
import java.util.*;

/** 逐件验收处置的共享契约校验；不访问数据库、不决定金额、不接受空处置自动恢复可售。 */
public final class ReturnDispositionPolicy {
    public static final Set<String> CONDITIONS = Set.of("SALEABLE","QUARANTINE","DAMAGED","SCRAPPED");
    private ReturnDispositionPolicy() { }

    /** 规范排序以保证幂等摘要稳定；购买件号仅为账务归属，实物质量由商家验收确认。 */
    public static List<ReturnDisposition> canonical(List<ReturnDisposition> input) {
        if(input==null||input.isEmpty()||input.size()>1980) throw invalid("必须逐件指定退货处置");
        var result=new ArrayList<ReturnDisposition>();var identities=new HashSet<String>();
        for(var item:input) {
            if(item==null||item.orderItemId()==null||!item.orderItemId().matches("[1-9][0-9]{0,18}")||item.unitNo()<1||item.unitNo()>99)
                throw invalid("购买件编号不合法");
            try { Long.parseLong(item.orderItemId()); } catch(NumberFormatException e) { throw invalid("购买件编号不合法"); }
            if(item.disposition()==null||!CONDITIONS.contains(item.disposition())) throw invalid("退货处置不合法");
            if(!identities.add(identity(item.orderItemId(),item.unitNo()))) throw invalid("购买件重复处置");
            result.add(item);
        }
        result.sort(Comparator.comparingLong((ReturnDisposition i)->Long.parseLong(i.orderItemId())).thenComparingInt(ReturnDisposition::unitNo));
        return List.copyOf(result);
    }

    /** 处置必须恰好覆盖原授权购买件，不能漏件、换件或借验收扩大库存回补数量。 */
    public static List<ReturnDisposition> match(List<ReturnDisposition> input,List<Allocation> allocations) {
        var result=canonical(input);var expected=new HashSet<String>();
        if(allocations==null) throw invalid("购买件授权不存在");
        for(var allocation:allocations) expected.add(identity(allocation.orderItemId(),allocation.unitNo()));
        var actual=new HashSet<String>();for(var item:result) actual.add(identity(item.orderItemId(),item.unitNo()));
        if(expected.size()!=allocations.size()||!expected.equals(actual)) throw invalid("退货处置必须完整匹配原授权购买件");
        return result;
    }

    /** 仅已确认可再售的购买件增加available；其他状态保留实物去向但不增加可售库存。 */
    public static boolean saleable(String disposition) { return "SALEABLE".equals(disposition); }
    private static String identity(String item,int unit) { return item+":"+unit; }
    private static BusinessException invalid(String message) { return new BusinessException(400,"INVALID_RETURN_DISPOSITION",message); }
}
