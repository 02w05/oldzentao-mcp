package com.xiaohan.zentao.mcp.storage;

import com.xiaohan.zentao.mcp.util.FileNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class DataPaths {

    public static final String DATA_DIRECTORY_ENV = "ZENTAO_MCP_DATA_DIR";

    private final Path root;

    // 缓存根目录真实路径，以检测进程运行期间的符号链接或重解析替换。
    private volatile Path canonicalRoot;

    public DataPaths() {
        this(defaultRoot());
    }

    public DataPaths(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path taskDir(String taskId) throws IOException {
        return detailDir("task", "task", taskId);
    }

    public Path storyDir(String storyId) throws IOException {
        return detailDir("product", "story", storyId);
    }

    public Path bugDir(String bugId) throws IOException {
        return detailDir("bug", "bug", bugId);
    }

    public Path imageDir(Path detailDirectory) throws IOException {
        return childDirectory(detailDirectory, "img");
    }

    public Path attachmentDir(Path detailDirectory) throws IOException {
        return childDirectory(detailDirectory, "attachment");
    }

    public Path safeFile(Path directory, String fileName) throws IOException {
        return safeFile(directory, fileName, "unnamed");
    }

    public Path safeFile(Path directory, String fileName, String fallback) throws IOException {
        Path normalizedDirectory = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath()
            .normalize();
        // 每次写入都同时检查词法路径和实时真实路径，避免校验后的目录被替换。
        ensureWithinRoot(normalizedDirectory);
        requirePlainDirectory(normalizedDirectory);
        Path realDirectory = requireRealChild(realRoot(), normalizedDirectory);
        return FileNames.resolveSafe(realDirectory, fileName, fallback);
    }

    private Path detailDir(String category, String prefix, String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("详情 ID 不能为空");
        }
        if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.contains("..")) {
            throw new IllegalArgumentException("详情 ID 格式无效");
        }
        Path realRoot = realRoot();

        Path categoryDirectory = FileNames.resolveSafe(root, category);
        ensureWithinRoot(categoryDirectory);
        createPlainDirectory(categoryDirectory);
        Path realCategory = requireRealChild(realRoot, categoryDirectory);

        Path detailDirectory = FileNames.resolveSafe(categoryDirectory, prefix + "-" + id);
        ensureWithinRoot(detailDirectory);
        createPlainDirectory(detailDirectory);
        Path realDetail = requireRealChild(realRoot, detailDirectory);
        if (!Objects.equals(realDetail.getParent(), realCategory)) {
            throw new IOException("详情目录包含不安全的重解析路径: " + detailDirectory);
        }
        return detailDirectory;
    }

    private Path childDirectory(Path detailDirectory, String name) throws IOException {
        Path normalizedDetail = Objects.requireNonNull(detailDirectory, "detailDirectory")
            .toAbsolutePath()
            .normalize();
        ensureWithinRoot(normalizedDetail);
        Path realRoot = realRoot();
        requirePlainDirectory(normalizedDetail);
        Path realDetail = requireRealChild(realRoot, normalizedDetail);
        Path child = FileNames.resolveSafe(normalizedDetail, name);
        createPlainDirectory(child);
        Path realChild = requireRealChild(realRoot, child);
        if (!Objects.equals(realChild.getParent(), realDetail)) {
            throw new IOException("详情子目录包含不安全的重解析路径: " + child);
        }
        return child;
    }

    private void ensureWithinRoot(Path path) {
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("数据路径超出根目录");
        }
    }

    private Path realRoot() throws IOException {
        Path currentRoot = canonicalRoot;
        if (currentRoot == null) {
            synchronized (this) {
                currentRoot = canonicalRoot;
                if (currentRoot == null) {
                    currentRoot = Files.createDirectories(root).toRealPath();
                    canonicalRoot = currentRoot;
                }
            }
        }

        Path resolvedNow = root.toRealPath();
        if (!resolvedNow.equals(currentRoot)) {
            throw new IOException("数据根目录在运行期间发生了变化: " + root);
        }
        return currentRoot;
    }

    private static void createPlainDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory);
        }
        requirePlainDirectory(directory);
    }

    private static void requirePlainDirectory(Path directory) throws IOException {
        // NOFOLLOW_LINKS 用于拒绝符号链接和 Windows 重解析目录。
        BasicFileAttributes attributes = Files.readAttributes(
            directory,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("拒绝使用符号链接或重解析目录: " + directory);
        }
    }

    private static Path requireRealChild(Path realRoot, Path child) throws IOException {
        Path realChild = child.toRealPath();
        if (realChild.equals(realRoot) || !realChild.startsWith(realRoot)) {
            throw new IOException("数据路径超出根目录: " + child);
        }
        return realChild;
    }

    private static Path defaultRoot() {
        String override = System.getenv(DATA_DIRECTORY_ENV);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        String userProfile = System.getenv("USERPROFILE");
        Path home = userProfile != null && !userProfile.isBlank()
            ? Path.of(userProfile)
            : Path.of(System.getProperty("user.home"));
        return home.resolve(".zentao-mcp").resolve("data");
    }
}
