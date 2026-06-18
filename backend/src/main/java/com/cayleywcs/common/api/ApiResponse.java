package com.cayleywcs.common.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 统一响应体，字段契约与 CayleyWMS 一致：isSuccess / code / errorMessage / data。
 */
public record ApiResponse<T>(
        @JsonProperty("isSuccess") boolean success,
        int code,
        String errorMessage,
        T data
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, 200, "", data);
    }

    public static <T> ApiResponse<T> error(String errorMessage) {
        return error(errorMessage, 400, null);
    }

    public static <T> ApiResponse<T> error(String errorMessage, int code) {
        return error(errorMessage, code, null);
    }

    public static <T> ApiResponse<T> error(String errorMessage, int code, T data) {
        return new ApiResponse<>(false, code, errorMessage, data);
    }
}
