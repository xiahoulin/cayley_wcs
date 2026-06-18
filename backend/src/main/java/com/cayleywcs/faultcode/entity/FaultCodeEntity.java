package com.cayleywcs.faultcode.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cayleywcs.common.entity.BaseEntity;

/** 故障码表（需求 6）：某协议下 code → 级别/名称/信息/处置建议。 */
@TableName("\"wcs_fault_code\"")
public class FaultCodeEntity extends BaseEntity {
    private Long protocol_id;
    private Long code;
    private String level;
    private String name;
    private String message;
    private String suggestion;

    public Long getProtocol_id() {
        return protocol_id;
    }

    public void setProtocol_id(Long protocol_id) {
        this.protocol_id = protocol_id;
    }

    public Long getCode() {
        return code;
    }

    public void setCode(Long code) {
        this.code = code;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }
}
