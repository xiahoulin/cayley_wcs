package com.cayleywcs.common.support;

import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.common.entity.BaseEntity;
import java.time.LocalDateTime;

/** 填充标准审计列，统一 create/update 行为。 */
public final class Audits {

    private Audits() {
    }

    public static void fillCreate(BaseEntity entity, JwtUser user) {
        LocalDateTime now = LocalDateTime.now();
        entity.setCreator(user == null ? "system" : user.userName());
        entity.setCreate_time(now);
        entity.setLast_update_time(now);
        entity.setTenant_id(user == null ? 1L : user.tenantId());
        entity.setIs_valid(true);
    }

    public static void touch(BaseEntity entity) {
        entity.setLast_update_time(LocalDateTime.now());
    }
}
