package com.cayleywcs.openapi;

import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.task.entity.TaskChainEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenPublicViews {
    private OpenPublicViews() {}
    public static Map<String,Object> alarm(AlarmEntity row) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("id",row.getId()); value.put("app_id",row.getApp_id()); value.put("fault_code",row.getFault_code());
        value.put("level",row.getLevel()); value.put("message",row.getMessage()); value.put("suggestion",row.getSuggestion());
        value.put("status",row.getStatus()); value.put("raised_time",row.getRaised_time()); value.put("ack_by",row.getAck_by());
        value.put("ack_time",row.getAck_time()); value.put("cleared_time",row.getCleared_time());
        value.put("create_time",row.getCreate_time()); value.put("last_update_time",row.getLast_update_time());
        return value;
    }
    public static Map<String,Object> chain(TaskChainEntity row) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("id",row.getId()); value.put("chain_no",row.getChain_no()); value.put("status",row.getStatus());
        value.put("priority",row.getPriority()); value.put("select_strategy",row.getSelect_strategy());
        value.put("wms_ref",row.getWms_ref()); value.put("payload",row.getPayload()); value.put("error_message",row.getError_message());
        value.put("create_time",row.getCreate_time()); value.put("last_update_time",row.getLast_update_time());
        value.put("finish_time",row.getFinish_time());
        value.put("segments",row.getSegments()==null?List.of():row.getSegments().stream().map(segment->{
            Map<String,Object> publicSegment=new LinkedHashMap<>();
            for (String name:List.of("seq","appId","candidateAppIds","payload")) if(segment.containsKey(name)) publicSegment.put(name,segment.get(name));
            return publicSegment;
        }).toList());
        return value;
    }
}
