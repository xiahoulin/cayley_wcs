package com.cayleywcs.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginRequest(
        @JsonProperty("user_name") String userName,
        String password
) {
}
