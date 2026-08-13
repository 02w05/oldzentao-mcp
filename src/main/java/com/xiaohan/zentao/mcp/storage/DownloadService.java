package com.xiaohan.zentao.mcp.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在不阻塞 MCP 工具响应的情况下执行图片和附件下载。
 *
 * <p>线程池使用固定数量的守护线程和有界队列，防止详情请求过多时无限占用内存。
 * 单个下载失败只记录警告，不会反向改变已经返回的详情结果；队列饱和或关闭后的
 * 新任务同样会被记录并丢弃。</p>
 */
public final class DownloadService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadService.class);
    private static final int DEFAULT_WORKERS = 4;
    private static final int MAX_QUEUED_DOWNLOADS = 256;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final ExecutorService executor;

    /** 使用四个后台工作线程创建下载服务。 */
    public DownloadService() {
        this(DEFAULT_WORKERS);
    }

    /**
     * 使用指定数量的固定后台线程创建下载服务。
     *
     * @param workers 并行下载线程数，必须大于零
     * @throws IllegalArgumentException 工作线程数小于一时抛出
     */
    public DownloadService(int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers 必须大于 0");
        }
        AtomicInteger sequence = new AtomicInteger();
        // 使用可识别的守护线程名，便于日志和线程转储定位下载任务。
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "zentao-download-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 核心线程数与最大线程数相同，所有超出并行度的任务进入固定容量队列。
        this.executor = new ThreadPoolExecutor(
            workers,
            workers,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_DOWNLOADS),
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 提交一个允许抛出受检异常的后台下载任务。
     *
     * @param description 用于失败日志的 URL 或资源描述
     * @param task 实际下载逻辑
     */
    public void submit(String description, ThrowingRunnable task) {
        Objects.requireNonNull(task, "task");
        try {
            executor.submit(() -> {
                try {
                    task.run();
                } catch (Exception exception) {
                    // 资源下载是详情查询的附属操作，失败不应传播到 MCP 请求线程。
                    LOGGER.warn("后台下载失败: {} ({})", description, exception.getMessage());
                }
            });
        } catch (RejectedExecutionException exception) {
            LOGGER.warn("下载队列已满或服务已关闭，忽略任务: {}", description);
        }
    }

    /**
     * 停止接收新下载，最多等待五秒；超时或当前线程被中断时强制取消剩余任务。
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 可由执行器调用、并允许抛出受检异常的下载动作。
     *
     * <p>异常由 {@link #submit(String, ThrowingRunnable)} 统一捕获和记录。</p>
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        /** 执行一次下载动作。 */
        void run() throws Exception;
    }
}
