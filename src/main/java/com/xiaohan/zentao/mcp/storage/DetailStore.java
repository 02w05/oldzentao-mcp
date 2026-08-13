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

public final class DetailStore {
    private final ObjectMapper objectMapper;
    private final DataPaths dataPaths;

    public DetailStore() {
        this(new DataPaths());
    }

    public DetailStore(DataPaths dataPaths) {
        this(JsonMapper.builder().build(), dataPaths);
    }

    public DetailStore(ObjectMapper objectMapper, DataPaths dataPaths) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.dataPaths = Objects.requireNonNull(dataPaths, "dataPaths");
    }

    public synchronized Path saveTask(TaskDetail detail) throws IOException {
        Objects.requireNonNull(detail, "detail");

        Path directory = prepare(dataPaths.taskDir(detail.id()));
        writeJson(directory, "task.json", detail);
        writeText(directory, "desc.md", taskMarkdown(detail));
        return directory;
    }

    public synchronized Path saveStory(StoryDetail detail) throws IOException {
        Objects.requireNonNull(detail, "detail");
        Path directory = prepare(dataPaths.storyDir(detail.id()));
        writeJson(directory, "story.json", detail);
        writeText(directory, "desc.md", storyMarkdown(detail));
        return directory;
    }

    public synchronized Path saveBug(BugDetail detail) throws IOException {
        Objects.requireNonNull(detail, "detail");
        Path directory = prepare(dataPaths.bugDir(detail.id()));
        writeJson(directory, "bug.json", detail);
        return directory;
    }

    private Path prepare(Path directory) throws IOException {
        dataPaths.imageDir(directory);
        dataPaths.attachmentDir(directory);
        return directory;
    }

    public Path imageDirectory(Path detailDirectory) throws IOException {
        return dataPaths.imageDir(detailDirectory);
    }

    public Path attachmentDirectory(Path detailDirectory) throws IOException {
        return dataPaths.attachmentDir(detailDirectory);
    }

    public Path safeFile(Path directory, String fileName, String fallback) throws IOException {
        return dataPaths.safeFile(directory, fileName, fallback);
    }

    private void writeJson(Path directory, String fileName, Object value) throws IOException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        writeText(directory, fileName, json + "\n");
    }

    private void writeText(Path directory, String fileName, String content) throws IOException {
        AtomicFiles.writeString(
            dataPaths.safeFile(directory, fileName),
            content,
            StandardCharsets.UTF_8
        );
    }

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

    private static String storyMarkdown(StoryDetail detail) {
        return "# " + text(detail.title()) + "\n\n"
            + "## 子标题\n" + text(detail.subtitle()) + "\n\n"
            + "## 版本\n" + text(detail.version()) + "\n\n"
            + "## 需求背景\n" + text(detail.background()) + "\n\n"
            + "## 需求描述\n" + text(detail.description()) + "\n\n"
            + "## 验收标准\n" + text(detail.acceptanceCriteria()) + "\n";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
