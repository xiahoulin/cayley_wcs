package com.cayleywcs.common.security;

public record CurrentUser(
        Long userId,
        String userNum,
        String userName,
        String userRole,
        Long tenantId
) {
}
