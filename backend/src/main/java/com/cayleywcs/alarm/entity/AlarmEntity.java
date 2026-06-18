package com.cayleywcs.alarm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cayleywcs.common.entity.BaseEntity;
import java.time.LocalDateTime;

/** 报警（wcs_alarm）。fault_code>0 为设备故障；fault_code=-1 为通讯断开类。 */
@TableName("\"wcs_alarm\"")
public class AlarmEntity extends BaseEntity {
    private Long app_id;
    private Long fault_code;
    private String level;
    private String message;
    private String suggestion;
    private String status;
    private LocalDateTime raised_time;
    private String ack_by;
    private LocalDateTime ack_time;
    private LocalDateTime cleared_time;

    public Long getApp_id() {
        return app_id;
    }

    public void setApp_id(Long app_id) {
        this.app_id = app_id;
    }

    public Long getFault_code() {
        return fault_code;
    }

    public void setFault_code(Long fault_code) {
        this.fault_code = fault_code;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getRaised_time() {
        return raised_time;
    }

    public void setRaised_time(LocalDateTime raised_time) {
        this.raised_time = raised_time;
    }

    public String getAck_by() {
        return ack_by;
    }

    public void setAck_by(String ack_by) {
        this.ack_by = ack_by;
    }

    public LocalDateTime getAck_time() {
        return ack_time;
    }

    public void setAck_time(LocalDateTime ack_time) {
        this.ack_time = ack_time;
    }

    public LocalDateTime getCleared_time() {
        return cleared_time;
    }

    public void setCleared_time(LocalDateTime cleared_time) {
        this.cleared_time = cleared_time;
    }
}
