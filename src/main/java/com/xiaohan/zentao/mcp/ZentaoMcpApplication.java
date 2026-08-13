package com.xiaohan.zentao.mcp;

import com.xiaohan.zentao.mcp.service.ZentaoSessionManager;
import com.xiaohan.zentao.mcp.tool.ZentaoMcpTools;
import com.xiaohan.zentao.mcp.tool.ZentaoToolService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 禅道 MCP STDIO 服务的 Java 17 启动入口。
 *
 * <p>该类只负责装配会话、业务服务、工具定义和 MCP 传输层。STDIO 模式要求
 * 标准输出完全留给 JSON-RPC 协议，因此运行日志由 SLF4J 写向标准错误流。</p>
 */
public final class ZentaoMcpApplication {
    /** MCP 客户端识别本服务时使用的稳定名称。 */
    public static final String SERVER_NAME = "zentao-mcp";
    /** 随 MCP 初始化响应公开的服务版本。 */
    public static final String SERVER_VERSION = "1.0.1";

    private static final Logger LOGGER = LoggerFactory.getLogger(ZentaoMcpApplication.class);

    // 纯启动类不需要实例化，所有生命周期均由 main 方法管理。
    private ZentaoMcpApplication() {
    }

    /**
     * 创建同步 MCP 服务并开始通过标准输入输出处理请求。
     *
     * <p>SDK 构建服务后会自行维护协议读取循环；这里注册关闭钩子，以便 JVM 退出时
     * 同时关闭 MCP 服务和附件下载线程池。</p>
     *
     * @param args 命令行参数；当前版本不读取任何参数
     */
    public static void main(String[] args) {
        try {
            // 按依赖方向装配：会话资源 -> 业务行为 -> MCP 工具契约 -> STDIO 传输层。
            ZentaoSessionManager sessions = new ZentaoSessionManager();
            ZentaoToolService toolService = new ZentaoToolService(sessions);
            ZentaoMcpTools tools = new ZentaoMcpTools(toolService);
            StdioServerTransportProvider transport =
                new StdioServerTransportProvider(McpJsonDefaults.getMapper());

            // 由 SDK 校验工具输入的 JSON Schema，具体业务失败仍由工具服务转成文本结果。
            McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .validateToolInputs(true)
                .tools(tools.specifications())
                .build();

            registerShutdownHook(server, sessions);
            LOGGER.info("Zentao MCP Server {} running on stdio", SERVER_VERSION);
            LOGGER.info("Config file: {}", sessions.configPath());
        } catch (Throwable throwable) {
            // 启动失败时不能向 stdout 写普通文本，否则会污染 MCP JSON-RPC 数据流。
            System.err.println("[zentao-mcp] 启动失败: " + throwable.getMessage());
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * 注册 JVM 关闭钩子，并保证资源清理最多执行一次。
     *
     * @param server 需要关闭的 MCP 服务实例
     * @param sessions 持有后台下载线程池的会话管理器
     */
    private static void registerShutdownHook(McpSyncServer server, ZentaoSessionManager sessions) {
        AtomicBoolean closed = new AtomicBoolean();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // compareAndSet 使重复触发的关闭路径只能有一个真正进入资源释放阶段。
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                server.close();
            } catch (Exception exception) {
                LOGGER.debug("关闭 MCP 服务失败", exception);
            } finally {
                // 即使协议服务关闭失败，也要给后台下载任务一次受控结束和清理的机会。
                sessions.close();
            }
        }, "zentao-mcp-shutdown"));
    }
}
