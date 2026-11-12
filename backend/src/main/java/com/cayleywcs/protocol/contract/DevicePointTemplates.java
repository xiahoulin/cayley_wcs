package com.cayleywcs.protocol.contract;

import com.cayleywcs.adapter.PointTypeRegistry;
import com.cayleywcs.adapter.seer.SeerPointCatalog;
import com.cayleywcs.telemetry.profile.SensorPointCatalog;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit driver contracts for the implemented device families. Reserved dictionary types are excluded. */
public final class DevicePointTemplates {
    public static final Set<String> CODES = Set.of("stacker", "conveyor", "lift", "auto_door", "agv", "vibration_sensor", "noise_sensor", "infrared_sensor");
    public static final List<String> STACKER_REQUEST = List.of("cmd_TaskType", "cmd_TakeCoor_Row", "cmd_TakeCoor_Column", "cmd_TakeCoor_Floor", "cmd_PutCoor_Row", "cmd_PutCoor_Column", "cmd_PutCoor_Floor", "cmd_PortNum");
    public static final List<String> STACKER_RESPONSE = List.of("status_Mode", "status_Task", "status_TaskTypeFeedback", "status_CurrentColumnNum", "status_CurrentFloorNum", "status_Speed_Lift", "status_Speed_Walk", "status_Speed_Fork", "status_CurrentPos_Walk", "status_CurrentPos_Lift", "status_CurrentPos_Fork", "status_TaskNum", "status_Cargo", "status_ErrorCode");
    private DevicePointTemplates() { }
    public static String template(String deviceType) { return "palletizer".equals(deviceType) ? "stacker" : deviceType; }
    public static List<String> request(String code) {
        if (SensorPointCatalog.isSensor(code)) return List.of();
        return switch (code) {
            case "agv" -> List.of();
            case "stacker" -> STACKER_REQUEST;
            case "conveyor" -> List.of("cmd_DestStation");
            case "lift" -> List.of("cmd_TargetFloor");
            case "auto_door" -> List.of("cmd_Open", "cmd_Close");
            default -> throw new IllegalArgumentException("未实现设备模板: " + code);
        };
    }
    public static List<String> response(String code) {
        if (SensorPointCatalog.isSensor(code)) return SensorPointCatalog.metrics(code);
        return switch (code) {
            case "agv" -> List.of("seer_location", "seer_speed", "seer_navigation");
            case "stacker" -> STACKER_RESPONSE;
            case "conveyor" -> List.of("status_Mode", "status_Task", "status_TaskNum", "status_Position", "status_Occupied", "status_ErrorCode");
            case "lift" -> List.of("status_Mode", "status_Task", "status_TaskNum", "status_CurrentFloor", "status_DoorState", "status_ErrorCode");
            case "auto_door" -> List.of("status_Mode", "status_DoorState", "status_ErrorCode");
            default -> throw new IllegalArgumentException("未实现设备模板: " + code);
        };
    }
    /** The driver owns the I/O sequence for these points; direction and purpose are validated below. */
    public static Set<String> internal(String code) {
        if ("agv".equals(code) || SensorPointCatalog.isSensor(code)) return Set.of();
        Set<String> required = new LinkedHashSet<>();
        required.add("WCS_Heart"); required.add("PLC_Heart");
        switch (code) {
            case "stacker" -> { required.add("cmd_TaskNum"); required.add("cmd_ConfirmTask"); }
            case "conveyor" -> { required.add("cmd_TaskNum"); required.add("cmd_Start"); }
            case "lift" -> { required.add("cmd_TaskNum"); required.add("cmd_Go"); }
            case "auto_door" -> { }
            default -> throw new IllegalArgumentException("未实现设备模板: " + code);
        }
        return java.util.Collections.unmodifiableSet(required);
    }

