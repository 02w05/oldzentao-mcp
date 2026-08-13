# oldzentao-mcp

适配禅道 12.x 旧版页面和 API 路由的 Java MCP 服务，让支持 [Model Context Protocol（MCP）](https://modelcontextprotocol.io/) 的 AI 助手能够查询禅道任务、需求和 Bug，并执行完成任务、解决 Bug 等操作。

- 当前版本：`1.0.1`
- 传输方式：`STDIO`
- 运行环境：Java 17 或更高版本

## ✨ 功能特性

- 🔐 使用禅道地址、账号和密码登录，成功后保存本地配置并支持后续自动登录
- 📋 分页查询当前用户的任务和 Bug，每页返回 10 条记录
- 🔍 获取任务、需求和 Bug 详情
- 📥 将详情、图片和附件保存到本地，图片与附件在后台下载
- ✅ 完成任务并填写消耗工时
- 🛠️ 解决 Bug，支持禅道常用解决方案
- 🔄 Cookie 会话失效后自动尝试重新登录
- 🛡️ 对本地保存路径和文件名进行安全校验

> 本项目专注于已实现的旧版禅道工作流，目前不提供创建/关闭需求、创建/关闭 Bug、测试用例管理等功能。

## 🚀 快速开始

### 环境要求

- Java 17 或更高版本
- 一个支持 STDIO MCP 服务的客户端，例如 Cursor 或 Claude Desktop
- 可以访问的禅道 12.x 站点和有效账号

先确认 Java 已正确安装：

```bash
java -version
```

### 方式一：使用预编译 JAR（推荐）

从仓库下载 [`target/zentao-mcp.jar`](./target/zentao-mcp.jar)，并保存到一个固定位置。下面的示例假设 Windows 中保存为：

```text
C:\tools\zentao-mcp.jar
```

macOS 或 Linux 可以保存为：

```text
/opt/zentao-mcp/zentao-mcp.jar
```

#### Cursor 配置

编辑 `~/.cursor/mcp.json`。Windows 对应路径通常为 `%USERPROFILE%\.cursor\mcp.json`：

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

macOS 或 Linux 请将 `args` 中的 JAR 路径改为实际绝对路径：

```json
{
  "mcpServers": {
    "zentao": {
      "command": "java",
      "args": ["-jar", "/opt/zentao-mcp/zentao-mcp.jar"]
    }
  }
}
```

#### Claude Desktop 配置

编辑 `claude_desktop_config.json`：

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

#### Codex 配置

Codex CLI、Codex IDE 扩展和同一主机上的 ChatGPT 桌面端共享 MCP 配置。可以使用命令直接添加本服务：

```powershell
codex mcp add zentao -- java -jar "C:\tools\zentao-mcp.jar"
```

macOS 或 Linux 示例：

```bash
codex mcp add zentao -- java -jar /opt/zentao-mcp/zentao-mcp.jar
```

也可以手动编辑 `~/.codex/config.toml`：

```toml
[mcp_servers.zentao]
command = "java"
args = ["-jar", "C:\\tools\\zentao-mcp.jar"]
```

macOS 或 Linux 请将 `args` 中的路径替换为实际绝对路径。添加后可运行以下命令确认服务已经写入配置：

```bash
codex mcp list
```

在 Codex 终端界面中也可以使用 `/mcp` 查看已连接的服务。更多配置选项参见 [Codex MCP 官方文档](https://developers.openai.com/codex/mcp)。

保存配置并重启 MCP 客户端。如果系统找不到 `java`，请把 `command` 改为 `java.exe` 的绝对路径。

### 方式二：从源码构建

需要额外安装 Maven：

```bash
git clone https://github.com/02w05/oldzentao-mcp.git
cd oldzentao-mcp
mvn clean package
```

构建完成后，可执行 JAR 位于：

```text
target/zentao-mcp.jar
```

将 MCP 客户端配置中的路径指向这个文件即可。

> `zentao-mcp.jar` 是 STDIO MCP 服务，不是交互式命令行程序。通常应由 MCP 客户端启动，而不是通过双击运行。

## 🔐 首次登录

本项目不会要求你把禅道账号密码写入 MCP 客户端配置。首次使用时，调用 `zentao_login` 工具并提供：

| 参数 | 说明 | 示例 |
|------|------|------|
| `baseUrl` | 禅道站点根地址，包含 `http://` 或 `https://` | `https://zentao.example.com` |
| `account` | 禅道登录账号 | `your_account` |
| `password` | 禅道登录密码 | `your_password` |

可以直接告诉 AI 助手：

```text
请登录禅道：
地址是 https://zentao.example.com
账号是 your_account
密码是 your_password
```

登录成功后，配置会保存在当前用户目录下：

```text
~/.zentao-mcp/config.json
```

Windows 中对应：

```text
%USERPROFILE%\.zentao-mcp\config.json
```

之后调用其他工具时，服务会读取该配置并尝试自动登录。也可以再次调用 `zentao_login`，只传入需要修改的字段，其余字段会沿用已有配置。

> ⚠️ 当前版本会在本机以明文保存禅道地址、账号和密码。请保护好 `config.json`，不要分享或提交到版本控制系统。

## 🛠️ 可用工具

| 工具名称 | 参数 | 说明 |
|----------|------|------|
| `zentao_login` | `baseUrl`、`account`、`password` 均可选 | 首次登录需要提供完整信息；已有配置时可只更新部分字段 |
| `zentao_get_my_tasks` | `page` 可选，默认 `1` | 获取当前用户的任务列表，每页 10 条 |
| `zentao_get_task_detail` | `taskId` 必填 | 获取任务详情，并在本地保存详情、图片和附件 |
| `zentao_get_my_bugs` | `page` 可选，默认 `1` | 获取当前用户的 Bug 列表，每页 10 条 |
| `zentao_get_story_detail` | `storyId` 必填 | 获取需求详情，并在本地保存详情、图片和附件 |
| `zentao_get_bug_detail` | `bugId` 必填 | 获取 Bug 详情，并在本地保存详情、图片和附件 |
| `zentao_finish_task` | `taskId` 必填；`consumed` 可选，默认 `0` | 将任务标记为已完成，并填写本次消耗工时 |
| `zentao_resolve_bug` | `bugId` 必填；`resolution` 可选，默认 `fixed` | 将 Bug 标记为已解决 |

### Bug 解决方案

`zentao_resolve_bug` 的 `resolution` 支持以下值：

| 值 | 含义 |
|----|------|
| `fixed` | 已解决 |
| `bydesign` | 设计如此 |
| `external` | 外部原因 |
| `willnotfix` | 不予解决 |
| `tostory` | 转为需求 |

## 💬 使用示例

配置完成后，可以通过自然语言让 AI 助手调用工具：

```text
登录我的禅道，地址是 https://zentao.example.com，账号是 your_account，密码是 your_password。

查询我第一页的任务。

查看任务 123 的详情。

查询我第一页的 Bug。

查看需求 456 的详情。

查看 Bug 789 的详情。

完成任务 123，本次消耗 2 小时。

将 Bug 789 标记为已解决，解决方案使用 fixed。
```

## 📁 本地配置和数据

### 登录配置

登录配置固定保存在：

```text
~/.zentao-mcp/config.json
```

该文件包含明文凭据。目前不支持通过 `ZENTAO_URL`、`ZENTAO_ACCOUNT`、`ZENTAO_PASSWORD` 等环境变量配置登录信息。

### 详情、图片和附件

默认数据根目录为：

```text
~/.zentao-mcp/data
```

不同类型的数据分别保存在：

```text
~/.zentao-mcp/data/task/task-{任务ID}/
~/.zentao-mcp/data/product/story-{需求ID}/
~/.zentao-mcp/data/bug/bug-{BugID}/
```

详情查询会保存结构化 JSON 数据；任务和需求还会保存 `desc.md`。页面中的图片和附件会在后台下载到对应详情目录。

如需修改数据根目录，可以在 MCP 客户端配置中设置 `ZENTAO_MCP_DATA_DIR`：

```json
{
  "mcpServers": {
    "zentao": {
      "command": "java",
      "args": ["-jar", "C:\\tools\\zentao-mcp.jar"],
      "env": {
        "ZENTAO_MCP_DATA_DIR": "D:\\zentao-data"
      }
    }
  }
}
```

该环境变量只修改详情和附件的数据目录，不会修改登录配置文件的位置。

## ⚠️ 兼容性与安全说明

1. **禅道版本**：本项目面向禅道 12.x 的旧版页面和路由实现。其他版本或启用了不同路由模式的站点可能无法正常解析。
2. **账号权限**：查询或写操作是否成功取决于禅道账号本身的权限和当前任务/Bug 状态。
3. **HTTPS 证书**：当前版本使用 Java 默认的证书校验，不支持 `ZENTAO_SKIP_SSL`。使用 HTTPS 时，服务器证书必须受到运行该服务的 Java 信任库信任。
4. **密码保存**：密码以明文写入当前用户的 `~/.zentao-mcp/config.json`，请限制该文件的访问权限。
5. **写操作**：`zentao_finish_task` 和 `zentao_resolve_bug` 会直接修改禅道数据，调用前请确认任务或 Bug ID。

## ❓ 常见问题

### 客户端提示找不到 `java`

先运行 `java -version` 确认 Java 17 或更高版本已安装并加入 `PATH`。也可以在 MCP 配置中把 `command` 写成 `java` 可执行文件的绝对路径。

### 直接运行 JAR 后立即退出

这是 STDIO MCP 服务，需要 MCP 客户端通过标准输入输出与它通信。终端没有 MCP 请求或标准输入关闭时，程序退出属于正常现象。

### 工具提示“请先使用 zentao_login 工具登录禅道系统”

说明本机还没有有效配置，或者使用已保存凭据自动登录失败。重新调用 `zentao_login`，提供正确的禅道地址、账号和密码。

### HTTPS 站点出现证书错误

当前版本不会跳过 SSL 证书验证。请为禅道服务器配置受信任证书，或将内部 CA 证书导入运行该服务的 Java 信任库。

### 升级禅道后工具无法解析页面

本项目依赖禅道 12.x 旧版路由和页面结构。升级后的页面结构或路由模式发生变化时，可能需要同步调整客户端和解析器实现。

## 🔧 开发与构建

```bash
mvn clean package
```

Maven Shade Plugin 会生成包含运行依赖的可执行文件：

```text
target/zentao-mcp.jar
```

入口类为：

```text
com.xiaohan.zentao.mcp.ZentaoMcpApplication
```

## 📄 开源许可证

本项目采用 [MIT License](./LICENSE)，允许使用、复制、修改、合并、发布和分发本项目，但必须保留原始版权声明和许可证文本。

Copyright (c) 2026 wzh
