package com.cayleywcs.common.exception;

/**
 * 业务异常，被 {@link GlobalExceptionHandler} 统一转换成 ApiResponse.error。
 */
public class WcsException extends RuntimeException {
    private final int code;

    public WcsException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public WcsException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.code();
    }

    public WcsException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static WcsException of(ErrorCode errorCode) {
        return new WcsException(errorCode);
    }

    public static WcsException of(ErrorCode errorCode, String message) {
        return new WcsException(errorCode, message);
    }
}
