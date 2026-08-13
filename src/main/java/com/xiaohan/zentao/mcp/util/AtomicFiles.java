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

public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void writeString(Path target, String content, Charset charset) throws IOException {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(charset, "charset");
        Path temporary = createTemporarySibling(target);
        try {
            Files.writeString(
                temporary,
                content,
                charset,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static Path createTemporarySibling(Path target) throws IOException {
        Path normalizedTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("目标文件没有父目录: " + normalizedTarget);
        }
        requirePlainDirectory(parent);
        return Files.createTempFile(parent, ".zentao-", ".tmp");
    }

    public static void replace(Path temporary, Path target) throws IOException {
        Path normalizedTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Path normalizedTemporary = Objects.requireNonNull(temporary, "temporary").toAbsolutePath().normalize();
        if (!Objects.equals(normalizedTarget.getParent(), normalizedTemporary.getParent())) {
            throw new IOException("临时文件与目标文件不在同一目录");
        }

        requirePlainDirectory(normalizedTarget.getParent());
        BasicFileAttributes temporaryAttributes = Files.readAttributes(
            normalizedTemporary,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (!temporaryAttributes.isRegularFile() || temporaryAttributes.isSymbolicLink()) {
            throw new IOException("临时文件类型不安全: " + normalizedTemporary);
        }

        // 不做非原子降级：文件系统无法保证原子替换时应明确失败。
        Files.move(
            normalizedTemporary,
            normalizedTarget,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        );
    }

    private static void requirePlainDirectory(Path directory) throws IOException {
        // NOFOLLOW_LINKS 用于拒绝符号链接和 Windows 重解析目录。
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
