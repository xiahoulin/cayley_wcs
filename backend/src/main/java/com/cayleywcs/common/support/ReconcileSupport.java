package com.cayleywcs.common.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 对账水位线辅助：epoch 毫秒 ↔ 系统时区 LocalDateTime（与实体 last_update_time 写入口径一致）。 */
public final class ReconcileSupport {

    private ReconcileSupport() {
    }

    public static LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    public static int clampLimit(int limit) {
        if (limit <= 0) {
            return 200;
        }
        return Math.min(limit, 2000);
    }
}
