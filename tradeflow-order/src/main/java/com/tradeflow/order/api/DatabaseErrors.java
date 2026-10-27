package com.tradeflow.order.api;

import com.tradeflow.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.annotation.Order;

/** 将数据库竞争转换为稳定业务错误，不向外泄露SQL或参数。 */
@RestControllerAdvice @Order(0)
public class DatabaseErrors {
    /** 唯一键冲突由数据库仲裁，调用方不能盲目换键重复写入。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> duplicate(HttpServletRequest request) {
        return error(409,"DUPLICATE_RESOURCE","唯一业务标识已存在，请查询原结果",request);
    }
    /** 锁竞争已由事务回滚；保留原业务/幂等键后再重试。 */
    @ExceptionHandler(TransientDataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> transientFailure(HttpServletRequest request) {
        return error(503,"DATABASE_BUSY","数据库暂时繁忙，请保留原幂等键重试",request);
    }
    /** 数据源不可用不能降级为假数据或写入成功。 */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(HttpServletRequest request) {
        return error(503,"DATABASE_UNAVAILABLE","数据库暂时不可用",request);
    }
    private ResponseEntity<ApiResponse<Void>> error(int status,String code,String message,HttpServletRequest r) {
        return ResponseEntity.status(status).body(ApiResponse.failure(code,message,(String)r.getAttribute("requestId"),(String)r.getAttribute("traceId")));
    }
}
