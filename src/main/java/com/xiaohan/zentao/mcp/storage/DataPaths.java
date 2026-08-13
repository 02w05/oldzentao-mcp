package com.xiaohan.zentao.mcp.storage;

import com.xiaohan.zentao.mcp.util.FileNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * 解析并创建禅道详情数据的本地目录结构。
 *
 * <p>所有任务、需求、Bug、图片和附件路径都必须经过本类。除词法层面的
 * {@code normalize} 与根目录包含检查外，本类还会读取真实路径，拒绝写入符号链接
 * 或重解析子目录，并检测运行期间被替换的数据根目录。</p>
 *
 * <p>根目录首次使用时才创建并缓存其真实路径；缓存字段使用 {@code volatile}，
 * 初始化过程则通过同步块保证并发调用只发布一个基准路径。</p>
 */
public final class DataPaths {
    /** 用于覆盖默认数据根目录的环境变量名称。 */
    public static final String DATA_DIRECTORY_ENV = "ZENTAO_MCP_DATA_DIR";

    private final Path root;
    // 首次访问后保存根目录真实路径，后续每次写入都会与当前真实路径重新比较。
    private volatile Path canonicalRoot;

    /** 使用环境变量或当前用户目录确定的数据根目录。 */
    public DataPaths() {
        this(defaultRoot());
    }

    /**
     * 使用指定的数据根目录。
     *
     * @param root 数据根目录；会转换为规范化的绝对路径，但在首次使用前不会创建
     */
    public DataPaths(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /** @return 配置的、规范化后的数据根目录 */
    public Path root() {
        return root;
    }

    /**
     * 创建并返回指定任务的详情目录。
     *
     * @param taskId 任务 ID
     * @return {@code task/task-<ID>} 目录
     * @throws IOException 目录创建或安全校验失败时抛出
     */
    public Path taskDir(String taskId) throws IOException {
        return detailDir("task", "task", taskId);
    }

    /**
     * 创建并返回指定需求的详情目录。
     *
     * @param storyId 需求 ID
     * @return {@code product/story-<ID>} 目录
     * @throws IOException 目录创建或安全校验失败时抛出
     */
    public Path storyDir(String storyId) throws IOException {
        return detailDir("product", "story", storyId);
    }

    /**
     * 创建并返回指定 Bug 的详情目录。
     *
     * @param bugId Bug ID
     * @return {@code bug/bug-<ID>} 目录
     * @throws IOException 目录创建或安全校验失败时抛出
     */
    public Path bugDir(String bugId) throws IOException {
        return detailDir("bug", "bug", bugId);
    }

    /**
     * 创建详情目录下的图片子目录。
     *
     * @param detailDirectory 已经过本类创建的详情目录
     * @return 安全校验后的 {@code img} 目录
     * @throws IOException 目录创建或安全校验失败时抛出
     */
    public Path imageDir(Path detailDirectory) throws IOException {
        return childDirectory(detailDirectory, "img");
    }

    /**
     * 创建详情目录下的附件子目录。
     *
     * @param detailDirectory 已经过本类创建的详情目录
     * @return 安全校验后的 {@code attachment} 目录
     * @throws IOException 目录创建或安全校验失败时抛出
     */
    public Path attachmentDir(Path detailDirectory) throws IOException {
        return childDirectory(detailDirectory, "attachment");
    }

    /**
     * 重新校验写入目录，并使用默认兜底名称解析其中的安全文件路径。
     *
     * @param directory 已创建的写入目录
     * @param fileName 原始文件名
     * @return 基于目录真实路径解析出的目标文件
     * @throws IOException 目录不安全或真实路径校验失败时抛出
     */
    public Path safeFile(Path directory, String fileName) throws IOException {
        return safeFile(directory, fileName, "unnamed");
    }

    /**
     * 重新校验写入目录，并解析其中的安全文件路径。
     *
     * <p>校验发生在每次文件写入之前，避免仅在目录创建时检查一次后，目录又被替换
     * 为符号链接或重解析路径。</p>
     *
     * @param directory 已创建的写入目录
     * @param fileName 原始文件名
     * @param fallback 原始名称无效时使用的兜底名称
     * @return 基于目录真实路径解析出的目标文件
     * @throws IOException 目录不安全或真实路径校验失败时抛出
     */
    public Path safeFile(Path directory, String fileName, String fallback) throws IOException {
        Path normalizedDirectory = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath()
            .normalize();
        ensureWithinRoot(normalizedDirectory);
        requirePlainDirectory(normalizedDirectory);
        Path realDirectory = requireRealChild(realRoot(), normalizedDirectory);
        return FileNames.resolveSafe(realDirectory, fileName, fallback);
    }

    /** 按“类别/前缀-ID”结构创建并验证一类详情目录。 */
    private Path detailDir(String category, String prefix, String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("详情 ID 不能为空");
        }
        if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.contains("..")) {
            throw new IllegalArgumentException("详情 ID 格式无效");
        }
        Path realRoot = realRoot();

