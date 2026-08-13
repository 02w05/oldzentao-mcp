package com.xiaohan.zentao.mcp.util;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Windows 安全的文件名清理与路径包含校验工具。
 *
 * <p>远端附件名称属于不可信输入，可能包含路径分隔符、Windows 保留设备名、
 * 控制字符或过长组件。本类先把输入压缩为单个安全文件名，再确认解析结果仍是
 * 指定目录的直接子项。</p>
 */
public final class FileNames {
    // 为完整路径和后续重名后缀预留空间，不使用 Windows 理论上的极限值 255。
    private static final int MAX_COMPONENT_LENGTH = 200;
    private static final String DEFAULT_FALLBACK = "unnamed";
    private static final Pattern WINDOWS_ILLEGAL = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");
    // Windows 对文件名末尾的空格和句点有特殊归一化行为，必须主动去除。
    private static final Pattern WINDOWS_TRAILING = Pattern.compile("[ .]+$");
    private static final Set<String> WINDOWS_RESERVED = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    // 纯静态工具类不允许实例化。
    private FileNames() {
    }

    /**
     * 同时识别 Unix 与 Windows 分隔符，只保留候选路径的最后一段。
     *
     * @param candidate 可能包含路径的远端文件名
     * @return 去除目录部分并去掉首尾空白的文件名；输入为 {@code null} 时返回空串
     */
    public static String basename(String candidate) {
        if (candidate == null) {
            return "";
        }
        String value = candidate.trim();
        int separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return separator >= 0 ? value.substring(separator + 1) : value;
    }

    /**
     * 使用默认兜底名称清理一个文件名。
     *
     * @param candidate 原始文件名
     * @return 可用作 Windows 路径组件的安全名称
     */
    public static String sanitize(String candidate) {
        return sanitize(candidate, DEFAULT_FALLBACK);
    }

    /**
     * 清理文件名，并在原始名称无效时使用调用方提供的兜底名称。
     *
     * @param candidate 原始文件名
     * @param fallback 原始名称为空、点目录或清理后为空时使用的名称
     * @return 去除危险字符、设备名冲突和超长部分后的文件名
     */
    public static String sanitize(String candidate, String fallback) {
        // 兜底值也来自调用方，必须先执行与正式文件名相同的安全处理。
        String safeFallback = clean(fallback);
        if (safeFallback.isBlank() || ".".equals(safeFallback) || "..".equals(safeFallback)) {
            safeFallback = DEFAULT_FALLBACK;
        }
        safeFallback = limitLength(avoidReservedDeviceName(safeFallback));

        // 对正式名称进行第二轮处理，无效时才替换为已经验证过的兜底值。
        String cleaned = clean(candidate);
        if (cleaned.isBlank() || ".".equals(cleaned) || "..".equals(cleaned)) {
            cleaned = safeFallback;
        }
        return limitLength(avoidReservedDeviceName(cleaned));
    }

    /**
     * 在保留扩展名和 Windows 组件长度预算的前提下追加稳定后缀。
     *
     * <p>该方法用于解决同一详情页内的附件重名问题。后缀本身也会被清理并限制长度，
     * 较长的主文件名则从末尾截断，为后缀和扩展名留出空间。</p>
     *
     * @param candidate 已有文件名
     * @param suffix 要追加的附件标识或序号
     * @return 形如“名称-后缀.扩展名”的安全文件名
     */
    public static String addSuffix(String candidate, String suffix) {
        String safeCandidate = sanitize(candidate);
        String safeSuffix = sanitize(suffix, "duplicate");
        if (safeSuffix.length() > 64) {
            safeSuffix = safeSuffix.substring(0, 64);
        }
        int dot = safeCandidate.lastIndexOf('.');
        String extension = dot > 0 ? safeCandidate.substring(dot) : "";
        String stem = dot > 0 ? safeCandidate.substring(0, dot) : safeCandidate;
        int stemLimit = Math.max(1, MAX_COMPONENT_LENGTH - extension.length() - safeSuffix.length() - 1);
        if (stem.length() > stemLimit) {
            stem = stem.substring(0, stemLimit);
        }
        return sanitize(stem + "-" + safeSuffix + extension);
    }

    /** Windows 设备名即使带扩展名也不可直接创建，因此统一添加下划线前缀。 */
    private static String avoidReservedDeviceName(String fileName) {
        String deviceName = fileName;
        int dot = deviceName.indexOf('.');
        if (dot >= 0) {
            deviceName = deviceName.substring(0, dot);
        }
        if (WINDOWS_RESERVED.contains(deviceName.toUpperCase(Locale.ROOT))) {
            return "_" + fileName;
        }
        return fileName;
    }

    /**
     * 使用默认兜底名称，在指定目录下解析安全文件路径。
     *
     * @param directory 目标目录
     * @param candidate 原始文件名
     * @return 目标目录中的规范化绝对路径
     */
    public static Path resolveSafe(Path directory, String candidate) {
        return resolveSafe(directory, candidate, DEFAULT_FALLBACK);
    }

    /**
     * 解析一个经过清理的直接子项，并确认结果不能逃出父目录。
     *
     * @param directory 目标目录
     * @param candidate 原始文件名
     * @param fallback 原始文件名无效时使用的兜底名称
     * @return 目标目录中的规范化绝对路径
     * @throws IllegalArgumentException 解析结果不是目标目录的直接子项时抛出
     */
    public static Path resolveSafe(Path directory, String candidate, String fallback) {
        Path base = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Path resolved = base.resolve(sanitize(candidate, fallback)).normalize();
        if (!base.equals(resolved.getParent()) || !resolved.startsWith(base)) {
            throw new IllegalArgumentException("文件路径超出目标目录");
        }
        return resolved;
    }

    /** 执行单路径组件清理：去目录、替换非法字符并移除危险的尾部字符。 */
    private static String clean(String candidate) {
        String value = basename(candidate);
        value = WINDOWS_ILLEGAL.matcher(value).replaceAll("_");
        return WINDOWS_TRAILING.matcher(value).replaceAll("").trim();
    }

    /**
     * 将过长的文件名压缩到预算内；仅在短扩展名存在时优先保留扩展名。
     */
    private static String limitLength(String fileName) {
        if (fileName.length() <= MAX_COMPONENT_LENGTH) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String extension = dot > 0 && fileName.length() - dot <= 16 ? fileName.substring(dot) : "";
        int stemLength = Math.max(1, MAX_COMPONENT_LENGTH - extension.length());
        return WINDOWS_TRAILING.matcher(fileName.substring(0, stemLength)).replaceAll("") + extension;
    }
}
