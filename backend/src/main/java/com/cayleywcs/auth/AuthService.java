package com.cayleywcs.auth;

import com.cayleywcs.system.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {
    private final UserMapper userMapper;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final int accessTokenMinutes;
    private final int refreshTokenMinutes;

    public AuthService(
            UserMapper userMapper,
            PasswordService passwordService,
            JwtService jwtService,
            RefreshTokenStore refreshTokenStore,
            @Value("${cayleywcs.auth.access-token-minutes}") int accessTokenMinutes,
            @Value("${cayleywcs.auth.refresh-token-minutes}") int refreshTokenMinutes
    ) {
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.accessTokenMinutes = accessTokenMinutes;
        this.refreshTokenMinutes = refreshTokenMinutes;
    }

    public Optional<LoginResponse> login(LoginRequest request) {
        LoginUserRow user = userMapper.findLoginUser(request.userName());
        if (user == null || !passwordService.matches(request.password(), user.getAuthString())) {
            return Optional.empty();
        }
        String accessToken = jwtService.generateToken(user, accessTokenMinutes);
        String refreshToken = jwtService.generateRefreshToken();
        refreshTokenStore.store(user.getUserId(), refreshToken, refreshTokenMinutes);
        return Optional.of(LoginResponse.from(user, accessTokenMinutes, accessToken, refreshToken));
    }

    public Optional<String> refreshToken(RefreshTokenRequest request) {
        JwtUser jwtUser = jwtService.parse(request.accessToken()).orElse(null);
        if (jwtUser == null || !refreshTokenStore.exists(jwtUser.userId(), request.refreshToken())) {
            return Optional.empty();
        }
        return Optional.of(jwtService.generateToken(jwtUser, accessTokenMinutes));
    }
}
