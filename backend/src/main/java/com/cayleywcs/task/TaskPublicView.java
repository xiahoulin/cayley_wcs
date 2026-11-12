package com.cayleywcs.task;

import com.cayleywcs.task.entity.TaskEntity;
import java.util.LinkedHashMap;
import java.util.Map;

/** 开放调用只返回业务元数据、请求点位及持久化结果。 */
public final class TaskPublicView {
    private TaskPublicView() {}
    public static Map<String,Object> of(TaskEntity task) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("id",task.getId()); value.put("task_no",task.getTask_no()); value.put("app_id",task.getApp_id());
        value.put("wms_ref",task.getWms_ref()); value.put("priority",task.getPriority()); value.put("status",task.getStatus());
        value.put("handshake_step",task.getHandshake_step()); value.put("error_code",task.getError_code());
        value.put("retry_count",task.getRetry_count()); value.put("max_retry",task.getMax_retry());
        value.put("create_time",task.getCreate_time()); value.put("last_update_time",task.getLast_update_time());
        value.put("dispatch_time",task.getDispatch_time()); value.put("finish_time",task.getFinish_time());
        value.put("payload",task.getPayload()); value.put("result",task.getResult());
        return value;
    }
}
