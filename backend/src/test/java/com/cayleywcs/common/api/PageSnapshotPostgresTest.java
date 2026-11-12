package com.cayleywcs.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.mapper.TaskMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真 PG 上在实际 TaskService.page 的 COUNT 与取页之间插入新行，验证请求内快照。 */
@SpringBootTest
@Import(PageSnapshotPostgresTest.BarrierConfig.class)
@EnabledIfEnvironmentVariable(named = "CAYLEYWCS_PG_TEST", matches = "true")
class PageSnapshotPostgresTest {
    @Autowired private TaskService tasks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Barrier barrier;

    @Test
    void pageRowsAndTotalUseTheSameSnapshot() throws Exception {
        String taskNo = "PAGE-SNAPSHOT-" + UUID.randomUUID();
        barrier.arm();
        PageSearch search = new PageSearch();
        search.setPageSize(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                barrier.target = Thread.currentThread();
                return tasks.page(search);
            });
            if (!barrier.counted.await(5, TimeUnit.SECONDS)) {
                future.get(1, TimeUnit.SECONDS); // 抛出请求线程真实错误，避免只看到屏障超时。
                throw new AssertionError("page count barrier not reached");
            }
            jdbc.update("insert into wcs_task(task_no,app_id,status) values (?,?,?)",
                    taskNo, 99778L, "pending");
            Long insertedId = jdbc.queryForObject("select id from wcs_task where task_no=?", Long.class, taskNo);
            barrier.release.countDown();
            PageData<?> result = future.get(5, TimeUnit.SECONDS);
            assertThat(result.totals()).isEqualTo(barrier.countValue.get());
            assertThat(result.rows()).hasSize(1);
            assertThat(((com.cayleywcs.task.entity.TaskEntity) result.rows().getFirst()).getId())
                    .isNotEqualTo(insertedId);
        } finally {
            barrier.release.countDown();
            executor.shutdownNow();
            jdbc.update("delete from wcs_task where task_no=?", taskNo);
        }
    }

    static class Barrier {
        volatile CountDownLatch counted = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        volatile Thread target;
        final AtomicLong countValue = new AtomicLong();
        final AtomicBoolean armed = new AtomicBoolean();

        void arm() {
            counted = new CountDownLatch(1);
            release = new CountDownLatch(1);
            armed.set(true);
        }
    }

    @TestConfiguration
    static class BarrierConfig {
        @Bean
        Barrier barrier() {
            return new Barrier();
        }

        @Bean
        @Primary
        TaskMapper barrierTaskMapper(@Qualifier("taskMapper") TaskMapper delegate, Barrier barrier) {
            return (TaskMapper) Proxy.newProxyInstance(TaskMapper.class.getClassLoader(),
                    new Class<?>[]{TaskMapper.class}, (proxy, method, args) -> {
                        Object result;
                        try {
                            result = method.invoke(delegate, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                        if ("selectCount".equals(method.getName()) && Thread.currentThread() == barrier.target
                                && barrier.armed.compareAndSet(true, false)) {
                            barrier.countValue.set(((Number) result).longValue());
                            barrier.counted.countDown();
                            if (!barrier.release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("page snapshot barrier timed out");
                            }
                        }
                        return result;
                    });
        }
    }
}
