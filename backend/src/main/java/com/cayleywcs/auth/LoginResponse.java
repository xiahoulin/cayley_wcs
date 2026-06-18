package com.cayleywcs.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginResponse(
        @JsonProperty("user_num") String userNum,
        @JsonProperty("user_name") String userName,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_role") String userRole,
        @JsonProperty("userrole_id") Long userroleId,
        @JsonProperty("tenant_id") Long tenantId,
        int expire,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken
) {
    static LoginResponse from(LoginUserRow user, int expire, String accessToken, String refreshToken) {
        return new LoginResponse(
                user.getUserNum(),
                user.getUserName(),
                user.getUserId(),
                user.getUserRole(),
                user.getUserroleId(),
                user.getTenantId(),
                expire,
                accessToken,
                refreshToken
        );
    }
}
