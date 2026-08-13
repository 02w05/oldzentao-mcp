package com.xiaohan.zentao.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 声明公开的 MCP 工具契约，并把每个工具调用连接到 {@link ZentaoToolService}。
 *
 * <p>本类负责工具名称、说明、JSON Schema、行为提示和参数类型转换，不包含禅道
 * 业务实现。SDK 先依据 Schema 校验请求，进入处理器后再由这里完成默认值处理，
 * 最终统一包装为单段文本结果。</p>
 */
public final class ZentaoMcpTools {
    // 明确声明 Schema 方言，确保 MCP 客户端按相同规则理解输入约束。
    private static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    // 与禅道 Bug 解决接口接受的枚举值保持一致。
    private static final List<String> RESOLUTIONS =
        List.of("fixed", "bydesign", "external", "willnotfix", "tostory");

    private final ZentaoToolService service;

    /**
     * 创建工具契约集合。
     *
     * @param service 实际执行业务行为的工具服务
     */
    public ZentaoMcpTools(ZentaoToolService service) {
        this.service = service;
    }

    /**
     * 按稳定顺序返回服务公开的八个同步工具。
     *
     * @return 可直接注册到 MCP 服务构建器的工具规格列表
     */
    public List<SyncToolSpecification> specifications() {
        return List.of(
            loginTool(),
            myTasksTool(),
            taskDetailTool(),
            myBugsTool(),
            storyDetailTool(),
            bugDetailTool(),
            finishTaskTool(),
            resolveBugTool()
        );
    }

