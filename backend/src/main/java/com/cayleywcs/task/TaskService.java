package com.cayleywcs.task;

import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.task.entity.TaskEntity;
import java.util.List;

public interface TaskService {

    PageData<TaskEntity> page(PageSearch pageSearch);

    TaskEntity getById(Long id);

    TaskEntity create(TaskEntity entity);

    /** WMS 下发：创建一条待调度任务（幂等：同 app + task_no 不重复创建）。 */
    TaskEntity dispatch(DispatchRequest request);

    boolean cancel(Long id);

    /** 引擎用：取某应用下一个可推进任务（在飞优先，其次按优先级取待调度）。 */
    TaskEntity findNextForApp(Long appId);

    void save(TaskEntity entity);

    public record DispatchRequest(
            Long appId, String taskNo, String taskType,
            Long takeRow, Long takeColumn, Long takeFloor,
            Long putRow, Long putColumn, Long putFloor,
            Long portNum, Long priority, String wmsRef
    ) {
    }
}
