package com.cayleywcs.webhook;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.alarm.mapper.AlarmMapper;
import com.cayleywcs.common.entity.BaseEntity;
import com.cayleywcs.task.entity.TaskChainEntity;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskChainMapper;
import com.cayleywcs.task.mapper.TaskMapper;
import com.cayleywcs.webhook.entity.WebhookCursorEntity;
import com.cayleywcs.webhook.entity.WebhookDeliveryEntity;
import com.cayleywcs.webhook.entity.WebhookEntity;
import com.cayleywcs.webhook.mapper.WebhookCursorMapper;
import com.cayleywcs.webhook.mapper.WebhookDeliveryMapper;
import com.cayleywcs.webhook.mapper.WebhookMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Webhook 事件外发扫描器（M13）：游标 outbox 模式，不用事件监听（监听器崩溃即丢事件）。
 * 每 webhook × 每事件源（task/alarm/chain）维护独立 keyset 游标 (watermark_millis, last_id)，
 * 每轮**严格大于游标**按 (last_update_time, id) 升序续扫，分类事件→匹配订阅+scope→插投递记录（唯一键幂等）→
 * 游标推进到**已处理的最后一行**（非 now）。同毫秒由 id 决胜、limit 截断安全、崩溃安全不漏扫。
 *
 * 注：事件源为可变状态表而非追加事件日志——极快的 raised→cleared 只会外发到扫描时的当前态（如只见 cleared）；
 * 持久故障（常态）能捕获 raised。完整事件流可后续引入 append-only 事件表。
 */
@Component
public class WebhookScanner {
    private static final Logger log = LoggerFactory.getLogger(WebhookScanner.class);
    private static final String SRC_TASK = "task";
    private static final String SRC_ALARM = "alarm";
    private static final String SRC_CHAIN = "chain";
    private static final List<String> SOURCES = List.of(SRC_TASK, SRC_ALARM, SRC_CHAIN);

    private final WebhookMapper webhookMapper;
    private final WebhookCursorMapper cursorMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final TaskMapper taskMapper;
    private final AlarmMapper alarmMapper;
    private final TaskChainMapper chainMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int batch;
    private final long lagMs;

    public WebhookScanner(WebhookMapper webhookMapper, WebhookCursorMapper cursorMapper,
                          WebhookDeliveryMapper deliveryMapper, TaskMapper taskMapper,
                          AlarmMapper alarmMapper, TaskChainMapper chainMapper,
                          @Value("${cayleywcs.webhook.scan-batch:200}") int batch,
                          @Value("${cayleywcs.webhook.scan-lag-ms:2000}") long lagMs) {
        this.webhookMapper = webhookMapper;
        this.cursorMapper = cursorMapper;
        this.deliveryMapper = deliveryMapper;
        this.taskMapper = taskMapper;
        this.alarmMapper = alarmMapper;
        this.chainMapper = chainMapper;
        this.batch = Math.max(1, batch);
        this.lagMs = Math.max(0, lagMs);
    }

    @Scheduled(initialDelayString = "${cayleywcs.webhook.scan-interval-ms:3000}",
            fixedDelayString = "${cayleywcs.webhook.scan-interval-ms:3000}")
    public void tick() {
        runOnce();
    }

