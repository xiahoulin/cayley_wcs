package com.cayleywcs.adapter;

import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.telemetry.profile.SensorPointCatalog;
import java.util.List;
import java.util.Set;

public final class ApplicationAdapterCatalog {
    public record Option(String code, String name, String protocolType, Set<String> deviceTypes) { }
    public static final List<Option> OPTIONS = List.of(
            new Option("standard", "通用协议适配器", "", Set.of()),
            new Option("seer_agv", "仙工 AGV 适配器", "tcp", Set.of("agv")),
            new Option("dr164_sensor", "DR164 共享采集适配器", "tcp", SensorPointCatalog.UNITS.keySet()));
    private ApplicationAdapterCatalog() { }
    public static String type(ApplicationEntity app) {
        return app.getAdapter_type() == null || app.getAdapter_type().isBlank() ? "standard" : app.getAdapter_type();
    }
    public static void validate(ApplicationEntity app, String protocol) {
        String type = type(app);
        if("seer_agv".equals(type)) com.cayleywcs.adapter.seer.SeerConnectionSettings.validate(app.getConn_params());
        Option option = OPTIONS.stream().filter(o -> o.code().equals(type)).findFirst()
                .orElseThrow(() -> new WcsException(4223, "未注册的应用适配器: " + type));
        if (!"standard".equals(type) && (!option.protocolType().equals(protocol)
                || app.getDevice_type()==null || !option.deviceTypes().contains(app.getDevice_type()) || !"downstream".equals(app.getDirection())))
            throw new WcsException(4223, "应用适配器与协议或设备类型不匹配");
        if (app.getDevice_type()!=null && SensorPointCatalog.isSensor(app.getDevice_type()) && !"dr164_sensor".equals(type))
            throw new WcsException(4223, "传感器应用必须选择 DR164 共享采集适配器");
    }
}
