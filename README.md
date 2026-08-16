<h1 align="center">oldzentao-mcp</h1>

<p align="center">
  面向禅道企业版 4.1.3 以及开源版 12.5.X 旧版页面与 API 路由的 Java MCP 服务
</p>

<p align="center">
  让 Cursor、Claude Desktop、Codex 等 AI 助手直接查询和处理禅道任务、需求与 Bug
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.0.1-2563eb?style=flat-square" alt="version 1.0.1">
  <img src="https://img.shields.io/badge/Java-17%2B-f97316?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17+">
  <img src="https://img.shields.io/badge/Zentao-12.x-14b8a6?style=flat-square" alt="Zentao 12.x">
  <a href="https://modelcontextprotocol.io/"><img src="https://img.shields.io/badge/MCP-STDIO-7c3aed?style=flat-square" alt="MCP STDIO"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-MIT-22c55e?style=flat-square" alt="MIT License"></a>
</p>

<p align="center">
  <a href="#项目能力">项目能力</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#可用工具">可用工具</a> ·
  <a href="#数据与安全">数据与安全</a> ·
  <a href="#源码构建">源码构建</a>
</p>

## 项目能力

- **登录与会话**：首次登录后在本机保存配置，Cookie 失效时自动尝试重新登录
- **信息查询**：分页获取当前用户的任务和 Bug，查看任务、需求与 Bug 详情
- **本地归档**：保存结构化详情，并在后台下载页面图片和附件
- **工作流操作**：完成任务并记录消耗工时，或按指定方案解决 Bug
- **路径防护**：校验本地保存路径和文件名，避免数据写入预期目录之外

> [!NOTE]
> 项目聚焦已实现的禅道企业版 4.1.3 旧版工作流，暂不提供创建或关闭需求、创建或关闭 Bug、测试用例管理等功能。

## 快速开始

开始前请准备 Java 17 或更高版本、一个支持 STDIO MCP 的客户端，以及可访问的禅道企业版 4.1.3 站点。运行 `java -version` 可以确认 Java 环境。

### 1. 下载 JAR

下载仓库中的 [`target/zentao-mcp.jar`](./target/zentao-mcp.jar?raw=1)，并保存到固定位置。以下示例使用 Windows 路径：

```text
C:\tools\zentao-mcp.jar
```

macOS 或 Linux 可保存为 `/opt/zentao-mcp/zentao-mcp.jar`。

### 2. 接入 MCP 客户端

Cursor 与 Claude Desktop 使用相同的服务配置结构。分别在 `~/.cursor/mcp.json` 或 `claude_desktop_config.json` 中加入：

```json
{
  "mcpServers": {
    "zentao": {
      "command": "java",
      "args": ["-jar", "C:\\tools\\zentao-mcp.jar"]
    }
  }
}
```

Codex 可以通过一条命令完成配置：

```powershell
codex mcp add zentao -- java -jar "C:\tools\zentao-mcp.jar"
```

在 macOS 或 Linux 中，将示例路径替换为 `/opt/zentao-mcp/zentao-mcp.jar`。保存配置后重启 MCP 客户端；如果客户端找不到 `java`，请将 `command` 改为 Java 可执行文件的绝对路径。

### 3. 首次登录

配置完成后，可以直接告诉 AI 助手：

```text
请使用 zentao_login 登录禅道：
地址是 https://zentao.example.com
账号是 your_account
密码是 your_password
```

登录成功后，服务会从本机配置中读取凭据，后续无需重复输入。再次调用 `zentao_login` 时，也可以只提供需要修改的字段。

## 可用工具

| 工具 | 参数与作用 |
|------|------------|
| `zentao_login` | 登录禅道；`baseUrl`、`account`、`password` 均可选，首次使用时需提供完整信息 |
| `zentao_get_my_tasks` | 获取当前用户的任务列表，每页 10 条；`page` 可选，默认 `1` |
| `zentao_get_task_detail` | 获取并保存任务详情、图片和附件；`taskId` 必填 |
| `zentao_get_my_bugs` | 获取当前用户的 Bug 列表，每页 10 条；`page` 可选，默认 `1` |
| `zentao_get_story_detail` | 获取并保存需求详情、图片和附件；`storyId` 必填 |
| `zentao_get_bug_detail` | 获取并保存 Bug 详情、图片和附件；`bugId` 必填 |
| `zentao_finish_task` | 完成任务并填写本次消耗工时；`taskId` 必填，`consumed` 可选，默认 `0` |
| `zentao_resolve_bug` | 解决 Bug；`bugId` 必填，`resolution` 可选，默认 `fixed` |

`resolution` 支持 `fixed`（已解决）、`bydesign`（设计如此）、`external`（外部原因）、`willnotfix`（不予解决）和 `tostory`（转为需求）。

## 数据与安全

登录配置固定保存在 `~/.zentao-mcp/config.json`。详情、图片和附件默认写入 `~/.zentao-mcp/data`：

```text
~/.zentao-mcp/data/task/task-{任务ID}/
~/.zentao-mcp/data/product/story-{需求ID}/
~/.zentao-mcp/data/bug/bug-{BugID}/
```

如需修改数据目录，请在 MCP 服务配置中加入：

```json
"env": {
  "ZENTAO_MCP_DATA_DIR": "D:\\zentao-data"
}
```

该环境变量只改变详情和附件的保存位置，不影响登录配置文件。

> [!WARNING]
> `config.json` 包含明文保存的禅道地址、账号和密码。请限制文件访问权限，不要分享或提交该文件。

> [!CAUTION]
> `zentao_finish_task` 和 `zentao_resolve_bug` 会直接修改禅道数据，调用前请确认 ID、当前状态和账号权限。

使用时还需注意：

- 本项目针对禅道企业版 4.1.3 以及开源版 12.5.X 旧版页面和路由，其他版本或不同路由模式可能无法正常解析
- HTTPS 连接使用 Java 默认的证书校验，不支持跳过 SSL 验证
- 查询与写操作能否成功取决于禅道账号自身权限和任务或 Bug 的当前状态

## 源码构建

源码构建需要 Java 17 和 Maven：

```bash
git clone https://github.com/02w05/oldzentao-mcp.git
cd oldzentao-mcp
mvn clean package
```

构建完成后，可执行文件位于 `target/zentao-mcp.jar`。

该 JAR 是由 MCP 客户端启动的 STDIO 服务；没有 MCP 请求或标准输入关闭时，进程退出属于正常现象。

## 开源许可

项目基于 [MIT License](./LICENSE) 开源，可自由使用、修改和分发，但需保留原始版权与许可声明。

Copyright (c) 2026 wzh
