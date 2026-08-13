package com.xiaohan.zentao.mcp.util;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class FileNames {

    private static final int MAX_COMPONENT_LENGTH = 200;
    private static final String DEFAULT_FALLBACK = "unnamed";
    private static final Pattern WINDOWS_ILLEGAL = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");

    private static final Pattern WINDOWS_TRAILING = Pattern.compile("[ .]+$");
    private static final Set<String> WINDOWS_RESERVED = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private FileNames() {
    }

    public static String basename(String candidate) {
        if (candidate == null) {
            return "";
        }
        String value = candidate.trim();
        int separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return separator >= 0 ? value.substring(separator + 1) : value;
    }

    public static String sanitize(String candidate) {
        return sanitize(candidate, DEFAULT_FALLBACK);
    }

    public static String sanitize(String candidate, String fallback) {
        String safeFallback = clean(fallback);
        if (safeFallback.isBlank() || ".".equals(safeFallback) || "..".equals(safeFallback)) {
            safeFallback = DEFAULT_FALLBACK;
        }
        safeFallback = limitLength(avoidReservedDeviceName(safeFallback));

        String cleaned = clean(candidate);
        if (cleaned.isBlank() || ".".equals(cleaned) || "..".equals(cleaned)) {
            cleaned = safeFallback;
        }
        return limitLength(avoidReservedDeviceName(cleaned));
    }

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

    public static Path resolveSafe(Path directory, String candidate) {
        return resolveSafe(directory, candidate, DEFAULT_FALLBACK);
    }

    public static Path resolveSafe(Path directory, String candidate, String fallback) {
        Path base = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Path resolved = base.resolve(sanitize(candidate, fallback)).normalize();
        // 清洗文件名之外仍需强制目标是 base 的直接子文件。
        if (!base.equals(resolved.getParent()) || !resolved.startsWith(base)) {
            throw new IllegalArgumentException("文件路径超出目标目录");
        }
        return resolved;
    }

    private static String clean(String candidate) {
        String value = basename(candidate);
        value = WINDOWS_ILLEGAL.matcher(value).replaceAll("_");
        return WINDOWS_TRAILING.matcher(value).replaceAll("").trim();
    }

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