    /** 声明登录工具；三个参数允许省略，以便业务层使用已保存配置补全。 */
    private SyncToolSpecification loginTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("baseUrl", stringProperty("禅道服务器地址 (如: https://zentao.example.com)"));
        properties.put("account", stringProperty("登录账号"));
        properties.put("password", stringProperty("登录密码"));
        Tool tool = tool(
            "zentao_login",
            "登录禅道系统",
            "登录禅道系统。首次使用会提示输入禅道地址、账号、密码，配置保存在本地。之后可自动登录。",
            objectSchema(properties, Set.of()),
            annotations(false, false, false)
        );
        return specification(tool, request -> service.login(
            optionalString(request, "baseUrl"),
            optionalString(request, "account"),
            optionalString(request, "password")
        ));
    }

    /** 声明任务列表工具，并在 Schema 中限制页码为正整数。 */
    private SyncToolSpecification myTasksTool() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("type", "integer");
        page.put("minimum", 1);
        page.put("maximum", Integer.MAX_VALUE);
        page.put("default", 1);
        page.put("description", "页码 (默认 1)");
        Tool tool = tool(
            "zentao_get_my_tasks",
            "获取我的任务列表",
            "获取当前登录用户的任务列表，每页返回10条数据。返回：项目名称、任务名称、需求ID、需求标题。",
            objectSchema(Map.of("page", page), Set.of()),
            annotations(true, false, true)
        );
        return specification(tool, request -> service.getMyTasks(optionalInt(request, "page", 1)));
    }

    /** 声明任务详情工具；任务 ID 为必填字符串。 */
    private SyncToolSpecification taskDetailTool() {
        Tool tool = tool(
            "zentao_get_task_detail",
            "获取任务详情",
            "根据任务ID获取任务详细信息和任务描述，自动下载图片和附件到本地。默认保存到 ~/.zentao-mcp/data/task/task-{任务ID}/ 目录。",
            objectSchema(Map.of("taskId", stringProperty("任务ID")), Set.of("taskId")),
            annotations(true, false, true)
        );
        return specification(tool, request -> service.getTaskDetail(requiredString(request, "taskId")));
    }

    /** 声明 Bug 列表工具，分页约束与任务列表保持一致。 */
    private SyncToolSpecification myBugsTool() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("type", "integer");
        page.put("minimum", 1);
        page.put("maximum", Integer.MAX_VALUE);
        page.put("default", 1);
        page.put("description", "页码 (默认 1)");
        Tool tool = tool(
            "zentao_get_my_bugs",
            "获取我的 Bug 列表",
            "获取当前登录用户的 Bug 列表，每页返回10条数据。返回：项目名称、Bug标题、Bug ID。",
            objectSchema(Map.of("page", page), Set.of()),
            annotations(true, false, true)
        );
        return specification(tool, request -> service.getMyBugs(optionalInt(request, "page", 1)));
    }

    /** 声明需求详情工具；查询会在业务层触发本地保存和后台资源下载。 */
    private SyncToolSpecification storyDetailTool() {
        Tool tool = tool(
            "zentao_get_story_detail",
            "获取需求详情",
            "根据需求ID获取需求详细信息，自动下载图片和附件到本地。默认保存到 ~/.zentao-mcp/data/product/story-{需求ID}/ 目录。",
            objectSchema(Map.of("storyId", stringProperty("需求ID")), Set.of("storyId")),
            annotations(true, false, true)
        );
        return specification(tool, request -> service.getStoryDetail(requiredString(request, "storyId")));
    }

    /** 声明 Bug 详情工具；Bug ID 为必填字符串。 */
    private SyncToolSpecification bugDetailTool() {
        Tool tool = tool(
            "zentao_get_bug_detail",
            "获取 Bug 详情",
            "根据Bug ID获取Bug详细信息，自动下载图片和附件到本地。默认保存到 ~/.zentao-mcp/data/bug/bug-{BugID}/ 目录。",
            objectSchema(Map.of("bugId", stringProperty("Bug ID")), Set.of("bugId")),
            annotations(true, false, true)
        );
        return specification(tool, request -> service.getBugDetail(requiredString(request, "bugId")));
    }

    /** 声明完成任务工具；消耗工时可省略并默认使用零。 */
    private SyncToolSpecification finishTaskTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("taskId", stringProperty("任务ID"));
        Map<String, Object> consumed = new LinkedHashMap<>();
        consumed.put("type", "number");
        consumed.put("minimum", 0);
        consumed.put("default", 0);
        consumed.put("description", "消耗工时(小时)，默认0");
        properties.put("consumed", consumed);
        Tool tool = tool(
            "zentao_finish_task",
            "完成任务",
            "标记任务为已完成。",
            objectSchema(properties, Set.of("taskId")),
            annotations(false, false, false)
        );
        return specification(tool, request -> service.finishTask(
            requiredString(request, "taskId"),
            optionalDouble(request, "consumed", 0.0d)
        ));
    }

    /** 声明解决 Bug 工具，并把解决方案限制为禅道支持的五个枚举值。 */
    private SyncToolSpecification resolveBugTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("bugId", stringProperty("Bug ID"));
        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("type", "string");
        resolution.put("enum", RESOLUTIONS);
        resolution.put("default", "fixed");
        resolution.put("description", "解决方案: fixed=已解决, bydesign=设计如此, external=外部原因, willnotfix=不予解决, tostory=转为需求");
        properties.put("resolution", resolution);
        Tool tool = tool(
            "zentao_resolve_bug",
            "解决Bug",
            "将Bug标记为已解决。",
            objectSchema(properties, Set.of("bugId")),
            annotations(false, false, false)
        );
        return specification(tool, request -> service.resolveBug(
            requiredString(request, "bugId"),
            optionalString(request, "resolution", "fixed")
        ));
    }

    /** 根据公共元数据组装一个 SDK 工具描述对象。 */
    private static Tool tool(
        String name,
        String title,
        String description,
        Map<String, Object> schema,
        ToolAnnotations annotations
    ) {
        return Tool.builder(name, schema)
            .title(title)
            .description(description)
            .annotations(annotations)
            .build();
    }

    /**
     * 创建 MCP 工具行为提示。
     *
     * <p>{@code openWorldHint} 始终为真，因为所有工具都可能与进程外的禅道服务交互。</p>
     */
    private static ToolAnnotations annotations(boolean readOnly, boolean destructive, boolean idempotent) {
        return ToolAnnotations.builder()
            .readOnlyHint(readOnly)
            .destructiveHint(destructive)
            .idempotentHint(idempotent)
            .openWorldHint(true)
            .build();
    }

    /**
     * 把可能抛出异常的业务处理器适配为 SDK 同步工具规格。
     *
     * <p>中断异常会恢复当前线程的中断标记；其他业务异常则统一转为带错误图标的文本，
     * 避免不同工具各自实现异常包装。</p>
     */
    private static SyncToolSpecification specification(
        Tool tool,
        ThrowingFunction<CallToolRequest, String> handler
    ) {
        return SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    return textResult(handler.apply(request));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return textResult(ZentaoToolService.errorText(exception));
                } catch (Exception exception) {
                    return textResult(ZentaoToolService.errorText(exception));
                }
            })
            .build();
    }

    /** 将工具服务生成的文本包装成 MCP 内容结果。 */
    private static CallToolResult textResult(String text) {
        // 只有 SDK 或 Schema 校验阶段的失败保留为 MCP 协议错误。
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    /**
     * 创建拒绝额外字段的 JSON Schema 对象定义。
     *
     * @param properties 属性名到属性 Schema 的映射；LinkedHashMap 可保持展示顺序
     * @param required 必填属性名集合
     */
    private static Map<String, Object> objectSchema(
        Map<String, Object> properties,
        Set<String> required
    ) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", JSON_SCHEMA_2020_12);
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 创建只描述类型和用途的字符串属性 Schema。 */
    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** 读取必填字符串，并对 Schema 之外的空白值再做一次业务层防御。 */
    private static String requiredString(CallToolRequest request, String name) {
        String value = optionalString(request, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    /** 读取可选字符串；参数不存在时返回 {@code null}。 */
    private static String optionalString(CallToolRequest request, String name) {
        Object value = arguments(request).get(name);
        return value == null ? null : value.toString();
    }

    /** 读取可选字符串，并把缺失值或空白值替换为指定默认值。 */
    private static String optionalString(CallToolRequest request, String name, String fallback) {
        String value = optionalString(request, name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 读取可选整数，并显式检查从通用 {@link Number} 转为 {@code int} 时不会溢出。
     */
    private static int optionalInt(CallToolRequest request, String name, int fallback) {
        Object value = arguments(request).get(name);
        if (!(value instanceof Number number)) {
            return fallback;
        }
        long parsed = number.longValue();
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " 超出允许范围");
        }
        return (int) parsed;
    }

    /** 读取可选浮点数，并拒绝 JSON 业务逻辑无法处理的无穷值和 NaN。 */
    private static double optionalDouble(CallToolRequest request, String name, double fallback) {
        Object value = arguments(request).get(name);
        if (!(value instanceof Number number)) {
            return fallback;
        }
        double parsed = number.doubleValue();
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException(name + " 超出允许范围");
        }
        return parsed;
    }

    /** 统一把 SDK 可能返回的空参数映射转换为空 Map。 */
    private static Map<String, Object> arguments(CallToolRequest request) {
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    /** 允许工具处理函数保留受检异常的轻量函数式接口。 */
    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        /** 应用一次工具调用。 */
        R apply(T value) throws Exception;
    }
}
