package com.xiaohan.zentao.mcp.tool;

import com.xiaohan.zentao.mcp.client.ZentaoClient;
import com.xiaohan.zentao.mcp.config.UserConfig;
import com.xiaohan.zentao.mcp.model.BugDetail;
import com.xiaohan.zentao.mcp.model.BugInfo;
import com.xiaohan.zentao.mcp.model.BugPageResult;
import com.xiaohan.zentao.mcp.model.StoryDetail;
import com.xiaohan.zentao.mcp.model.TaskDetail;
import com.xiaohan.zentao.mcp.model.TaskInfo;
import com.xiaohan.zentao.mcp.model.TaskPageResult;
import com.xiaohan.zentao.mcp.service.ZentaoSessionManager;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class ZentaoToolService {

    private static final int PAGE_SIZE = 10;

    private static final Map<String, String> RESOLUTION_LABELS = Map.of(
        "fixed", "已解决",
        "bydesign", "设计如此",
        "external", "外部原因",
        "willnotfix", "不予解决",
        "tostory", "转为需求"
    );

    private final ZentaoSessionManager sessions;

    public ZentaoToolService(ZentaoSessionManager sessions) {
        this.sessions = sessions;
    }

    public String login(String baseUrl, String account, String password) {
        Optional<UserConfig> saved = sessions.loadSavedConfig();
        boolean firstCompleteConfiguration = saved.isEmpty() || !saved.get().hasCredentials();
        if (saved.isPresent()) {

            UserConfig config = saved.get();
            baseUrl = firstNonBlank(baseUrl, config.baseUrl());
            account = firstNonBlank(account, config.account());
            password = firstNonBlank(password, config.password());
        }

        if (isBlank(baseUrl) || isBlank(account) || isBlank(password)) {

            return """
                ⚠️ 首次使用，请提供禅道登录信息：

                **请输入以下参数：**
                - `baseUrl`: 禅道服务器地址 (如: https://zentao.example.com)
                - `account`: 登录账号
                - `password`: 登录密码

                **示例：**
                ```
                baseUrl: https://xiaohan.chandao.com
                account: your_account
                password: your_password
                ```

                > 配置将保存到: %s
                > 如需修改，可直接编辑该文件。""".formatted(sessions.configPath());
        }

        try {
            if (!sessions.loginAndSave(baseUrl, account, password)) {
                return """
                    ❌ 登录失败，请检查账号密码是否正确。

                    如需重新配置，请提供新的 baseUrl、account、password 参数。""";
            }

            String savedNotice = firstCompleteConfiguration
                ? "\n**配置已保存到**: " + sessions.configPath()
                    + "\n\n如需修改账号密码，请编辑该文件或重新调用登录工具传入新参数。\n"
                : "";
            return "✅ 登录成功！\n\n"
                + "**服务器**: " + baseUrl + "\n"
                + "**账号**: " + account + "\n"
                + savedNotice
                + "\n现在可以使用其他禅道工具了。";
        } catch (Exception exception) {
            return "❌ 登录出错：" + errorMessage(exception);
        }
    }

    public String getMyTasks(int page) throws Exception {
        TaskPageResult result = sessions.requireClient().fetchMyTasks(page, PAGE_SIZE);
        StringBuilder text = new StringBuilder()
            .append("# 我的任务列表 (第 ").append(result.pageID())
            .append(" 页，共 ").append(result.recTotal()).append(" 条)\n\n")
            .append("| 序号 | 项目名称 | 任务名称 | 需求ID | 需求标题 |\n")
            .append("|------|----------|----------|--------|----------|\n");

        for (int index = 0; index < result.tasks().size(); index++) {
            TaskInfo task = result.tasks().get(index);
            text.append("| ").append((page - 1) * PAGE_SIZE + index + 1)
                .append(" | ").append(orDash(task.projectName()))
                .append(" | ").append(orDash(task.name()))
                .append(" | ").append(orDash(task.storyID()))
                .append(" | ").append(orDash(task.storyTitle()))
                .append(" |");
            if (index + 1 < result.tasks().size()) {
                text.append('\n');
            }
        }
        return text.append("\n\n> 提示：使用 page 参数获取下一页数据").toString();
    }

    public String getTaskDetail(String taskId) throws Exception {
        TaskDetail detail = sessions.requireClient().fetchTaskDetail(taskId);
        Path saveDir = sessions.dataPaths().taskDir(taskId);
        return "# 任务详情\n\n"
            + "**任务ID**: " + detail.id() + "\n"
            + "**标题**: " + detail.title() + "\n"
            + "**状态**: " + detail.status() + "\n"
            + "**所属项目**: " + detail.projectName() + "\n"
            + "**相关需求**: " + orDash(detail.storyID()) + " " + nullToEmpty(detail.storyTitle()) + "\n"
            + "**指派给**: " + orDash(detail.assignedTo()) + "\n"
            + "**截止日期**: " + orDash(detail.deadline()) + "\n\n"
            + "## 任务描述\n" + truncate(detail.description(), 1000, false) + "\n\n"
            + "## 附件信息\n"
            + "- 图片: " + detail.images().size() + " 张 (后台下载中)\n"
            + "- 附件: " + detail.attachments().size() + " 个 (后台下载中)\n\n"
            + "> 数据保存到: " + saveDir;
    }

    public String getMyBugs(int page) throws Exception {
        BugPageResult result = sessions.requireClient().fetchMyBugs(page, PAGE_SIZE);
        StringBuilder text = new StringBuilder()
            .append("# 我的 Bug 列表 (第 ").append(result.pageID())
            .append(" 页，共 ").append(result.recTotal()).append(" 条)\n\n")
            .append("| 序号 | 项目名称 | Bug标题 | Bug ID |\n")
            .append("|------|----------|---------|--------|\n");

        for (int index = 0; index < result.bugs().size(); index++) {
            BugInfo bug = result.bugs().get(index);
            text.append("| ").append((page - 1) * PAGE_SIZE + index + 1)
                .append(" | ").append(orDash(bug.projectName()))
                .append(" | ").append(orDash(bug.title()))
                .append(" | ").append(bug.id())
                .append(" |");
            if (index + 1 < result.bugs().size()) {
                text.append('\n');
            }
        }
        return text.append("\n\n> 提示：使用 page 参数获取下一页数据").toString();
    }

    public String getStoryDetail(String storyId) throws Exception {
        StoryDetail detail = sessions.requireClient().fetchStoryDetail(storyId);
        Path saveDir = sessions.dataPaths().storyDir(storyId);
        return "# 需求详情\n\n"
            + "**需求ID**: " + detail.id() + "\n"
            + "**主标题**: " + detail.title() + "\n"
            + "**子标题**: " + detail.subtitle() + "\n"
            + "**版本**: " + detail.version() + "\n\n"
            + "## 需求背景\n" + orNone(detail.background()) + "\n\n"
            + "## 需求描述\n" + truncate(detail.description(), 500, true) + "\n\n"
            + "## 验收标准\n" + orNone(detail.acceptanceCriteria()) + "\n\n"
            + "## 附件信息\n"
            + "- 图片: " + detail.images().size() + " 张 (后台下载中)\n"
            + "- 附件: " + detail.attachments().size() + " 个 (后台下载中)\n\n"
            + "> 数据保存到: " + saveDir;
    }

    public String getBugDetail(String bugId) throws Exception {
        BugDetail detail = sessions.requireClient().fetchBugDetail(bugId);
        Path saveDir = sessions.dataPaths().bugDir(bugId);
        return "# Bug 详情\n\n"
            + "**Bug ID**: " + detail.id() + "\n"
            + "**标题**: " + detail.title() + "\n"
            + "**状态**: " + detail.status() + "\n"
            + "**严重程度**: " + detail.severity() + "\n\n"
            + "## 重现步骤\n" + truncate(detail.steps(), 500, true) + "\n\n"
            + "## 附件信息\n"
            + "- 图片: " + detail.images().size() + " 张 (后台下载中)\n"
            + "- 附件: " + detail.attachments().size() + " 个 (后台下载中)\n\n"
            + "> 数据保存到: " + saveDir;
    }

    public String finishTask(String taskId, double consumed) {
        ZentaoClient client = sessions.requireClient();
        String finishedDate = ZentaoClient.currentUtcDateTime();
        if (client.finishTask(taskId, finishedDate, consumed)) {
            return "✅ 任务 " + taskId + " 已完成！\n\n"
                + "完成时间: " + finishedDate + "\n"
                + "消耗工时: " + formatNumber(consumed) + " 小时";
        }
        return "❌ 完成任务失败，请检查任务状态。";
    }

    public String resolveBug(String bugId, String resolution) {
        ZentaoClient client = sessions.requireClient();
        if (client.resolveBug(bugId, resolution)) {
            return "✅ Bug " + bugId + " 已解决！\n\n解决方案: " + RESOLUTION_LABELS.get(resolution);
        }
        return "❌ 解决Bug失败，请检查Bug状态。";
    }

    public static String errorText(Throwable throwable) {
        return "❌ " + errorMessage(throwable);
    }

    private static String truncate(String value, int maximum, boolean alwaysEllipsis) {
        if (isBlank(value)) {
            return "无";
        }
        String result = value.substring(0, Math.min(maximum, value.length()));
        if (alwaysEllipsis || value.length() > maximum) {
            result += "...";
        }
        return result;
    }

    private static String errorMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return isBlank(message) ? current.toString() : message;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return isBlank(preferred) ? fallback : preferred;
    }

    private static String orDash(String value) {
        return isBlank(value) ? "-" : value;
    }

    private static String orNone(String value) {
        return isBlank(value) ? "无" : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
