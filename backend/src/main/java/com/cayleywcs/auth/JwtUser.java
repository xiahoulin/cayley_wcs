package com.cayleywcs.auth;

public record JwtUser(
        Long userId,
        String userNum,
        String userName,
        String userRole,
        Long tenantId,
        Long userroleId
) {
}
