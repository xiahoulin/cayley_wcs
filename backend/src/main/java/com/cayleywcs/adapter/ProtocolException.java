package com.cayleywcs.adapter;

import com.cayleywcs.common.exception.WcsException;

/** 适配器错误保留统一业务码；日志不包含完整认证报文。 */
public final class ProtocolException extends WcsException {
    public ProtocolException(int code, String message) { super(code, message); }
    public ProtocolException(int code, String message, Throwable cause) {
        super(code, message); initCause(cause);
    }
    public static RuntimeException io(String field, Throwable cause) {
        Throwable original=cause;
        while ((cause instanceof java.util.concurrent.ExecutionException || cause instanceof java.util.concurrent.CompletionException)
                && cause.getCause()!=null) {cause=cause.getCause();}
        if (cause instanceof InterruptedException) {Thread.currentThread().interrupt();}
        return cause instanceof WcsException existing ? existing
                : new ProtocolException(4222,"协议点位收发失败："+field,original);
    }
}
