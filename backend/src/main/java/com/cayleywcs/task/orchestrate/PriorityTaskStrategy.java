package com.cayleywcs.task.orchestrate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import org.springframework.stereotype.Component;

/** 优先级派工（默认，=M10 前既有行为）：priority desc，同优先级 FIFO（id asc）。 */
@Component
public class PriorityTaskStrategy implements TaskSelectionStrategy {
    private final TaskMapper taskMapper;

    public PriorityTaskStrategy(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public String code() {
        return "priority";
    }

    @Override
    public TaskEntity nextPending(Long appId) {
        return taskMapper.selectOne(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_id\"", appId)
                .eq("\"status\"", TaskStatus.PENDING)
                .last("order by \"priority\" desc, \"id\" asc limit 1"));
    }
}