    /** Publication must satisfy the implemented numeric state machines, even when two applications agree. */
    public static void validateHandlerContract(String code, List<ProtocolPointEntity> points) {
        if (SensorPointCatalog.isSensor(code) || "agv".equals(code) && points.stream().anyMatch(p->Long.valueOf(2).equals(p.getTemplate_version()) || Long.valueOf(3).equals(p.getTemplate_version()))) {
            var expected = SensorPointCatalog.isSensor(code) ? SensorPointCatalog.points(null,code)
                    : points.stream().anyMatch(p->Long.valueOf(3).equals(p.getTemplate_version())) ? SeerPointCatalog.navigationContract(null) : SeerPointCatalog.points(null);
            if(points.size()!=expected.size()) throw new WcsException(4223,"只读点位集合不完整");
            for(var point:points) {
                var reference=expected.stream().filter(p->p.getField_name().equals(point.getField_name())).findFirst().orElse(null);
                if(reference==null || !java.util.Objects.equals(reference.getTemplate_version(),point.getTemplate_version())
                    || !reference.getDirection().equals(point.getDirection()) || !reference.getPoint_scope().equals(point.getPoint_scope())
                    || !reference.getLogical_type().equals(point.getLogical_type()) || !reference.getData_type().equals(point.getData_type())
                    || !reference.getAddress().equals(point.getAddress()) || !reference.getNullable().equals(point.getNullable()))
                    unsupported(point,"必须使用已定义的应用点位及映射");
            }
            return;
        }
        if ("agv".equals(code)) {
            for (var point : points) {
                Integer api = com.cayleywcs.adapter.seer.SeerTcpAdapter.FIELDS.get(point.getField_name());
                if (api == null || !"response".equals(point.getDirection()) || !"business".equals(point.getPoint_scope())
                        || !"STRING".equals(point.getLogical_type()) || Boolean.TRUE.equals(point.getNullable())
                        || !Integer.toString(api).equals(point.getAddress())) unsupported(point, "AGV 仅支持已定义的只读 JSON 状态点位");
            }
            return;
        }
        for (ProtocolPointEntity point : points) {
            String field=point.getField_name();
            boolean business="business".equals(point.getPoint_scope());
            boolean driver=internal(code).contains(field) || "cmd_ResetFault".equals(field);
            if (!business && !driver) continue;
            if (driver) {
                String direction=field.equals("PLC_Heart")?"response":"request";
                if (!"internal".equals(point.getPoint_scope()) || !direction.equals(point.getDirection())) unsupported(point,"驱动点位必须为internal/"+direction);
            }
            if (Boolean.TRUE.equals(point.getNullable())) unsupported(point,"设备处理器必需点位不能允许空值");
            String logical=PointTypeRegistry.canonical("http",point.getLogical_type());
            if (field.equals("cmd_TaskNum") || field.equals("status_TaskNum")) {
                if (!Set.of("INT8","UINT8","INT16","UINT16","INT32","UINT32","INT64","UINT64").contains(logical)) unsupported(point,"任务号必须是无损整数");
                var low=bound(point,"min");var high=bound(point,"max");
                if (low.compareTo(BigDecimal.valueOf(field.equals("cmd_TaskNum")?1:0))<0 || high.compareTo(low)<0 || high.compareTo(BigDecimal.valueOf(Long.MAX_VALUE))>0 || (field.equals("status_TaskNum") && low.signum()!=0)) unsupported(point,"任务号范围必须完整位于处理器可无损表达的非负long范围，命令最小值至少1，反馈须包含0");
                if (field.equals("cmd_TaskNum")) {
                    if (point.getConstraints().containsKey("enum")) unsupported(point,"任务号分配不支持枚举约束，请使用范围和reserved_values");
                    if (point.getConstraints().get("reserved_values") instanceof List<?> reserved) {
                        long taken=reserved.stream().map(v->new BigDecimal(v.toString())).filter(v->v.compareTo(low)>=0&&v.compareTo(high)<=0).distinct().count();
                        if (high.subtract(low).add(BigDecimal.ONE).compareTo(BigDecimal.valueOf(taken))<=0) unsupported(point,"任务号范围中没有可预约号码");
                    }
                }
                continue;
            }
            String expected="INT16";
            if (code.equals("stacker") && (field.startsWith("status_Speed_") || field.startsWith("status_CurrentPos_"))) expected="FLOAT32";
            if (code.equals("stacker") && field.equals("status_ErrorCode")) expected="ARRAY<INT16>";
            if (!expected.equals(logical)) unsupported(point,"当前设备处理器要求逻辑类型 "+expected);
            if (expected.equals("ARRAY<INT16>")) {
                Map<String,Object> c=point.getConstraints();
                if (!(c.get("dimensions") instanceof List<?> dims) || !PointContractValidator.equivalent(dims,List.of(60)) || !PointContractValidator.equivalent(c.get("length"),60)) unsupported(point,"堆垛机故障反馈必须是60个INT16的一维数组");
                continue;
            }
            if (expected.equals("FLOAT32")) continue;
            List<Integer> values=switch(field) {
                case "WCS_Heart","PLC_Heart","cmd_Start","cmd_Go","cmd_Open","cmd_Close","cmd_ResetFault","status_Occupied" -> List.of(0,1);
                case "cmd_ConfirmTask","status_Task","status_Cargo" -> List.of(0,1,2);
                case "status_Mode" -> List.of(0,1,2,3,4);
                case "cmd_TaskType" -> List.of(1,2,3,4,5,6);
                case "status_TaskTypeFeedback" -> List.of(0,1,2,3,4,5,6);
                case "status_DoorState" -> code.equals("auto_door")?List.of(0,1,2,3):List.of(0,1);
                default -> null;
            };
            if (values!=null) {
                Map<String,Object> c=point.getConstraints();
                if (!PointContractValidator.equivalent(c.get("min"),values.getFirst()) || !PointContractValidator.equivalent(c.get("max"),values.getLast()) || !sameValues(c.get("enum"),values)) unsupported(point,"状态机完整枚举必须为 "+values);
            } else bounded(point,field.equals("cmd_DestStation")||field.equals("cmd_TargetFloor")?1:0,Short.MAX_VALUE);
        }
        ProtocolPointEntity command=points.stream().filter(p->p.getField_name().equals("cmd_TaskNum")).findFirst().orElse(null);
        ProtocolPointEntity feedback=points.stream().filter(p->p.getField_name().equals("status_TaskNum")).findFirst().orElse(null);
        if (command!=null && feedback!=null && bound(feedback,"max").compareTo(bound(command,"max"))<0) unsupported(command,"任务号分配范围超过设备反馈可表达的范围");
    }
    private static boolean sameValues(Object actual,List<Integer> required) {
        if (!(actual instanceof List<?> list) || list.size()!=required.size()) return false;
        try {return list.stream().map(v->new BigDecimal(v.toString()).stripTrailingZeros()).collect(java.util.stream.Collectors.toSet()).equals(required.stream().map(BigDecimal::valueOf).map(BigDecimal::stripTrailingZeros).collect(java.util.stream.Collectors.toSet()));}
        catch(NumberFormatException ex){return false;}
    }
    private static BigDecimal bound(ProtocolPointEntity point,String key) {
        Object value=point.getConstraints().get(key);
        if (value==null) unsupported(point,"处理器数值点位必须明确min/max值域");
        try {var result=new BigDecimal(value.toString());result.toBigIntegerExact();return result;}
        catch(ArithmeticException|NumberFormatException ex){unsupported(point,"处理器数值点位范围必须是整数");return BigDecimal.ZERO;}
    }
    private static void bounded(ProtocolPointEntity point,long minimum,long maximum) {
        var low=bound(point,"min");var high=bound(point,"max");
        if (low.compareTo(BigDecimal.valueOf(minimum))!=0 || high.compareTo(BigDecimal.valueOf(minimum))<=0 || high.compareTo(BigDecimal.valueOf(maximum))>0) unsupported(point,"当前处理器数值范围要求min="+minimum+"，max在有效整数范围内");
    }
    private static void unsupported(ProtocolPointEntity point,String message) {throw new WcsException(4223,point.getField_name()+": "+message);}
}
