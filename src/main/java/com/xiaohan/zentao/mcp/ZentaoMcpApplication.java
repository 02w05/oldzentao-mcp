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

public final class ZentaoMcpApplication {

    public static final String SERVER_NAME = "zentao-mcp";

    public static final String SERVER_VERSION = "1.0.1";

    private static final Logger LOGGER = LoggerFactory.getLogger(ZentaoMcpApplication.class);

    private ZentaoMcpApplication() {
    }

    public static void main(String[] args) {
        // STDIO 的 stdout 仅供 MCP JSON-RPC 使用，日志和启动错误必须写入 stderr。
        try {
            ZentaoSessionManager sessions = new ZentaoSessionManager();
            ZentaoToolService toolService = new ZentaoToolService(sessions);
            ZentaoMcpTools tools = new ZentaoMcpTools(toolService);
            StdioServerTransportProvider transport =
                new StdioServerTransportProvider(McpJsonDefaults.getMapper());

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
            System.err.println("[zentao-mcp] 启动失败: " + throwable.getMessage());
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void registerShutdownHook(McpSyncServer server, ZentaoSessionManager sessions) {
        AtomicBoolean closed = new AtomicBoolean();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                server.close();
            } catch (Exception exception) {
                LOGGER.debug("关闭 MCP 服务失败", exception);
            } finally {
                sessions.close();
            }
        }, "zentao-mcp-shutdown"));
    }
}