        // 先创建固定的类别层，再验证其真实路径仍是数据根目录的直接后代。
        Path categoryDirectory = FileNames.resolveSafe(root, category);
        ensureWithinRoot(categoryDirectory);
        createPlainDirectory(categoryDirectory);
        Path realCategory = requireRealChild(realRoot, categoryDirectory);

        // ID 已拒绝路径语法，FileNames 仍会对最终组件执行统一的 Windows 安全清理。
        Path detailDirectory = FileNames.resolveSafe(categoryDirectory, prefix + "-" + id);
        ensureWithinRoot(detailDirectory);
        createPlainDirectory(detailDirectory);
        Path realDetail = requireRealChild(realRoot, detailDirectory);
        if (!Objects.equals(realDetail.getParent(), realCategory)) {
            throw new IOException("详情目录包含不安全的重解析路径: " + detailDirectory);
        }
        return detailDirectory;
    }

    /** 创建并验证详情目录下固定名称的直接子目录。 */
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

    /** 执行不访问文件系统的第一层根目录包含检查，并拒绝把根目录本身当作写入目录。 */
    private void ensureWithinRoot(Path path) {
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("数据路径超出根目录");
        }
    }

    /**
     * 延迟初始化根目录真实路径，并检查它在进程运行期间没有指向其他位置。
     */
    private Path realRoot() throws IOException {
        Path currentRoot = canonicalRoot;
        if (currentRoot == null) {
            synchronized (this) {
                currentRoot = canonicalRoot;
                if (currentRoot == null) {
                    // 只有首次调用负责创建目录；toRealPath 同时解析链接和重解析点。
                    currentRoot = Files.createDirectories(root).toRealPath();
                    canonicalRoot = currentRoot;
                }
            }
        }
        // 即使已经缓存，也重新解析一次，检测根目录在两次请求间被替换的情况。
        Path resolvedNow = root.toRealPath();
        if (!resolvedNow.equals(currentRoot)) {
            throw new IOException("数据根目录在运行期间发生了变化: " + root);
        }
        return currentRoot;
    }

    /** 如目录不存在则只创建当前层，随后统一校验目录类型。 */
    private static void createPlainDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory);
        }
        requirePlainDirectory(directory);
    }

    /** 在不跟随链接的前提下确认路径表示普通目录。 */
    private static void requirePlainDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
            directory,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("拒绝使用符号链接或重解析目录: " + directory);
        }
    }

    /** 解析子路径的真实位置，并确认它仍位于真实数据根目录之下。 */
    private static Path requireRealChild(Path realRoot, Path child) throws IOException {
        Path realChild = child.toRealPath();
        if (realChild.equals(realRoot) || !realChild.startsWith(realRoot)) {
            throw new IOException("数据路径超出根目录: " + child);
        }
        return realChild;
    }

    /**
     * 按“环境变量覆盖优先、用户目录兜底”的顺序确定默认数据根目录。
     */
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
