package com.cayleywcs.common.exception;

/**
 * WCS 统一错误码。HTTP 始终 200，业务结果由 {@code ApiResponse.code} 表达。
 */
public enum ErrorCode {
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证或令牌无效"),
    APPKEY_INVALID(4011, "APP KEY 无效或未启用"),
    APPKEY_SIGNATURE_INVALID(4012, "APP KEY 签名校验失败"),
    APPKEY_REPLAY(4013, "请求重放或时间戳过期"),
    OPEN_CREDENTIAL_INVALID(101002, "应用调用凭证无效"),
    OPEN_SECRET_INVALID(101003, "应用密钥无效"),
    OPEN_APP_DISABLED(101004, "应用已停用"),
    OPEN_TOKEN_MISSING(102001, "access_token 缺失"),
    OPEN_TOKEN_INVALID(102002, "access_token 无效"),
    OPEN_TOKEN_EXPIRED(102003, "access_token 已过期"),
    OPEN_TOKEN_REVOKED(102004, "access_token 已撤销"),
    OPEN_TOKEN_MISMATCH(102005, "令牌与应用不匹配"),
    OPEN_GRANT_INVALID(102006, "令牌获取方式不支持"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源状态冲突"),

    // 连接治理
    CONN_POOL_FULL(4291, "连接已满，请等待"),
    CONN_TIMEOUT(4292, "建立连接超时"),
    CONN_FAILED(4293, "建立连接失败"),
    CONN_NOT_FOUND(4294, "连接不存在"),
    CONN_ALREADY_OPEN(4295, "连接已存在"),

    // 协议/适配器
    PROTOCOL_UNSUPPORTED(4221, "不支持的协议类型"),
    PROTOCOL_IO_ERROR(4222, "协议读写错误"),
    POINT_CONTRACT_MISMATCH(4223, "点位约定不匹配"),
    POINT_ENCODING_INVALID(4224, "点位类型或取值不可编码"),
    POINT_RESPONSE_INVALID(4225, "响应点位校验失败"),

    // 故障码
    DEVICE_FAULT(4231, "设备故障"),

    // 任务调度
    TASK_INVALID(4241, "任务参数非法"),
    TASK_DUPLICATED(4242, "任务号重复"),
    TASK_STATE_ILLEGAL(4243, "任务状态非法"),

    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
