package com.cayleywcs.task.orchestrate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import org.springframework.stereotype.Component;

/** 先进先出派工：严格按下发顺序（id asc），忽略 priority。 */
@Component
public class FifoTaskStrategy implements TaskSelectionStrategy {
    private final TaskMapper taskMapper;

    public FifoTaskStrategy(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public String code() {
        return "fifo";
    }

    @Override
    public TaskEntity nextPending(Long appId) {
        return taskMapper.selectOne(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_id\"", appId)
                .eq("\"status\"", TaskStatus.PENDING)
                .last("order by \"id\" asc limit 1"));
    }
}
