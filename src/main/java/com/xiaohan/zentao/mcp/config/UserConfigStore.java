package com.xiaohan.zentao.mcp.config;

import com.xiaohan.zentao.mcp.util.AtomicFiles;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class UserConfigStore {
    // config.json 含明文凭据，任何读取路径都不得记录或返回其原始内容。
    private static final String CONFIG_DIRECTORY = ".zentao-mcp";
    private static final String CONFIG_FILE = "config.json";

    private final Path configPath;
    private final ObjectMapper objectMapper;

    public UserConfigStore() {
        this(defaultConfigPath());
    }

    public UserConfigStore(Path configPath) {
        this.configPath = Objects.requireNonNull(configPath, "configPath")
            .toAbsolutePath()
            .normalize();
        this.objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }

    public Path configPath() {
        return configPath;
    }

    public synchronized Optional<UserConfig> load() {
        if (!Files.isRegularFile(configPath) || !Files.isReadable(configPath)) {
            return Optional.empty();
        }
        // 缺失、不可读或损坏的配置都按“尚未配置”处理，避免阻止服务启动。
        try {
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            UserConfig config = objectMapper.readValue(json, UserConfig.class);
            return Optional.ofNullable(config);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public synchronized UserConfig save(String baseUrl, String account, String password) {
        return save(new UserConfig(baseUrl, account, password, null));
    }

    public synchronized UserConfig save(UserConfig config) {
        Objects.requireNonNull(config, "config");
        UserConfig stamped = new UserConfig(
            config.baseUrl(),
            config.account(),
            config.password(),
            Instant.now()
        );

        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(stamped);
            AtomicFiles.writeString(configPath, json + System.lineSeparator(), StandardCharsets.UTF_8);
            return stamped;
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存禅道配置: " + configPath, exception);
        }
    }

    private static Path defaultConfigPath() {
        String userProfile = System.getenv("USERPROFILE");
        Path home = userProfile != null && !userProfile.isBlank()
            ? Path.of(userProfile)
            : Path.of(System.getProperty("user.home"));
        return home.resolve(CONFIG_DIRECTORY).resolve(CONFIG_FILE);
    }
}
