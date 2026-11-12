package com.cayleywcs.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.alarm.mapper.AlarmMapper;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.entity.TaskChainEntity;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskChainMapper;
import com.cayleywcs.task.mapper.TaskMapper;
import com.cayleywcs.task.orchestrate.ChainService;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 与原全量读取后切片的行为对照，覆盖软删、排序和越界页。 */
@SpringBootTest
@ActiveProfiles("test-flyway")
class DatabasePaginationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskService taskService;
    @Autowired private AlarmService alarmService;
    @Autowired private ChainService chainService;
    @Autowired private TaskMapper taskMapper;
    @Autowired private AlarmMapper alarmMapper;
    @Autowired private TaskChainMapper chainMapper;

    @Test
    void threeLargeListsMatchPreviousSliceSemantics() {
        String suffix = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            boolean valid = i != 2;
            jdbc.update("insert into \"wcs_task\" (\"task_no\",\"app_id\",\"status\",\"is_valid\") values (?,?,?,?)",
                    "PAGE-" + suffix + "-" + i, 90091L, "completed", valid);
            jdbc.update("insert into \"wcs_alarm\" (\"app_id\",\"fault_code\",\"status\",\"is_valid\") values (?,?,?,?)",
                    90091L, 900L + i, "cleared", valid);
            jdbc.update("insert into \"wcs_task_chain\" (\"chain_no\",\"status\",\"is_valid\") values (?,?,?)",
                    "PAGE-" + suffix + "-" + i, "completed", valid);
        }

        assertPages(taskService::page, () -> taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true).orderByDesc("\"id\"")));
        assertPages(alarmService::page, () -> alarmMapper.selectList(new QueryWrapper<AlarmEntity>()
                .eq("\"is_valid\"", true).orderByDesc("\"id\"")));
        assertPages(chainService::page, () -> chainMapper.selectList(new QueryWrapper<TaskChainEntity>()
                .eq("\"is_valid\"", true).orderByDesc("\"id\"")));
    }

    private static <T extends com.cayleywcs.common.entity.BaseEntity> void assertPages(
            Function<PageSearch, PageData<T>> actualPage, Supplier<List<T>> oldQuery) {
        List<T> all = oldQuery.get();
        for (int index : List.of(1, 2, 3, Integer.MAX_VALUE)) {
            PageSearch search = new PageSearch();
            search.setPageIndex(index);
            search.setPageSize(2);
            PageData<T> actual = actualPage.apply(search);
            long offset = ((long) index - 1) * search.getPageSize();
            List<Long> expectedIds = offset >= all.size() ? List.of()
                    : all.subList((int) offset, Math.min((int) offset + 2, all.size()))
                            .stream().map(com.cayleywcs.common.entity.BaseEntity::getId).toList();
            assertThat(actual.totals()).isEqualTo(all.size());
            assertThat(actual.rows().stream().map(com.cayleywcs.common.entity.BaseEntity::getId).toList())
                    .containsExactlyElementsOf(expectedIds);
        }
    }
}
