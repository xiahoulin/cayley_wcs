package com.cayleywcs.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;

/** 仅在隔离 PostgreSQL 环境启用；在任务三次统计查询之间制造已提交的 retry。 */
@SpringBootTest(properties = "cayleywcs.task.recovery-interval-ms=3600000")
@Import(StatsSnapshotPostgresTest.BarrierConfig.class)
@EnabledIfEnvironmentVariable(named = "CAYLEYWCS_PG_TEST", matches = "true")
class StatsSnapshotPostgresTest {
    @Autowired private StatsService stats;
    @Autowired private BarrierJdbcTemplate jdbc;

    @Test
    void retryBetweenQueriesDoesNotMixCountAndFailTop() throws Exception {
        long appId = 99_160_000L + (System.nanoTime() & 0xffff);
        String taskNo = "SNAPSHOT-" + UUID.randomUUID();
        jdbc.update("insert into wcs_task(task_no,app_id,status,error_code) values (?,?,?,?)",
                taskNo, appId, "failed", 7);
        var executor = Executors.newSingleThreadExecutor();
        try {
            jdbc.arm();
            var future = executor.submit(() -> stats.taskSummary(appId, 0, 0));
            assertThat(jdbc.firstRead.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.update("update wcs_task set status='pending',error_code=0 where task_no=?", taskNo))
                    .isEqualTo(1);
            jdbc.release.countDown();
            StatsService.TaskSummary result = future.get(5, TimeUnit.SECONDS);
            assertThat(result.total()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.pending()).isZero();
            assertThat(result.failTop()).containsExactly(new StatsService.FailCode(7, 1));
        } finally {
            jdbc.release.countDown();
            executor.shutdownNow();
            jdbc.update("delete from wcs_task where task_no=?", taskNo);
        }
    }

    @TestConfiguration
    static class BarrierConfig {
        @Bean
        @Primary
        BarrierJdbcTemplate barrierJdbcTemplate(DataSource source) {
            return new BarrierJdbcTemplate(source);
        }
    }

    static class BarrierJdbcTemplate extends JdbcTemplate {
        volatile CountDownLatch firstRead = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean();

        BarrierJdbcTemplate(DataSource source) {
            super(source);
        }

        void arm() {
            firstRead = new CountDownLatch(1);
            release = new CountDownLatch(1);
            armed.set(true);
        }

        @Override
        public void query(PreparedStatementCreator creator, RowCallbackHandler handler) {
            super.query(creator, handler);
            if (armed.compareAndSet(true, false)) {
                firstRead.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("snapshot test barrier timed out");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
        }
    }
}
