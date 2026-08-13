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

/**
 * 在不依赖 MCP 传输层的前提下实现各项工具行为。
 *
 * <p>本类处在工具 Schema 与禅道客户端之间：从会话管理器取得已登录客户端，
 * 调用查询或写操作，再把模型对象格式化为适合对话阅读的 Markdown 文本。这样，
 * HTTP、HTML 解析和文件保存细节不会泄漏到 MCP 工具定义中。</p>
 */
public final class ZentaoToolService {
    /** 任务和 Bug 列表对外固定使用的每页记录数。 */
    private static final int PAGE_SIZE = 10;
    /** 禅道解决方案代码到中文展示名称的映射。 */
    private static final Map<String, String> RESOLUTION_LABELS = Map.of(
        "fixed", "已解决",
        "bydesign", "设计如此",
        "external", "外部原因",
        "willnotfix", "不予解决",
        "tostory", "转为需求"
    );

    private final ZentaoSessionManager sessions;

    /**
     * 创建工具业务服务。
     *
     * @param sessions 提供配置、活动客户端和数据路径的会话管理器
     */
    public ZentaoToolService(ZentaoSessionManager sessions) {
        this.sessions = sessions;
    }

    /**
     * 合并调用参数与已保存配置，执行登录并在成功后保存配置。
     *
     * <p>显式传入的非空参数优先；缺失项由旧配置补齐。若仍不完整，则返回首次使用
     * 指引而不是发起无效网络请求。</p>
     *
     * @param baseUrl 可选的禅道根地址
     * @param account 可选的登录账号
     * @param password 可选的登录密码
     * @return 面向用户的登录结果或配置提示
     */
    public String login(String baseUrl, String account, String password) {
        Optional<UserConfig> saved = sessions.loadSavedConfig();
        boolean firstCompleteConfiguration = saved.isEmpty() || !saved.get().hasCredentials();
        if (saved.isPresent()) {
            // 允许用户只覆盖某一项，其余凭据继续沿用本地配置。
            UserConfig config = saved.get();
            baseUrl = firstNonBlank(baseUrl, config.baseUrl());
            account = firstNonBlank(account, config.account());
            password = firstNonBlank(password, config.password());
        }

        if (isBlank(baseUrl) || isBlank(account) || isBlank(password)) {
            // 参数不完整属于可恢复的交互状态，返回说明文本而不是抛出工具错误。
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

            // 仅首次形成完整配置时显示保存位置，避免日常重复登录产生冗余提示。
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

    /**
     * 查询指定页的当前用户任务，并格式化为 Markdown 表格。
     *
     * @param page 从 1 开始的页码
     * @return 包含全局序号、项目和关联需求的任务表格
     * @throws Exception 会话、网络或响应解析失败时向统一工具适配层传播
     */
    public String getMyTasks(int page) throws Exception {
        TaskPageResult result = sessions.requireClient().fetchMyTasks(page, PAGE_SIZE);
        StringBuilder text = new StringBuilder()
            .append("# 我的任务列表 (第 ").append(result.pageID())
            .append(" 页，共 ").append(result.recTotal()).append(" 条)\n\n")
            .append("| 序号 | 项目名称 | 任务名称 | 需求ID | 需求标题 |\n")
            .append("|------|----------|----------|--------|----------|\n");

        // 序号按完整结果集计算，而不是每一页重新从 1 开始。
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

    /**
     * 获取任务详情，并返回摘要、资源数量和本地保存位置。
     *
     * <p>客户端返回前已经同步保存 JSON/Markdown，并已提交图片和附件下载任务；
     * 因此文本明确标记资源仍在后台下载。</p>
     *
     * @param taskId 任务 ID
     * @return 面向用户的任务详情文本
     * @throws Exception 查询、解析或本地保存失败时传播
     */
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

    /**
     * 查询指定页的当前用户 Bug，并格式化为 Markdown 表格。
     *
     * @param page 从 1 开始的页码
     * @return 包含全局序号、项目和 Bug ID 的表格
     * @throws Exception 会话、网络或响应解析失败时传播
     */
    public String getMyBugs(int page) throws Exception {
        BugPageResult result = sessions.requireClient().fetchMyBugs(page, PAGE_SIZE);
        StringBuilder text = new StringBuilder()
            .append("# 我的 Bug 列表 (第 ").append(result.pageID())
            .append(" 页，共 ").append(result.recTotal()).append(" 条)\n\n")
            .append("| 序号 | 项目名称 | Bug标题 | Bug ID |\n")
            .append("|------|----------|---------|--------|\n");

        // 使用同任务列表一致的全局序号算法，翻页后序号连续。
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

    /**
     * 获取需求详情，并返回截断后的正文、附件数量和本地保存位置。
     *
     * @param storyId 需求 ID
     * @return 面向用户的需求详情文本
     * @throws Exception 查询、解析或本地保存失败时传播
     */
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

    /**
     * 获取 Bug 详情，并返回重现步骤、附件数量和本地保存位置。
     *
     * @param bugId Bug ID
     * @return 面向用户的 Bug 详情文本
     * @throws Exception 查询、解析或本地保存失败时传播
     */
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

    /**
     * 将任务标记为完成，并以当前 UTC 时间作为完成时间。
     *
     * @param taskId 任务 ID
     * @param consumed 本次填写的消耗工时
     * @return 成功或失败的用户提示
     */
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

    /**
     * 使用指定解决方案将 Bug 标记为已解决。
     *
     * @param bugId Bug ID
     * @param resolution 禅道接受的解决方案代码
     * @return 成功或失败的用户提示
     */
    public String resolveBug(String bugId, String resolution) {
        ZentaoClient client = sessions.requireClient();
        if (client.resolveBug(bugId, resolution)) {
            return "✅ Bug " + bugId + " 已解决！\n\n解决方案: " + RESOLUTION_LABELS.get(resolution);
        }
        return "❌ 解决Bug失败，请检查Bug状态。";
    }

    /**
     * 将任意异常转换为工具统一使用的错误文本。
     *
     * @param throwable 工具处理期间捕获的异常
     * @return 带错误图标且不为空的文本
     */
    public static String errorText(Throwable throwable) {
        return "❌ " + errorMessage(throwable);
    }

    /**
     * 截断过长正文，避免一次 MCP 返回占用过多上下文；空内容统一显示“无”。
     */
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

    /** 沿无消息的异常包装向下查找，尽量返回最具体且可读的错误原因。 */
    private static String errorMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return isBlank(message) ? current.toString() : message;
    }

    /** 优先使用调用方给出的非空值，否则使用保存配置中的值。 */
    private static String firstNonBlank(String preferred, String fallback) {
        return isBlank(preferred) ? fallback : preferred;
    }

    /** 表格单元格缺少内容时使用短横线占位。 */
    private static String orDash(String value) {
        return isBlank(value) ? "-" : value;
    }

    /** 正文段落缺少内容时使用中文“无”占位。 */
    private static String orNone(String value) {
        return isBlank(value) ? "无" : value;
    }

    /** 字符串拼接时只把 {@code null} 转为空串，保留其他原始内容。 */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 工时为整数时移除无意义的小数部分，否则保留标准浮点文本。 */
    private static String formatNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /** 统一判断缺失、空串和仅含空白字符的文本。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
