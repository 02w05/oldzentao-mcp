package com.xiaohan.zentao.mcp.config;

import java.time.Instant;

public record UserConfig(
    String baseUrl,
    String account,
    String password,
    Instant lastLogin
) {

    public boolean hasCredentials() {
        return !isBlank(baseUrl) && !isBlank(account) && !isBlank(password);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
