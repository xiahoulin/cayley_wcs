package com.cayleywcs.auth;

import com.cayleywcs.common.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        return authService.login(request)
                .<ApiResponse<LoginResponse>>map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error("login_failed"));
    }

    @PostMapping("/refresh-token")
    ApiResponse<String> refreshToken(@RequestBody RefreshTokenRequest request) {
        return authService.refreshToken(request)
                .<ApiResponse<String>>map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error("refreshtoken_failure"));
    }
}
