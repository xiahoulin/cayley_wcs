package com.cayleywcs.telemetry.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import java.util.Map;

@TableName(value = "\"wcs_sensor_gateway\"", autoResultMap = true)
public class SensorGatewayEntity extends BaseEntity {
    private Long agv_app_id;
    private String endpoint_ref;
    private Boolean enabled;
    private Integer infrared_offset;
    private Double infrared_min;
    private Double infrared_max;
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private Long power_on_at;
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String power_evidence;
    private Long config_version;
    public Long getAgv_app_id() { return agv_app_id; }
    public void setAgv_app_id(Long value) { agv_app_id = value; }
    public String getEndpoint_ref() { return endpoint_ref; }
    public void setEndpoint_ref(String value) { endpoint_ref = value; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { enabled = value; }
    public Integer getInfrared_offset() { return infrared_offset; }
    public void setInfrared_offset(Integer value) { infrared_offset = value; }
    public Double getInfrared_min() { return infrared_min; }
    public void setInfrared_min(Double value) { infrared_min = value; }
    public Double getInfrared_max() { return infrared_max; }
    public void setInfrared_max(Double value) { infrared_max = value; }
    public Long getPower_on_at() { return power_on_at; }
    public void setPower_on_at(Long value) { power_on_at = value; }
    public String getPower_evidence() { return power_evidence; }
    public void setPower_evidence(String value) { power_evidence = value; }
    public Long getConfig_version() { return config_version; }
    public void setConfig_version(Long value) { config_version = value; }
}
