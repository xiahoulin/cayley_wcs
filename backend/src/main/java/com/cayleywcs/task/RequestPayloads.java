package com.cayleywcs.task;

import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 设备点位公共请求的唯一边界，旧字段只用于代码内及历史数据的显式转换。 */
public final class RequestPayloads {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RequestPayloads() {}

    public static <T> T parse(Map<String,Object> body, Set<String> allowed, Class<T> type) {
        for (String key : body.keySet()) if (!allowed.contains(key))
            throw new WcsException(ErrorCode.TASK_INVALID, "不支持字段 " + key + "，设备点位必须放入 payload");
        try { return JSON.convertValue(body, type); }
        catch (IllegalArgumentException ex) { throw new WcsException(ErrorCode.TASK_INVALID, "请求字段格式错误"); }
    }

    public static Map<String,Object> migrate(String type, Long tr, Long tc, Long tf, Long pr, Long pc, Long pf,
                                              Long port, Map<String,Object> original) {
        Map<String,Object> points = new LinkedHashMap<>();
        if (original != null) points.putAll(original);
        put(points, "cmd_TaskType", type != null && type.matches("\\d+") ? Integer.valueOf(type) : null);
        put(points, "cmd_TakeCoor_Row", tr); put(points,"cmd_TakeCoor_Column",tc); put(points,"cmd_TakeCoor_Floor",tf);
        put(points, "cmd_PutCoor_Row", pr); put(points,"cmd_PutCoor_Column",pc); put(points,"cmd_PutCoor_Floor",pf);
        put(points, "cmd_PortNum", port);
        if (points.containsKey("toStation")) points.put("cmd_DestStation", points.remove("toStation"));
        if (points.containsKey("targetFloor")) points.put("cmd_TargetFloor", points.remove("targetFloor"));
        if (points.containsKey("action")) {
            Object action = points.remove("action");
            if (!("open".equals(action) || "close".equals(action))) throw new WcsException(ErrorCode.TASK_INVALID,"历史门动作不能自动迁移");
            points.put("cmd_Open", "open".equals(action) ? 1 : 0);
            points.put("cmd_Close", "close".equals(action) ? 1 : 0);
        }
        if (points.containsKey("cmd_DestStation") || points.containsKey("cmd_TargetFloor") || points.containsKey("cmd_Open"))
            points.remove("cmd_TaskType");
        points.remove("fromStation");
        return points;
    }
    private static void put(Map<String,Object> target,String key,Object value) {
        if (value == null) return;
        if (target.containsKey(key) && !String.valueOf(value).equals(String.valueOf(target.get(key))))
            throw new WcsException(ErrorCode.CONFLICT,"历史平铺字段与 payload 冲突："+key);
        target.put(key,value);
    }
}
