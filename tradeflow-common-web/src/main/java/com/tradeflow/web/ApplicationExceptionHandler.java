package com.tradeflow.web;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一客户端可见错误，内部日志与外部消息分离。 */
@RestControllerAdvice
public class ApplicationExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationExceptionHandler.class);

    /** 按明确业务语义返回错误；事务异常由应用边界回滚，不在此伪装为成功。 */
    @ExceptionHandler(BusinessException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> business(BusinessException exception,
            HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.status(exception.status())
                .body(failure(exception.code(), exception.getMessage(), request));
    }

    /** 拒绝未知JSON字段、错误枚举及错误路径参数，不回显原始请求值。 */
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            jakarta.validation.ConstraintViolationException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> malformed(HttpServletRequest request) {
        return failure("VALIDATION_ERROR", "请求格式不合法", request);
    }

    /** 参数错误不重试，不返回可能含敏感输入的原始校验对象。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> invalidInput(HttpServletRequest request) {
        return failure("VALIDATION_ERROR", "请求参数不合法", request);
    }

    /** 未知异常仅在服务端记录堆栈，不能向调用方泄露数据库或配置细节。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Unhandled request failure requestId={}", request.getAttribute("requestId"), exception);
        return failure("INTERNAL_ERROR", "服务暂时无法处理请求", request);
    }

    private ApiResponse<Void> failure(String code, String message, HttpServletRequest request) {
        return ApiResponse.failure(code, message, (String) request.getAttribute("requestId"),
                (String) request.getAttribute("traceId"));
    }
}
