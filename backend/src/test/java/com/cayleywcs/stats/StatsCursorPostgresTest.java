package com.cayleywcs.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;

/** 真 PG 分批游标遍历中暂停，确认连接占用受事务约束且其他查询仍可服务。 */
@SpringBootTest(properties = "cayleywcs.metrics.webhook-probe-interval-ms=3600000")
@Import(StatsCursorPostgresTest.CursorConfig.class)
@EnabledIfEnvironmentVariable(named = "CAYLEYWCS_PG_TEST", matches = "true")
class StatsCursorPostgresTest {
    @Autowired private StatsService stats;
    @Autowired private CursorJdbcTemplate jdbc;
    @Autowired private DataSource source;

    @Test
    void largeWindowStreamsRowsAndReleasesConnection() throws Exception {
        long appId = 99_170_000L + (System.nanoTime() & 0xffff);
        jdbc.update("insert into wcs_connection_log(app_id,app_code,event,state,create_time)"
                        + " select ?, 'CURSOR_TEST', 'STATE_CHANGE',"
                        + " case when g % 2 = 0 then 'RUNNING' else 'DISCONNECTED' end,"
                        + " current_timestamp - interval '1 second' * (20001-g)"
                        + " from generate_series(1,20000) g", appId);
        var executor = Executors.newSingleThreadExecutor();
        try {
            jdbc.arm();
            long to = System.currentTimeMillis();
            long from = to - TimeUnit.DAYS.toMillis(1);
            var future = executor.submit(() -> stats.deviceUtilization(appId, from, to));
            assertThat(jdbc.cursorReached.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.seen.get()).isGreaterThanOrEqualTo(512);
            HikariDataSource hikari = (HikariDataSource) source;
            assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
            jdbc.release.countDown();
            var result = future.get(20, TimeUnit.SECONDS);
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().appCode()).isEqualTo("CURSOR_TEST");
            assertThat(jdbc.seen.get()).isEqualTo(20000);
        } finally {
            jdbc.release.countDown();
            executor.shutdownNow();
            jdbc.update("delete from wcs_connection_log where app_id=?", appId);
        }
    }

    @TestConfiguration
    static class CursorConfig {
        @Bean
        @Primary
        CursorJdbcTemplate cursorJdbcTemplate(DataSource source) {
            return new CursorJdbcTemplate(source);
        }
    }

    static class CursorJdbcTemplate extends JdbcTemplate {
        volatile CountDownLatch cursorReached = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicInteger seen = new AtomicInteger();

        CursorJdbcTemplate(DataSource source) {
            super(source);
        }

        void arm() {
            cursorReached = new CountDownLatch(1);
            release = new CountDownLatch(1);
            seen.set(0);
            armed.set(true);
        }

        @Override
        public void query(PreparedStatementCreator creator, RowCallbackHandler handler) {
            super.query(creator, row -> {
                if (armed.get() && seen.incrementAndGet() == 512) {
                    cursorReached.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("cursor barrier timed out");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }
                handler.processRow(row);
            });
        }
    }
}
