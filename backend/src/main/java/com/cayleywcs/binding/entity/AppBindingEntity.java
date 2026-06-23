package com.cayleywcs.binding.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cayleywcs.common.entity.BaseEntity;

/**
 * 应用绑定授权：声明「上位侧应用 upstream → 下位侧应用 downstream」的指挥授权。
 * 不按应用 direction 锁方向：任意应用都可出现在上位侧或下位侧（含「下位→下位」），仅禁止自绑定 (upstream≠downstream)。
 * dispatch 时以 upstream(验签身份) + downstream(body.appId) 查本表，命中且 enabled 才放行。
 */
@TableName(value = "\"wcs_app_binding\"")
public class AppBindingEntity extends BaseEntity {
    private Long upstream_app_id;
    private Long downstream_app_id;
    private String scope;
    private Boolean enabled;
    private String remark;

    public Long getUpstream_app_id() {
        return upstream_app_id;
    }

    public void setUpstream_app_id(Long upstream_app_id) {
        this.upstream_app_id = upstream_app_id;
    }

    public Long getDownstream_app_id() {
        return downstream_app_id;
    }

    public void setDownstream_app_id(Long downstream_app_id) {
        this.downstream_app_id = downstream_app_id;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
