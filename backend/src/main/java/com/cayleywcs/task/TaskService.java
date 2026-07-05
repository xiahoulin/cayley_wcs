package com.cayleywcs.task;

import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.task.entity.TaskEntity;
import java.util.List;
import java.util.Map;

public interface TaskService {

    PageData<TaskEntity> page(PageSearch pageSearch);

    TaskEntity getById(Long id);

    TaskEntity create(TaskEntity entity);

    /** WMS 下发：创建一条待调度任务（幂等：同 app + task_no 不重复创建）。 */
    TaskEntity dispatch(DispatchRequest request);

    boolean cancel(Long id);

    /**
     * 引擎用：取某应用下一个可推进任务。
     * 顺序：①在飞优先（正确性约束，不受策略/工作模式影响）→ ②work_mode 非 normal 停新派工 → ③按应用 dispatch_strategy 选 pending。
     */
    TaskEntity findNextForApp(Long appId);

    /** 管理端：改优先级（乐观锁保护；终态任务拒绝）。false=并发冲突，重试。 */
    boolean reprioritize(Long id, Long priority);

    /** 管理端：重试 failed 独立任务（重置 pending/CREATED，retry_count++；链子任务走链级处理）。false=并发冲突。 */
    boolean retryTask(Long id);

    /**
     * 重试原语（M11，恢复调度器/编排器共用）：failed → pending/CREATED，error_code 清零，retry_count++。
     * 不区分链子任务（链段重试由编排器调本方法）；非 failed 态返回 false（并发已推进）。乐观锁保护。
     */
    boolean resetForRetry(Long id);

    /** 对账用：返回某应用 last_update_time >= sinceMillis 的任务（sinceMillis=0 即全量），按更新时间升序。 */
    List<TaskEntity> queryReconcile(Long appId, long sinceMillis, int limit);

    /** 对账用（越权收敛）：限定 appIds 集合；null=不限（appkey 关闭旁路），空集=无可见应用返回空。 */
    List<TaskEntity> queryReconcile(List<Long> appIds, long sinceMillis, int limit);

    /** 保存任务。返回 false = 乐观锁版本冲突（并发写赢了），调用方应丢弃本地状态重读。 */
    boolean save(TaskEntity entity);

    public record DispatchRequest(
            Long appId, String taskNo, String taskType,
            Long takeRow, Long takeColumn, Long takeFloor,
            Long putRow, Long putColumn, Long putFloor,
            Long portNum, Long priority, String wmsRef,
            Map<String, Object> payload
    ) {
    }
}
