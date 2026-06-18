package com.cayleywcs.dict.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cayleywcs.common.entity.BaseEntity;

@TableName("\"wcs_dict_item\"")
public class DictItemEntity extends BaseEntity {
    private String type_code;
    private String item_code;
    private String item_name;
    private String item_value;
    private Long sort;
    private String remark;

    public String getType_code() {
        return type_code;
    }

    public void setType_code(String type_code) {
        this.type_code = type_code;
    }

    public String getItem_code() {
        return item_code;
    }

    public void setItem_code(String item_code) {
        this.item_code = item_code;
    }

    public String getItem_name() {
        return item_name;
    }

    public void setItem_name(String item_name) {
        this.item_name = item_name;
    }

    public String getItem_value() {
        return item_value;
    }

    public void setItem_value(String item_value) {
        this.item_value = item_value;
    }

    public Long getSort() {
        return sort;
    }

    public void setSort(Long sort) {
        this.sort = sort;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
