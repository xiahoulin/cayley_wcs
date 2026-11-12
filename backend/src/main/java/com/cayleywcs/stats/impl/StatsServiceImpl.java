package com.cayleywcs.stats.impl;

import com.cayleywcs.common.support.ReconcileSupport;
import com.cayleywcs.stats.StatsService;
import com.cayleywcs.task.TaskStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 统计只读取所需列；多语句入口使用同一只读快照，日志用服务端游标重放。 */
@Service
public class StatsServiceImpl implements StatsService {
    private static final int FETCH_SIZE = 256;
    private static final int UTILIZATION_LOOKBACK_DAYS = 7;
    private final JdbcTemplate jdbc;

    public StatsServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskSummary taskSummary(Long appId, long fromMillis, long toMillis) {
        StringBuilder filter = new StringBuilder(" from \"wcs_task\" where \"is_valid\" = true");
        List<Object> args = new ArrayList<>();
        appendFilters(filter, args, appId, "create_time", fromMillis, toMillis);
        TaskCounts counts = new TaskCounts();
        stream("select count(*) total,"
                + " coalesce(sum(case when \"status\"='" + TaskStatus.COMPLETED + "' then 1 else 0 end),0) completed,"
                + " coalesce(sum(case when \"status\"='" + TaskStatus.FAILED + "' then 1 else 0 end),0) failed,"
                + " coalesce(sum(case when \"status\"='" + TaskStatus.CANCELLED + "' then 1 else 0 end),0) cancelled,"
                + " coalesce(sum(case when \"status\" in ('" + TaskStatus.DISPATCHED + "','" + TaskStatus.EXECUTING
                + "') then 1 else 0 end),0) in_flight"
                + filter, args, rs -> {
            counts.total = rs.getLong("total");
            counts.completed = rs.getLong("completed");
            counts.failed = rs.getLong("failed");
            counts.cancelled = rs.getLong("cancelled");
            counts.inFlight = rs.getLong("in_flight");
        });
        counts.pending = counts.total - counts.completed - counts.failed - counts.cancelled - counts.inFlight;
        stream("select \"dispatch_time\", \"finish_time\"" + filter
                + " and \"status\"='" + TaskStatus.COMPLETED
                + "' and \"dispatch_time\" is not null and \"finish_time\" is not null",
                args, rs -> {
                    counts.execSum += Duration.between(timestamp(rs, "dispatch_time"),
                            timestamp(rs, "finish_time")).toMillis();
                    counts.execCount++;
                });
        List<FailCode> failTop = new ArrayList<>(10);
        stream("select coalesce(\"error_code\",0) error_code, count(*) code_count, min(\"id\") first_id"
                + filter + " and \"status\"='" + TaskStatus.FAILED + "' group by coalesce(\"error_code\",0)"
                + " order by code_count desc, first_id asc limit 10", args,
                rs -> failTop.add(new FailCode(rs.getLong("error_code"), rs.getLong("code_count"))));
        long decided = counts.completed + counts.failed;
        return new TaskSummary(counts.total, counts.completed, counts.failed, counts.cancelled,
                counts.inFlight, counts.pending, decided == 0 ? 0.0 : (double) counts.completed / decided,
                counts.execCount == 0 ? 0 : counts.execSum / counts.execCount, List.copyOf(failTop));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<DeviceUtilization> deviceUtilization(Long appId, long fromMillis, long toMillis) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = fromMillis > 0 ? ReconcileSupport.toLocalDateTime(fromMillis) : now.minusDays(1);
        LocalDateTime to = toMillis > 0 ? ReconcileSupport.toLocalDateTime(toMillis) : now;
        if (!to.isAfter(from)) {
            return List.of();
        }
        long windowMillis = Duration.between(from, to).toMillis();
        StringBuilder sql = new StringBuilder("select \"app_id\", \"app_code\", \"state\", \"create_time\" "
                + "from \"wcs_connection_log\" where \"event\" = 'STATE_CHANGE' "
                + "and \"create_time\" >= ? and \"create_time\" <= ?");
        List<Object> args = new ArrayList<>(List.of(from.minusDays(UTILIZATION_LOOKBACK_DAYS), to));
        if (appId != null) {
            sql.append(" and \"app_id\" = ?");
            args.add(appId);
        }
        sql.append(" order by \"app_id\" asc, \"create_time\" asc, \"id\" asc");
        List<DeviceUtilization> out = new ArrayList<>();
        DeviceReplay[] current = new DeviceReplay[1];
        stream(sql.toString(), args, rs -> {
            long rowAppId = rs.getLong("app_id");
            if (current[0] == null || current[0].appId != rowAppId) {
                if (current[0] != null) {
                    out.add(current[0].finish(to, windowMillis));
                }
                current[0] = new DeviceReplay(rowAppId, from);
            }
            current[0].accept(rs.getString("app_code"), rs.getString("state"),
                    timestamp(rs, "create_time"), from);
        });
        if (current[0] != null) {
            out.add(current[0].finish(to, windowMillis));
        }
        // 稳定排序保留原查询的 app_id 升序并列规则。
        out.sort(Comparator.comparingDouble(DeviceUtilization::utilization).reversed());
        return out;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AlarmTop> alarmTop(Long appId, long fromMillis, long toMillis, int topN) {
        StringBuilder filter = new StringBuilder(" from \"wcs_alarm\" where \"is_valid\" = true");
        List<Object> args = new ArrayList<>();
        appendFilters(filter, args, appId, "raised_time", fromMillis, toMillis);
        String sql = "with filtered as (select \"id\", coalesce(\"fault_code\",0) code,"
                + " \"level\", \"message\", \"raised_time\"" + filter + "), ranked as ("
                + " select code, \"level\", \"message\","
                + " count(*) over(partition by code) code_count,"
                + " row_number() over(partition by code order by \"raised_time\" desc,\"id\" desc) latest_rank,"
                + " first_value(\"raised_time\") over(partition by code order by \"raised_time\",\"id\") first_raised,"
                + " first_value(\"id\") over(partition by code order by \"raised_time\",\"id\") first_id"
                + " from filtered) select code,code_count,\"level\",\"message\" from ranked"
                + " where latest_rank=1 order by code_count desc,first_raised asc,first_id asc limit ?";
        args.add(topN <= 0 ? 10 : topN);
        List<AlarmTop> result = new ArrayList<>();
        stream(sql, args, rs -> result.add(new AlarmTop(rs.getLong("code"), rs.getLong("code_count"),
                rs.getString("level"), rs.getString("message"))));
        return result;
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, Long appId,
                                      String timeColumn, long fromMillis, long toMillis) {
        if (appId != null) {
            sql.append(" and \"app_id\" = ?");
            args.add(appId);
        }
        if (fromMillis > 0) {
            sql.append(" and \"").append(timeColumn).append("\" >= ?");
            args.add(ReconcileSupport.toLocalDateTime(fromMillis));
        }
        if (toMillis > 0) {
            sql.append(" and \"").append(timeColumn).append("\" <= ?");
            args.add(ReconcileSupport.toLocalDateTime(toMillis));
        }
    }

    private void stream(String sql, List<Object> args, RowCallbackHandler row) {
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(FETCH_SIZE);
            for (int i = 0; i < args.size(); i++) {
                statement.setObject(i + 1, args.get(i));
            }
            return statement;
        }, row);
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static final class TaskCounts {
        long total, completed, failed, cancelled, inFlight, pending, execSum, execCount;
    }

    private static final class DeviceReplay {
        final long appId;
        String appCode;
        boolean running;
        LocalDateTime cursor;
        long runningMillis;

        DeviceReplay(long appId, LocalDateTime from) {
            this.appId = appId;
            cursor = from;
        }

        void accept(String code, String state, LocalDateTime at, LocalDateTime from) {
            appCode = code;
            if (at == null) {
                return;
            }
            if (at.isBefore(from)) {
                running = "RUNNING".equals(state);
                return;
            }
            if (running) {
                runningMillis += Duration.between(cursor, at).toMillis();
            }
            running = "RUNNING".equals(state);
            cursor = at;
        }

        DeviceUtilization finish(LocalDateTime to, long windowMillis) {
            if (running) {
                runningMillis += Duration.between(cursor, to).toMillis();
            }
            long bounded = Math.max(0, runningMillis);
            return new DeviceUtilization(appId, appCode, bounded, windowMillis,
                    windowMillis == 0 ? 0.0 : (double) bounded / windowMillis);
        }
    }
}
