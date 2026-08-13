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

public final class ZentaoMcpTools {

    private static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private static final List<String> RESOLUTIONS =
        List.of("fixed", "bydesign", "external", "willnotfix", "tostory");

    private final ZentaoToolService service;

    public ZentaoMcpTools(ZentaoToolService service) {
        this.service = service;
    }

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

    private static ToolAnnotations annotations(boolean readOnly, boolean destructive, boolean idempotent) {
        return ToolAnnotations.builder()
            .readOnlyHint(readOnly)
            .destructiveHint(destructive)
            .idempotentHint(idempotent)
            .openWorldHint(true)
            .build();
    }

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

    private static CallToolResult textResult(String text) {
        // 业务失败保留为可读文本；只有协议或 Schema 校验失败才使用 MCP 错误。
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

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

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static String requiredString(CallToolRequest request, String name) {
        String value = optionalString(request, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static String optionalString(CallToolRequest request, String name) {
        Object value = arguments(request).get(name);
        return value == null ? null : value.toString();
    }

    private static String optionalString(CallToolRequest request, String name, String fallback) {
        String value = optionalString(request, name);
        return value == null || value.isBlank() ? fallback : value;
    }

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

    private static Map<String, Object> arguments(CallToolRequest request) {
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T value) throws Exception;
    }
}
