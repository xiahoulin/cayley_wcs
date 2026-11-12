package com.cayleywcs.openapi;

import com.cayleywcs.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApplicationTokenController {
    private final ApplicationTokenService tokens;
    public ApplicationTokenController(ApplicationTokenService tokens) { this.tokens = tokens; }

    @PostMapping(value = "/system/token", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "获取对接应用 access_token")
    public ApiResponse<ApplicationTokenService.TokenData> issue(@RequestBody ApplicationTokenService.TokenRequest request) {
        return ApiResponse.success(tokens.issue(request));
    }
}
