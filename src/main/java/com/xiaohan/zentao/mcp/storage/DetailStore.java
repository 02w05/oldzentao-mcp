package com.xiaohan.zentao.mcp.storage;

import com.xiaohan.zentao.mcp.model.BugDetail;
import com.xiaohan.zentao.mcp.model.StoryDetail;
import com.xiaohan.zentao.mcp.model.TaskDetail;
import com.xiaohan.zentao.mcp.util.AtomicFiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 在后台资源下载开始前，同步持久化解析后的禅道详情。
 *
 * <p>任务和需求会同时生成便于程序读取的 JSON 与便于人工阅读的 Markdown，
 * Bug 当前只生成 JSON。所有文本写入均委托给 {@link AtomicFiles}，所有目标路径
 * 均重新经过 {@link DataPaths} 的安全校验。</p>
 *
 * <p>保存方法使用实例锁串行执行，避免并发请求同一详情时交错替换文件。</p>
 */
public final class DetailStore {
    private final ObjectMapper objectMapper;
    private final DataPaths dataPaths;

    /** 使用默认 JSON 映射器和默认数据目录创建详情存储。 */
    public DetailStore() {
        this(new DataPaths());
    }

    /**
     * 使用指定数据目录和默认 JSON 映射器创建详情存储。
     *
     * @param dataPaths 数据目录解析器
     */
    public DetailStore(DataPaths dataPaths) {
        this(JsonMapper.builder().build(), dataPaths);
    }

    /**
     * 使用完整的可替换依赖创建详情存储。
     *
     * @param objectMapper JSON 序列化器
     * @param dataPaths 数据目录解析器
     */
    public DetailStore(ObjectMapper objectMapper, DataPaths dataPaths) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.dataPaths = Objects.requireNonNull(dataPaths, "dataPaths");
    }

    /**
     * 保存任务详情，并返回后续资源下载应使用的目录。
     *
     * @param detail 已解析的任务详情
     * @return 任务详情目录
     * @throws IOException 创建目录或写入 JSON、Markdown 失败时抛出
     */
    public synchronized Path saveTask(TaskDetail detail) throws IOException {
        Objects.requireNonNull(detail, "detail");
        // 先创建完整目录结构，再写入两种同步结果，便于后续异步任务直接使用。
        Path directory = prepare(dataPaths.taskDir(detail.id()));
        writeJson(directory, "task.json", detail);
        writeText(directory, "desc.md", taskMarkdown(detail));
        return directory;
    }

    /**
     * 保存需求详情，并返回后续资源下载应使用的目录。
     *
     * @param detail 已解析的需求详情
     * @return 需求详情目录
     * @throws IOException 创建目录或写入 JSON、Markdown 失败时抛出
     */
    public synchronized Path saveStory(StoryDetail detail) throws IOException {
        Objects.requireNonNull(detail, "detail");
        Path directory = prepare(dataPaths.storyDir(detail.id()));
        writeJson(directory, "story.json", detail);
        writeText(directory, "desc.md", storyMarkdown(detail));
        return directory;
    }

    /**
     * 保存 Bug 详情，并返回后续资源下载应使用的目录。
     *
     * @param detail 已解析的 Bug 详情
     * @return Bug 详情目录
     * @throws IOException 创建目录或写入 JSON 失败时抛出
     */
    public synchronized Path saveBug(BugDetail detail) throws IOException {
        Objects.requireNonNull(detail, "detail");
        Path directory = prepare(dataPaths.bugDir(detail.id()));
        writeJson(directory, "bug.json", detail);
        return directory;
    }

    /** 提前创建固定的图片和附件目录，使详情目录结构在工具返回前已经完整。 */
    private Path prepare(Path directory) throws IOException {
        dataPaths.imageDir(directory);
        dataPaths.attachmentDir(directory);
        return directory;
    }

    /**
     * @param detailDirectory 详情目录
     * @return 已创建并通过安全校验的图片目录
     * @throws IOException 目录创建或安全校验失败时抛出
     */
    public Path imageDirectory(Path detailDirectory) throws IOException {
        return dataPaths.imageDir(detailDirectory);
    }

    /**
     * @param detailDirectory 详情目录
     * @return 已创建并通过安全校验的附件目录
     * @throws IOException 目录创建或安全校验失败时抛出
     */
    public Path attachmentDirectory(Path detailDirectory) throws IOException {
        return dataPaths.attachmentDir(detailDirectory);
    }

    /**
     * 为后台下载解析安全目标文件；下载前会再次验证目录真实路径。
     *
     * @param directory 图片或附件目录
     * @param fileName 候选文件名
     * @param fallback 候选名称无效时的兜底值
     * @return 安全目标路径
     * @throws IOException 目录真实路径或文件目标校验失败时抛出
     */
    public Path safeFile(Path directory, String fileName, String fallback) throws IOException {
        return dataPaths.safeFile(directory, fileName, fallback);
    }

    /** 将对象格式化为 UTF-8 JSON，并统一在文件末尾保留换行。 */
    private void writeJson(Path directory, String fileName, Object value) throws IOException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        writeText(directory, fileName, json + "\n");
    }

    /** 通过安全路径与原子替换写入一份完整文本。 */
    private void writeText(Path directory, String fileName, String content) throws IOException {
        AtomicFiles.writeString(
            dataPaths.safeFile(directory, fileName),
            content,
            StandardCharsets.UTF_8
        );
    }

    /** 把任务详情组织成人工可读的 Markdown 摘要。 */
    private static String taskMarkdown(TaskDetail detail) {
        String relatedStory = text(detail.storyID())
            + (text(detail.storyTitle()).isEmpty() ? "" : " " + detail.storyTitle());
        return "# " + text(detail.title()) + "\n\n"
            + "## 状态\n" + text(detail.status()) + "\n\n"
            + "## 所属项目\n" + text(detail.projectName()) + "\n\n"
            + "## 相关需求\n" + relatedStory + "\n\n"
            + "## 指派给\n" + text(detail.assignedTo()) + "\n\n"
            + "## 截止日期\n" + text(detail.deadline()) + "\n\n"
            + "## 任务描述\n" + text(detail.description()) + "\n";
    }

    /** 把需求背景、描述和验收标准组织成人工可读的 Markdown。 */
    private static String storyMarkdown(StoryDetail detail) {
        return "# " + text(detail.title()) + "\n\n"
            + "## 子标题\n" + text(detail.subtitle()) + "\n\n"
            + "## 版本\n" + text(detail.version()) + "\n\n"
            + "## 需求背景\n" + text(detail.background()) + "\n\n"
            + "## 需求描述\n" + text(detail.description()) + "\n\n"
            + "## 验收标准\n" + text(detail.acceptanceCriteria()) + "\n";
    }

    /** Markdown 拼接时将缺失字段转为空串，避免输出字面量 {@code null}。 */
    private static String text(String value) {
        return value == null ? "" : value;
    }
}
