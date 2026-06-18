package com.cayleywcs.faultcode;

/**
 * 故障解析结果。{@code known=false} 表示未维护，走统一兜底信息（需求 6）。
 */
public record FaultInfo(
        long code,
        String level,
        String name,
        String message,
        String suggestion,
        boolean known
) {
    public static FaultInfo unknown(long code) {
        return new FaultInfo(code, "error", "未知故障",
                "未知故障，故障码=" + code + "，请联系维护", "请在故障码表中维护该编码", false);
    }
}
