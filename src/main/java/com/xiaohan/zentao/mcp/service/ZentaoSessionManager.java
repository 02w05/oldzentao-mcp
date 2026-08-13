package com.xiaohan.zentao.mcp.service;

import com.xiaohan.zentao.mcp.client.ZentaoClient;
import com.xiaohan.zentao.mcp.config.UserConfig;
import com.xiaohan.zentao.mcp.config.UserConfigStore;
import com.xiaohan.zentao.mcp.storage.DataPaths;
import com.xiaohan.zentao.mcp.storage.DetailStore;
import com.xiaohan.zentao.mcp.storage.DownloadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class ZentaoSessionManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZentaoSessionManager.class);

    private final UserConfigStore configStore;
    private final DataPaths dataPaths;
    private final DetailStore detailStore;
    private final DownloadService downloadService;

    // 登录、配置落盘和活动客户端发布必须在同一锁内完成。
    private final Object lock = new Object();

    private volatile ZentaoClient client;

    // 保存的凭据无效时只自动尝试一次，避免每次工具调用都形成登录风暴。
    private boolean autoLoginAttempted;

    public ZentaoSessionManager() {
        this(new UserConfigStore(), new DataPaths(), new DownloadService());
    }

    public ZentaoSessionManager(
        UserConfigStore configStore,
        DataPaths dataPaths,
        DownloadService downloadService
    ) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.dataPaths = Objects.requireNonNull(dataPaths, "dataPaths");
        this.downloadService = Objects.requireNonNull(downloadService, "downloadService");
        this.detailStore = new DetailStore(dataPaths);
    }

    public Optional<UserConfig> loadSavedConfig() {
        return configStore.load();
    }

    public boolean loginAndSave(String baseUrl, String account, String password) {
        synchronized (lock) {
            ZentaoClient candidate = createClient(baseUrl);
            if (!candidate.login(account, password)) {
                return false;
            }
            configStore.save(baseUrl, account, password);
            client = candidate;
            autoLoginAttempted = true;
            return true;
        }
    }

    public ZentaoClient requireClient() {
        ZentaoClient current = client;
        if (current != null) {
            return current;
        }

        synchronized (lock) {
            // 获取锁前可能已有其他线程完成登录，因此必须再次检查。
            if (client != null) {
                return client;
            }
            if (!autoLoginAttempted) {
                autoLoginAttempted = true;
                Optional<UserConfig> saved = configStore.load();
                if (saved.isPresent() && saved.get().hasCredentials()) {
                    UserConfig config = saved.get();
                    try {
                        ZentaoClient candidate = createClient(config.baseUrl());
                        if (candidate.login(config.account(), config.password())) {
                            client = candidate;
                            LOGGER.info("自动登录成功");
                        } else {
                            LOGGER.warn("自动登录失败");
                        }
                    } catch (RuntimeException exception) {
                        LOGGER.warn("自动登录失败: {}", exception.getMessage());
                    }
                }
            }
            if (client == null) {
                throw new IllegalStateException("请先使用 zentao_login 工具登录禅道系统");
            }
            return client;
        }
    }

    public Path configPath() {
        return configStore.configPath();
    }

    public DataPaths dataPaths() {
        return dataPaths;
    }

    @Override
    public void close() {
        downloadService.close();
    }

    private ZentaoClient createClient(String baseUrl) {
        return new ZentaoClient(baseUrl, detailStore, downloadService);
    }
}
