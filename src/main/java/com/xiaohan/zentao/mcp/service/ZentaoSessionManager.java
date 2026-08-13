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

/**
 * 统一管理当前禅道会话及其关联资源。
 *
 * <p>工具服务不会自行创建 HTTP 客户端，而是通过本类取得已经登录的
 * {@link ZentaoClient}。首次需要客户端时，本类会尝试使用本地配置自动登录一次；
 * 显式调用登录工具则会在验证成功后同时保存配置并发布新会话。</p>
 *
 * <p>{@code client} 使用 {@code volatile} 提供无锁快速读取，创建与替换过程由
 * {@code lock} 串行化。管理器关闭时还会停止共享的后台下载服务。</p>
 */
public final class ZentaoSessionManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZentaoSessionManager.class);

    private final UserConfigStore configStore;
    private final DataPaths dataPaths;
    private final DetailStore detailStore;
    private final DownloadService downloadService;
    // 该锁把登录、保存配置和发布客户端组合成一个进程内事务。
    private final Object lock = new Object();

    // volatile 允许登录完成后的客户端安全发布给随后到达的工具调用。
    private volatile ZentaoClient client;
    // 无论成功与否，保存配置触发的自动登录在一个进程生命周期内只尝试一次。
    private boolean autoLoginAttempted;

    /** 使用默认配置路径、默认数据目录和四线程下载服务创建会话管理器。 */
    public ZentaoSessionManager() {
        this(new UserConfigStore(), new DataPaths(), new DownloadService());
    }

    /**
     * 使用调用方提供的基础组件创建会话管理器。
     *
     * @param configStore 用户配置存储
     * @param dataPaths 详情数据目录解析器
     * @param downloadService 图片和附件后台下载服务
     */
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

    /**
     * 读取本地保存的配置，但不触发登录。
     *
     * @return 可用的配置；文件不存在或损坏时为空
     */
    public Optional<UserConfig> loadSavedConfig() {
        return configStore.load();
    }

    /**
     * 验证一组凭据，并在成功后保存配置和替换活动会话。
     *
     * <p>失败的候选客户端不会影响当前已登录会话。只有远端登录成功、配置也成功
     * 写入后，新的客户端才会对其他线程可见。</p>
     *
     * @param baseUrl 禅道站点根地址
     * @param account 登录账号
     * @param password 登录密码
     * @return 登录且保存成功时返回 {@code true}；远端拒绝凭据时返回 {@code false}
     */
    public boolean loginAndSave(String baseUrl, String account, String password) {
        synchronized (lock) {
            ZentaoClient candidate = createClient(baseUrl);
            if (!candidate.login(account, password)) {
                return false;
            }
            // 保存凭据和发布对应会话必须处于同一个锁区间，避免配置与活动账号不一致。
            configStore.save(baseUrl, account, password);
            client = candidate;
            autoLoginAttempted = true;
            return true;
        }
    }

    /**
     * 返回活动客户端，必要时使用本地配置执行一次延迟自动登录。
     *
     * <p>方法先走无锁快速路径；只有尚无客户端时才进入同步区并再次检查，
     * 防止多个并发工具调用重复登录。自动登录失败后不会在每次调用时反复请求远端。</p>
     *
     * @return 已登录的禅道客户端
     * @throws IllegalStateException 没有活动会话且自动登录未成功时抛出
     */
    public ZentaoClient requireClient() {
        ZentaoClient current = client;
        if (current != null) {
            return current;
        }

        synchronized (lock) {
            // 进入锁前可能已有其他线程完成登录，因此需要进行第二次检查。
            if (client != null) {
                return client;
            }
            if (!autoLoginAttempted) {
                // 先标记已尝试，确保配置损坏或网络失败时也不会形成重试风暴。
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

    /** @return 当前使用的用户配置文件绝对路径 */
    public Path configPath() {
        return configStore.configPath();
    }

    /** @return 当前详情数据目录解析器 */
    public DataPaths dataPaths() {
        return dataPaths;
    }

    /** 停止共享下载线程池，并等待其按下载服务约定完成关闭。 */
    @Override
    public void close() {
        downloadService.close();
    }

    /** 使用共享的详情存储与下载服务创建一个尚未登录的站点客户端。 */
    private ZentaoClient createClient(String baseUrl) {
        return new ZentaoClient(baseUrl, detailStore, downloadService);
    }
}
