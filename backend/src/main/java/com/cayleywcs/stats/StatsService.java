package com.cayleywcs.stats;

import java.util.List;

/**
 * 运行统计（M11）：任务汇总 / 设备利用率（重放连接事件日志）/ 报警 TOP。
 * 聚合一律在 Java 内完成（对齐 PageSupport 内存分页风格），保 H2/PG 双兼容。
 */
public interface StatsService {

    /** 任务汇总：时间窗（create_time ∈ [fromMillis, toMillis]，0=不限）内任务量/终态分布/完成率/平均执行时长/失败码 TOP。 */
    TaskSummary taskSummary(Long appId, long fromMillis, long toMillis);

    /** 设备利用率：重放 wcs_connection_log 的状态迁移，计算窗口内 RUNNING 时长占比。appId=null 返回全部有日志的设备。 */
    List<DeviceUtilization> deviceUtilization(Long appId, long fromMillis, long toMillis);

    /** 报警 TOP：时间窗内按故障码聚合的报警次数（降序，含级别与最近一条文案）。 */
    List<AlarmTop> alarmTop(Long appId, long fromMillis, long toMillis, int topN);

    record TaskSummary(long total, long completed, long failed, long cancelled, long inFlight, long pending,
                       double completionRate, long avgExecMillis, List<FailCode> failTop) {
    }

    record FailCode(long errorCode, long count) {
    }

    record DeviceUtilization(Long appId, String appCode, long runningMillis, long windowMillis, double utilization) {
    }

    record AlarmTop(long faultCode, long count, String level, String latestMessage) {
    }
}
