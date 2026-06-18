package com.cayleywcs.auth;

public record RefreshTokenRequest(
        String accessToken,
        String refreshToken
) {
}
