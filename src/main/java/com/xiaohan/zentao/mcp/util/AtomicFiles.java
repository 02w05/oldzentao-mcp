package com.xiaohan.zentao.mcp.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * 提供“同目录临时文件 + 原子替换”的安全文件写入能力。
 *
 * <p>临时文件与目标文件位于同一目录，既提高原子移动可用性，也避免跨卷复制。
 * 写入前会拒绝符号链接、重解析目录和非普通临时文件，降低路径被替换后写到预期目录
 * 之外的风险。</p>
 */
public final class AtomicFiles {
    // 纯静态工具类不允许实例化。
    private AtomicFiles() {
    }

    /**
     * 将字符串完整写入临时兄弟文件，然后原子替换目标文件。
     *
     * @param target 最终目标文件
     * @param content 要写入的完整文本
     * @param charset 文本编码
     * @throws IOException 创建、写入、校验或替换文件失败时抛出
     */
    public static void writeString(Path target, String content, Charset charset) throws IOException {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(charset, "charset");
        Path temporary = createTemporarySibling(target);
        try {
            // 临时文件已经由 createTempFile 创建，只需清空并写入完整内容。
            Files.writeString(
                temporary,
                content,
                charset,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            replace(temporary, target);
        } finally {
            // 替换失败或写入中断时清理残留；移动成功后该调用只是无操作。
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 在目标文件的同一目录创建一个唯一临时文件。
     *
     * @param target 最终目标文件
     * @return 已创建的临时文件绝对路径
     * @throws IOException 目标没有父目录、父目录不安全或创建失败时抛出
     */
    public static Path createTemporarySibling(Path target) throws IOException {
        Path normalizedTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("目标文件没有父目录: " + normalizedTarget);
        }
        requirePlainDirectory(parent);
        return Files.createTempFile(parent, ".zentao-", ".tmp");
    }

    /**
     * 校验临时文件后，以原子移动方式替换目标文件。
     *
     * @param temporary 已写完的临时文件
     * @param target 最终目标文件
     * @throws IOException 两个文件不在同一目录、路径类型不安全或移动失败时抛出
     */
    public static void replace(Path temporary, Path target) throws IOException {
        Path normalizedTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Path normalizedTemporary = Objects.requireNonNull(temporary, "temporary").toAbsolutePath().normalize();
        if (!Objects.equals(normalizedTarget.getParent(), normalizedTemporary.getParent())) {
            throw new IOException("临时文件与目标文件不在同一目录");
        }
        // 移动前再次检查父目录，缩小目录在创建临时文件后被替换所留下的时间窗口。
        requirePlainDirectory(normalizedTarget.getParent());
        BasicFileAttributes temporaryAttributes = Files.readAttributes(
            normalizedTemporary,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (!temporaryAttributes.isRegularFile() || temporaryAttributes.isSymbolicLink()) {
            throw new IOException("临时文件类型不安全: " + normalizedTemporary);
        }
        // 不提供非原子降级：若文件系统不支持，调用方会收到异常而不是接受半写入风险。
        Files.move(
            normalizedTemporary,
            normalizedTarget,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        );
    }

    /** 校验写入目录是真实目录，而不是符号链接或其他特殊文件。 */
    private static void requirePlainDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
            directory,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("拒绝写入符号链接或重解析目录: " + directory);
        }
    }
}
