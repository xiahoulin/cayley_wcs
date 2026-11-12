package com.cayleywcs.adapter.seer;

import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.math.BigDecimal;
import java.util.*;

/** Version 2 exposes typed state fields; packet points remain internal diagnostic/context sources. */
public final class SeerPointCatalog {
    public static final String NAV_STATION="nav_station", NAV_SENT="nav_send_success";
    public static List<ProtocolPointEntity> navigationPoints(Long protocolId) {
        var station=point(protocolId,NAV_STATION,"导航目标站点","STRING","3051:/id","business",false);
        station.setDirection("request");station.setOperation("dispatch");station.setTemplate_version(3L);station.setSort(2000L);
        station.setConstraints(Map.of("encoding","UTF-8","min_length",1,"max_length",128));
        station.setDescription("当前地图站点 ID。通过导航至站点操作发送一次，不作为普通参数写入。");
        var sent=point(protocolId,NAV_SENT,"最近导航下发是否成功","BOOL","3051:/sent","business",true);
        sent.setTemplate_version(3L);sent.setSort(2001L);
        sent.setDescription("收到 3051 成功应答为是，明确拒绝或未发送为否；尚未发送或应答不确定为未知。不代表到站。");
        return List.of(station,sent);
    }
    public static List<ProtocolPointEntity> navigationContract(Long protocolId) {
        var result=points(protocolId);result.forEach(p->p.setTemplate_version(3L));result.addAll(navigationPoints(protocolId));return result;
    }
    public record Field(int api, String key, String label, String type, String unit) {
        public String name() { return (api==1004?"loc_":api==1005?"speed_":"nav_")+key; }
        public String address() { return api+":/"+key; }
    }
    public static final List<Field> FIELDS=List.of(
        f(1004,"x","地图 X 坐标","FLOAT64","m"),f(1004,"y","地图 Y 坐标","FLOAT64","m"),
        f(1004,"angle","车体朝向角","FLOAT64","rad"),f(1004,"confidence","定位置信度","FLOAT64",""),
        f(1004,"current_station","当前所在站点","STRING",""),f(1004,"last_station","上一经过站点","STRING",""),
        f(1004,"loc_method","定位方式代码","INT32",""),f(1004,"loc_state","定位状态代码","INT32",""),
        f(1004,"loc_notify","定位提示信息","STRING",""),f(1004,"in_forbidden_area","是否位于禁行区域","BOOL",""),
        f(1004,"similarity","定位匹配相似度","FLOAT64",""),f(1004,"update_reason","定位更新原因代码","INT32",""),
        f(1004,"correction_errs","定位修正反馈","STRING",""),f(1004,"reliabilities","定位可靠性信息","STRING",""),
        f(1005,"vx","车体 X 方向速度","FLOAT64","m/s"),f(1005,"vy","车体 Y 方向速度","FLOAT64","m/s"),
        f(1005,"w","车体角速度","FLOAT64","rad/s"),f(1005,"is_stop","是否处于停止状态","BOOL",""),
        f(1005,"detect_skid","打滑检测标志","BOOL",""),f(1005,"steer","舵轮转向角","FLOAT64",""),
        f(1005,"steer_angles","各舵轮转向角","ARRAY<FLOAT64>",""),f(1005,"motor_steer_angles","各转向电机角度","ARRAY<FLOAT64>",""),
        f(1005,"spin","旋转反馈量","FLOAT64",""),f(1005,"r_spin","旋转指令量","FLOAT64",""),
        f(1005,"r_vx","接收的 X 方向速度指令","FLOAT64",""),f(1005,"r_vy","接收的 Y 方向速度指令","FLOAT64",""),
        f(1005,"r_w","接收的角速度指令","FLOAT64",""),f(1005,"r_steer","舵轮转向指令","FLOAT64",""),
        f(1005,"r_steer_angles","各舵轮转向指令","ARRAY<FLOAT64>",""),
        f(1020,"task_status","导航任务状态","INT32",""),f(1020,"task_type","导航任务类型","INT32",""),
        f(1020,"running_status","导航运行状态代码","INT32",""),f(1020,"target_id","目标站点编号","STRING",""),
        f(1020,"target_label","目标站点名称","STRING",""),f(1020,"target_point","目标位置与朝向","ARRAY<FLOAT64>",""),
        f(1020,"target_dist","距目标的剩余距离","FLOAT64",""),f(1020,"finished_path","已通过站点","ARRAY<STRING>",""),
        f(1020,"unfinished_path","待通过站点","ARRAY<STRING>",""),f(1020,"containers","载具信息","STRING",""),
        f(1020,"move_status_info","运动状态说明","STRING",""));
    private static Field f(int api,String key,String label,String type,String unit) { return new Field(api,key,label,type,unit); }
    public static Field field(String name) { return FIELDS.stream().filter(f->f.name().equals(name)).findFirst().orElse(null); }
    public static List<String> names() { return FIELDS.stream().map(Field::name).toList(); }
    public static List<ProtocolPointEntity> points(Long protocolId) {
        List<ProtocolPointEntity> result=new ArrayList<>();
        for(var field:FIELDS) {
            var p=point(protocolId,field.name(),field.label(),field.type(),field.address(),"business",true);
            Map<String,Object> rules=new LinkedHashMap<>(p.getConstraints());
            if(!field.unit().isEmpty())rules.put("unit",field.unit());
            if(field.key().equals("task_status"))rules.put("enum",List.of(0,1,2,3,4,5,6));
            p.setConstraints(rules);p.setSort((long)result.size());result.add(p);
        }
        SeerTcpAdapter.FIELDS.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(entry->{
            var p=point(protocolId,entry.getKey(),"原始状态报文 "+entry.getValue(),"STRING",entry.getValue().toString(),"internal",false);
            p.setSort(100L+entry.getValue());result.add(p);
        });
        return result;
    }
    private static ProtocolPointEntity point(Long id,String name,String label,String type,String address,String scope,boolean nullable) {
        var p=new ProtocolPointEntity();p.setProtocol_id(id);p.setField_name(name);p.setSymbol_name(label);p.setAddress(address);
        p.setData_type(type);p.setLogical_type(type);p.setTemplate_code("agv");p.setTemplate_version(2L);
        p.setDirection("response");p.setOperation("result");p.setPoint_scope(scope);p.setRequired(true);p.setNullable(nullable);p.setScale(BigDecimal.ONE);
        p.setDescription("仙工只读状态；缺失字段显示未知。"+(type.equals("STRING")?"复杂字段保留 JSON 内容。":""));
        p.setConstraints(type.equals("STRING")?Map.of("encoding","UTF-8","max_length",70000):type.startsWith("ARRAY")?Map.of("max_items",4096):Map.of());
        return p;
    }
    private SeerPointCatalog() { }
}
