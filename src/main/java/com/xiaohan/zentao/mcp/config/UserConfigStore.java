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

/**
 * 读取和保存当前用户的禅道配置。
 *
 * <p>默认配置位于用户目录下的 {@code .zentao-mcp/config.json}。密码按项目约定
 * 以明文保存，因此调用方不得把配置内容输出到日志。读操作采用宽容策略，损坏或
 * 不可读的文件等同于没有配置；写操作则明确抛出异常，避免调用方误以为已保存。</p>
 *
 * <p>公开读写方法使用同步锁保护，防止同一进程内的并发登录相互覆盖配置文件。</p>
 */
public final class UserConfigStore {
    private static final String CONFIG_DIRECTORY = ".zentao-mcp";
    private static final String CONFIG_FILE = "config.json";

    private final Path configPath;
    private final ObjectMapper objectMapper;

    /** 使用当前用户的默认配置路径创建存储。 */
    public UserConfigStore() {
        this(defaultConfigPath());
    }

    /**
     * 使用指定配置文件创建存储，主要便于替换默认目录或隔离调用环境。
     *
     * @param configPath 配置文件路径；会立即转换为规范化的绝对路径
     */
    public UserConfigStore(Path configPath) {
        this.configPath = Objects.requireNonNull(configPath, "configPath")
            .toAbsolutePath()
            .normalize();
        // 忽略未来版本新增的 JSON 字段，使旧客户端仍能读取向前扩展的配置。
        this.objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }

    /**
     * 返回实际使用的配置文件路径。
     *
     * @return 规范化后的绝对路径
     */
    public Path configPath() {
        return configPath;
    }

    /**
     * 尝试读取已保存的用户配置。
     *
     * <p>文件不存在、不可读、JSON 格式损坏或字段无法反序列化时均返回空结果。
     * 这种宽容行为让服务仍可启动，并提示用户重新调用登录工具。</p>
     *
     * @return 成功解析的配置；没有可用配置时返回 {@link Optional#empty()}
     */
    public synchronized Optional<UserConfig> load() {
        if (!Files.isRegularFile(configPath) || !Files.isReadable(configPath)) {
            return Optional.empty();
        }
        try {
            // 配置文件固定按 UTF-8 读写，避免受 Windows 系统代码页影响。
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            UserConfig config = objectMapper.readValue(json, UserConfig.class);
            return Optional.ofNullable(config);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * 根据刚刚验证成功的登录参数创建并保存配置。
     *
     * @param baseUrl 禅道站点根地址
     * @param account 登录账号
     * @param password 登录密码，将按项目约定明文保存
     * @return 带有当前登录时间的最终配置
     */
    public synchronized UserConfig save(String baseUrl, String account, String password) {
        return save(new UserConfig(baseUrl, account, password, null));
    }

    /**
     * 持久化明文凭据，并把最近登录时间更新为当前 UTC 时间。
     *
     * <p>调用方必须先确认登录成功。文件通过同目录临时文件和原子替换写入，
     * 尽量避免进程中断后只留下半份 JSON。</p>
     *
     * @param config 要保存的凭据配置
     * @return 实际写入文件、且已更新时间戳的配置
     * @throws IllegalStateException 创建目录、序列化或写文件失败时抛出
     */
    public synchronized UserConfig save(UserConfig config) {
        Objects.requireNonNull(config, "config");
        UserConfig stamped = new UserConfig(
            config.baseUrl(),
            config.account(),
            config.password(),
            Instant.now()
        );

        try {
            // 首次运行时配置目录可能尚不存在，需要在原子写入前创建。
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

    /**
     * 解析 Windows 用户目录，并构造默认配置文件位置。
     *
     * <p>优先使用 {@code USERPROFILE}，非 Windows 或环境变量缺失时退回 Java 的
     * {@code user.home} 系统属性。</p>
     */
    private static Path defaultConfigPath() {
        String userProfile = System.getenv("USERPROFILE");
        Path home = userProfile != null && !userProfile.isBlank()
            ? Path.of(userProfile)
            : Path.of(System.getProperty("user.home"));
        return home.resolve(CONFIG_DIRECTORY).resolve(CONFIG_FILE);
    }
}
