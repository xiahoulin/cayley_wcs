package com.cayleywcs.telemetry.profile;

import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.math.BigDecimal;
import java.util.*;

public final class SensorPointCatalog {
    public static final String PROTOCOL="tcp";
    public static final Map<String,Integer> UNITS=Map.of("vibration_sensor",80,"noise_sensor",1,"infrared_sensor",2);
    public static final Map<String,String> NAMES=Map.of("vibration_sensor","振动传感器","noise_sensor","噪声传感器","infrared_sensor","红外温度传感器");
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("vibration_speed_x","X 轴振动速度"),Map.entry("vibration_speed_y","Y 轴振动速度"),Map.entry("vibration_speed_z","Z 轴振动速度"),
        Map.entry("vibration_chip_temperature","振动芯片温度"),Map.entry("vibration_displacement_x","X 轴振动位移"),
        Map.entry("vibration_displacement_y","Y 轴振动位移"),Map.entry("vibration_displacement_z","Z 轴振动位移"),
        Map.entry("vibration_frequency_x","X 轴振动频率"),Map.entry("vibration_frequency_y","Y 轴振动频率"),Map.entry("vibration_frequency_z","Z 轴振动频率"),
        Map.entry("noise","噪声声级"),Map.entry("infrared_temperature","红外测量温度"));
    public static boolean isSensor(String type) { return UNITS.containsKey(type); }
    public static List<String> metrics(String kind) {
        Integer unit=UNITS.get(kind);if(unit==null)return List.of();
        return SensorProfile.plan(0).stream().filter(block->block.unit()==unit).flatMap(block->block.metrics().stream()).toList();
    }
    public static String address(String kind,String metric) {
        for(var block:SensorProfile.plan(0))if(Objects.equals(UNITS.get(kind),block.unit()) && block.metrics().contains(metric))
            return block.unit()+":"+(kind.equals("infrared_sensor")?"profile.infrared_offset":String.format("0x%04X",block.offset()+block.metrics().indexOf(metric)));
        throw new IllegalArgumentException("传感器点位不匹配");
    }
    public static List<ProtocolPointEntity> points(Long protocolId,String kind) {
        List<ProtocolPointEntity> points=new ArrayList<>();
        for(String metric:metrics(kind)) {
            var p=new ProtocolPointEntity();p.setProtocol_id(protocolId);p.setField_name(metric);p.setSymbol_name(LABELS.get(metric));
            p.setTemplate_code(kind);p.setTemplate_version(1L);p.setDirection("response");p.setOperation("result");p.setPoint_scope("business");
            p.setAddress(address(kind,metric));p.setLogical_type("FLOAT64");p.setData_type("FLOAT64");p.setScale(BigDecimal.ONE);
            p.setRequired(true);p.setNullable(true);p.setSort((long)points.size());p.setConstraints(Map.of("unit",SensorProfile.unit(metric)));
            p.setDescription("只读测量；由共享 DR164 总线批量读取并按传感器档案换算，未知和预热值不作为有效测量。");points.add(p);
        }
        return points;
    }
    private SensorPointCatalog() { }
}
