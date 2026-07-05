package com.cayleywcs.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.audit.entity.MessageLogEntity;
import com.cayleywcs.audit.mapper.MessageLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 报文留存清理（M11 运维）：wcs_message_log 高频写入（每次点位读写一行），按保留天数每日清理。
 * wcs_connection_log 为低频状态迁移事件且供设备利用率统计重放，不在清理范围。
 */
@Component
public class MessageLogCleaner {
    private static final Logger log = LoggerFactory.getLogger(MessageLogCleaner.class);

    private final MessageLogMapper messageLogMapper;
    private final long retentionDays;

    public MessageLogCleaner(MessageLogMapper messageLogMapper,
                             @Value("${cayleywcs.audit.message-log-retention-days:7}") long retentionDays) {
        this.messageLogMapper = messageLogMapper;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${cayleywcs.audit.message-log-clean-cron:0 30 3 * * ?}")
    public void tick() {
        runOnce();
    }

    /** 每批删除行数：分批防大表一次性 DELETE 长事务/锁（引擎轮询读写审计使量级可观）。 */
    private static final int BATCH = 5000;

    /** 单轮清理（公开供测试驱动）。返回删除行数。 */
    public int runOnce() {
        if (retentionDays <= 0) {
            return 0; // 0=永久保留
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int total = 0;
        while (true) {
            List<Object> ids = messageLogMapper.selectObjs(new QueryWrapper<MessageLogEntity>()
                    .select("\"id\"")
                    .lt("\"create_time\"", cutoff)
                    .last("order by \"id\" asc limit " + BATCH));
            if (ids.isEmpty()) {
                break;
            }
            total += messageLogMapper.deleteBatchIds(ids.stream()
                    .map(o -> ((Number) o).longValue()).toList());
            if (ids.size() < BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("message_log 清理 {} 行（保留 {} 天，cutoff={}）", total, retentionDays, cutoff);
        }
        return total;
    }
}
