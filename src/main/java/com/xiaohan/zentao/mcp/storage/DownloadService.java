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

public final class DownloadService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadService.class);
    private static final int DEFAULT_WORKERS = 4;
    private static final int MAX_QUEUED_DOWNLOADS = 256;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final ExecutorService executor;

    public DownloadService() {
        this(DEFAULT_WORKERS);
    }

    public DownloadService(int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers 必须大于 0");
        }
        AtomicInteger sequence = new AtomicInteger();

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "zentao-download-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };

        // 有界队列避免附件过多时耗尽内存；溢出的任务只记录告警。
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

    public void submit(String description, ThrowingRunnable task) {
        Objects.requireNonNull(task, "task");
        try {
            executor.submit(() -> {
                try {
                    task.run();
                } catch (Exception exception) {
                    LOGGER.warn("后台下载失败: {} ({})", description, exception.getMessage());
                }
            });
        } catch (RejectedExecutionException exception) {
            LOGGER.warn("下载队列已满或服务已关闭，忽略任务: {}", description);
        }
    }

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

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
