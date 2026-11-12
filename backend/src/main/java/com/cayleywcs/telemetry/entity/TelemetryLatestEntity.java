package com.cayleywcs.telemetry.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import java.util.Map;

@TableName(value = "\"wcs_telemetry_latest\"", autoResultMap = true)
public class TelemetryLatestEntity extends BaseEntity {
    private Long gateway_id;
    private String metric;
    private Long observed_at;
    private Long config_version;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sample;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> last_good;
    public Long getGateway_id() { return gateway_id; }
    public void setGateway_id(Long value) { gateway_id = value; }
    public String getMetric() { return metric; }
    public void setMetric(String value) { metric = value; }
    public Long getObserved_at() { return observed_at; }
    public void setObserved_at(Long value) { observed_at = value; }
    public Long getConfig_version() { return config_version; }
    public void setConfig_version(Long value) { config_version = value; }
    public Map<String, Object> getSample() { return sample; }
    public void setSample(Map<String, Object> value) { sample = value; }
    public Map<String, Object> getLast_good() { return last_good; }
    public void setLast_good(Map<String, Object> value) { last_good = value; }
}
