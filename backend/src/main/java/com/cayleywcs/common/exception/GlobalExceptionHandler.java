package com.cayleywcs.common.exception;

import com.cayleywcs.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（CayleyWMS 当前缺失，作为 WCS 的改进）。
 * 把任何异常统一转成 {@link ApiResponse#error}，HTTP 仍返回 200，避免前端拿不到结构化错误。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WcsException.class)
    public ApiResponse<Void> handleWcs(WcsException ex) {
        log.warn("WcsException code={} msg={}", ex.getCode(), ex.getMessage());
        return ApiResponse.error(ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("IllegalArgument: {}", ex.getMessage());
        return ApiResponse.error(ex.getMessage(), ErrorCode.BAD_REQUEST.code());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ApiResponse<Void> handleBadRequest(Exception ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ApiResponse.error("请求体解析失败", ErrorCode.BAD_REQUEST.code());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ApiResponse<Void> handleIllegalState(IllegalStateException ex) {
        log.warn("IllegalState: {}", ex.getMessage());
        return ApiResponse.error(ex.getMessage(), ErrorCode.CONFLICT.code());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleOther(Exception ex) {
        log.error("Unhandled exception", ex);
        return ApiResponse.error(ErrorCode.INTERNAL_ERROR.message(), ErrorCode.INTERNAL_ERROR.code());
    }
}
