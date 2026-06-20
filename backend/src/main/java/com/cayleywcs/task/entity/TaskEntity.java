package com.cayleywcs.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import java.time.LocalDateTime;
import java.util.Map;

/** WCS 任务（wcs_task）。承载 WMS 下发 + 堆垛机三段握手执行态。 */
@TableName(value = "\"wcs_task\"", autoResultMap = true)
public class TaskEntity extends BaseEntity {
    private String task_no;
    private Long app_id;
    private String wms_ref;
    private String task_type;
    private Long take_row;
    private Long take_column;
    private Long take_floor;
    private Long put_row;
    private Long put_column;
    private Long put_floor;
    private Long port_num;
    private Long priority;
    private String status;
    private String handshake_step;
    private Long error_code;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private LocalDateTime dispatch_time;
    private LocalDateTime finish_time;

    public String getTask_no() {
        return task_no;
    }

    public void setTask_no(String task_no) {
        this.task_no = task_no;
    }

    public Long getApp_id() {
        return app_id;
    }

    public void setApp_id(Long app_id) {
        this.app_id = app_id;
    }

    public String getWms_ref() {
        return wms_ref;
    }

    public void setWms_ref(String wms_ref) {
        this.wms_ref = wms_ref;
    }

    public String getTask_type() {
        return task_type;
    }

    public void setTask_type(String task_type) {
        this.task_type = task_type;
    }

    public Long getTake_row() {
        return take_row;
    }

    public void setTake_row(Long take_row) {
        this.take_row = take_row;
    }

    public Long getTake_column() {
        return take_column;
    }

    public void setTake_column(Long take_column) {
        this.take_column = take_column;
    }

    public Long getTake_floor() {
        return take_floor;
    }

    public void setTake_floor(Long take_floor) {
        this.take_floor = take_floor;
    }

    public Long getPut_row() {
        return put_row;
    }

    public void setPut_row(Long put_row) {
        this.put_row = put_row;
    }

    public Long getPut_column() {
        return put_column;
    }

    public void setPut_column(Long put_column) {
        this.put_column = put_column;
    }

    public Long getPut_floor() {
        return put_floor;
    }

    public void setPut_floor(Long put_floor) {
        this.put_floor = put_floor;
    }

    public Long getPort_num() {
        return port_num;
    }

    public void setPort_num(Long port_num) {
        this.port_num = port_num;
    }

    public Long getPriority() {
        return priority;
    }

    public void setPriority(Long priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getHandshake_step() {
        return handshake_step;
    }

    public void setHandshake_step(String handshake_step) {
        this.handshake_step = handshake_step;
    }

    public Long getError_code() {
        return error_code;
    }

    public void setError_code(Long error_code) {
        this.error_code = error_code;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public LocalDateTime getDispatch_time() {
        return dispatch_time;
    }

    public void setDispatch_time(LocalDateTime dispatch_time) {
        this.dispatch_time = dispatch_time;
    }

    public LocalDateTime getFinish_time() {
        return finish_time;
    }

    public void setFinish_time(LocalDateTime finish_time) {
        this.finish_time = finish_time;
    }
}