    /** 单轮扫描（公开供测试驱动）。 */
    public void runOnce() {
        List<WebhookEntity> webhooks = webhookMapper.selectList(new QueryWrapper<WebhookEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"enabled\"", true));
        for (WebhookEntity wh : webhooks) {
            List<String> events = wh.getEvent_types() == null ? List.of() : wh.getEvent_types();
            for (String source : SOURCES) {
                if (events.stream().anyMatch(e -> e.startsWith(source + "."))) {
                    try {
                        scanSource(wh, source);
                    } catch (RuntimeException ex) {
                        log.warn("webhook {} 源 {} 扫描异常，本轮跳过: {}", wh.getId(), source, ex.getMessage());
                    }
                }
            }
        }
    }

    private void scanSource(WebhookEntity wh, String source) {
        WebhookCursorEntity cursor = getOrCreateCursor(wh, source);
        LocalDateTime wmTs = cursor.getWatermark_time();
        long lastId = cursor.getLast_id() == null ? 0 : cursor.getLast_id();
        // 滞后上界（对抗审查 P2）：只处理 lut <= now()-lag 的行——保证一行被处理前已「稳定」lag 时长，
        // 使「事务先取 now(低 lut) 后延迟提交」的行在提交时仍 > 游标、不被越过丢失。lag 内的新行下轮再处理。
        LocalDateTime upper = LocalDateTime.now().minus(Duration.ofMillis(lagMs));

        List<? extends BaseEntity> rows = queryKeyset(source, wmTs, lastId, upper);
        if (rows.isEmpty()) {
            return;
        }
        for (BaseEntity row : rows) {
            String eventType = classify(source, statusOf(row));
            if (eventType != null && wh.getEvent_types().contains(eventType) && scopeMatches(wh, source, row)) {
                insertDelivery(wh, eventType, row.getId(), payload(source, eventType, row));
            }
        }
        // 游标推进到已处理的最后一行（全精度 lut，非毫秒截断）——limit 截断时下轮从此继续，不漏不重
        BaseEntity last = rows.get(rows.size() - 1);
        cursor.setWatermark_time(last.getLast_update_time());
        cursor.setLast_id(last.getId());
        cursor.setLast_update_time(LocalDateTime.now());
        cursorMapper.updateById(cursor);
    }

    private List<? extends BaseEntity> queryKeyset(String source, LocalDateTime wmTs, long lastId, LocalDateTime upper) {
        return switch (source) {
            case SRC_TASK -> taskMapper.selectList(keyset(new QueryWrapper<TaskEntity>(), wmTs, lastId, upper));
            case SRC_ALARM -> alarmMapper.selectList(keyset(new QueryWrapper<AlarmEntity>(), wmTs, lastId, upper));
            case SRC_CHAIN -> chainMapper.selectList(keyset(new QueryWrapper<TaskChainEntity>(), wmTs, lastId, upper));
            default -> List.of();
        };
    }

    /** keyset：is_valid + (lut > wm OR (lut = wm AND id > lastId)) + lut <= upper(now-lag)，升序，limit。 */
    private <T> QueryWrapper<T> keyset(QueryWrapper<T> w, LocalDateTime wmTs, long lastId, LocalDateTime upper) {
        return w.eq("\"is_valid\"", true)
                .le("\"last_update_time\"", upper)
                .and(a -> a.gt("\"last_update_time\"", wmTs)
                        .or(o -> o.eq("\"last_update_time\"", wmTs).gt("\"id\"", lastId)))
                .orderByAsc("\"last_update_time\"").orderByAsc("\"id\"")
                .last("limit " + batch);
    }

    private WebhookCursorEntity getOrCreateCursor(WebhookEntity wh, String source) {
        WebhookCursorEntity cursor = cursorMapper.selectOne(new QueryWrapper<WebhookCursorEntity>()
                .eq("\"webhook_id\"", wh.getId())
                .eq("\"source\"", source)
                .eq("\"tenant_id\"", tenant(wh))
                .last("limit 1"));
        if (cursor != null) {
            return cursor;
        }
        cursor = new WebhookCursorEntity();
        cursor.setWebhook_id(wh.getId());
        cursor.setSource(source);
        // 起点=订阅时刻（webhook 创建时间），只投递注册后发生的事件，不回放全部历史（对抗审查 P2 洪泛）。
        cursor.setWatermark_time(wh.getCreate_time() == null ? LocalDateTime.now() : wh.getCreate_time());
        cursor.setLast_id(0L);
        cursor.setLast_update_time(LocalDateTime.now());
        cursor.setTenant_id(tenant(wh));
        try {
            cursorMapper.insert(cursor);
        } catch (DuplicateKeyException ex) {
            return cursorMapper.selectOne(new QueryWrapper<WebhookCursorEntity>()
                    .eq("\"webhook_id\"", wh.getId()).eq("\"source\"", source)
                    .eq("\"tenant_id\"", tenant(wh)).last("limit 1"));
        }
        return cursor;
    }

    private void insertDelivery(WebhookEntity wh, String eventType, Long bizId, Map<String, Object> payload) {
        WebhookDeliveryEntity d = new WebhookDeliveryEntity();
        d.setWebhook_id(wh.getId());
        d.setEvent_type(eventType);
        d.setBiz_id(bizId);
        d.setPayload(toJson(payload));
        d.setStatus("pending");
        d.setAttempts(0L);
        d.setNext_retry_time(LocalDateTime.now());
        d.setLast_response("");
        LocalDateTime now = LocalDateTime.now();
        d.setCreator("system");
        d.setCreate_time(now);
        d.setLast_update_time(now);
        d.setIs_valid(true);
        d.setTenant_id(tenant(wh));
        try {
            deliveryMapper.insert(d);
        } catch (DuplicateKeyException ex) {
            // 该 (webhook, event, biz) 已入队/已投递：幂等去重
        }
    }

    private Map<String, Object> payload(String source, String eventType, BaseEntity row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", eventType);
        m.put("bizId", row.getId());
        m.put("timestamp", System.currentTimeMillis());
        switch (source) {
            case SRC_TASK -> {
                TaskEntity t = (TaskEntity) row;
                m.put("taskNo", t.getTask_no());
                m.put("appId", t.getApp_id());
                m.put("status", t.getStatus());
                m.put("errorCode", t.getError_code());
                m.put("chainId", t.getChain_id());
                m.put("wmsRef", t.getWms_ref());
            }
            case SRC_ALARM -> {
                AlarmEntity a = (AlarmEntity) row;
                m.put("appId", a.getApp_id());
                m.put("faultCode", a.getFault_code());
                m.put("level", a.getLevel());
                m.put("message", a.getMessage());
                m.put("status", a.getStatus());
            }
            case SRC_CHAIN -> {
                TaskChainEntity c = (TaskChainEntity) row;
                m.put("chainNo", c.getChain_no());
                m.put("upstreamAppId", c.getUpstream_app_id());
                m.put("status", c.getStatus());
            }
            default -> { }
        }
        return m;
    }

    private boolean scopeMatches(WebhookEntity wh, String source, BaseEntity row) {
        List<Long> scope = wh.getScope_app_ids();
        if (scope == null || scope.isEmpty()) {
            return true; // 空=全部
        }
        Long appId = switch (source) {
            case SRC_TASK -> ((TaskEntity) row).getApp_id();
            case SRC_ALARM -> ((AlarmEntity) row).getApp_id();
            case SRC_CHAIN -> ((TaskChainEntity) row).getUpstream_app_id();
            default -> null;
        };
        // 链无具体上位（upstream=0/null，管理端/旁路下发）：不绑定单一设备 → 广播给所有订阅链事件者，
        // 避免 scope 非空的 webhook 永远收不到旁路链事件（对抗审查 P3）。
        if (SRC_CHAIN.equals(source) && (appId == null || appId == 0)) {
            return true;
        }
        return appId != null && scope.contains(appId);
    }

    private static String statusOf(BaseEntity row) {
        if (row instanceof TaskEntity t) {
            return t.getStatus();
        }
        if (row instanceof AlarmEntity a) {
            return a.getStatus();
        }
        if (row instanceof TaskChainEntity c) {
            return c.getStatus();
        }
        return null;
    }

    private static String classify(String source, String status) {
        if (status == null) {
            return null;
        }
        return switch (source) {
            case SRC_TASK, SRC_CHAIN -> switch (status) {
                case "completed", "failed", "cancelled" -> source + "." + status;
                default -> null; // pending/dispatched/executing/waiting/running 不外发
            };
            case SRC_ALARM -> switch (status) {
                case "raised" -> "alarm.raised";
                case "cleared" -> "alarm.cleared";
                default -> null; // ack 内部流转不外发
            };
            default -> null;
        };
    }

    private static long tenant(WebhookEntity wh) {
        return wh.getTenant_id() == null ? 1L : wh.getTenant_id();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
