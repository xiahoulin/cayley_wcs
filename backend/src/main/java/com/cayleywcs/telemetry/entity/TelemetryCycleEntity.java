package com.cayleywcs.telemetry.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import java.util.Map;

@TableName(value = "\"wcs_telemetry_cycle\"", autoResultMap = true)
public class TelemetryCycleEntity extends BaseEntity {
    private Long gateway_id;
    private String cycle_id;
    private Long config_version;
    private Long started_at;
    private Long finished_at;
    private Long duration_ms;
    private String connection_state;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> measurements;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> diagnostics;
    public Long getGateway_id() { return gateway_id; }
    public void setGateway_id(Long value) { gateway_id = value; }
    public String getCycle_id() { return cycle_id; }
    public void setCycle_id(String value) { cycle_id = value; }
    public Long getConfig_version() { return config_version; }
    public void setConfig_version(Long value) { config_version = value; }
    public Long getStarted_at() { return started_at; }
    public void setStarted_at(Long value) { started_at = value; }
    public Long getFinished_at() { return finished_at; }
    public void setFinished_at(Long value) { finished_at = value; }
    public Long getDuration_ms() { return duration_ms; }
    public void setDuration_ms(Long value) { duration_ms = value; }
    public String getConnection_state() { return connection_state; }
    public void setConnection_state(String value) { connection_state = value; }
    public Map<String, Object> getMeasurements() { return measurements; }
    public void setMeasurements(Map<String, Object> value) { measurements = value; }
    public Map<String, Object> getDiagnostics() { return diagnostics; }
    public void setDiagnostics(Map<String, Object> value) { diagnostics = value; }
}
