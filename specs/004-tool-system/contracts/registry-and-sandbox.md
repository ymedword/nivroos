# Contract: ToolRegistry / 适配器 / Sandbox（上层装配面）

> 面向**上层装配与 US-5**：注册、查找、全量列表是唯一取用入口；沙箱是动作放行闸门。

## 1. `ToolRegistry`（`nivroos-tool`，public）

```java
public void register(NivroTool tool);          // 重名：保留先注册者 + WARN，不覆盖
public NivroTool get(String name);             // 未命中返回 null，不抛
public Map<String, NivroTool> all();           // 不可变视图；装配进 ReActLoop / ToolExecutor
public void scanAnnotated(Object... beanCandidates);  // 内置工具与方式三 Bean 共用同一路径
```

- `scanAnnotated` 只认 `@Tool` 方法；无标注的 bean 静默跳过（容器遍历时大量 bean 会经过它）。
- 工具名来自 `@Tool(name = ...)`，**不来自 Java 方法名**；schema 来自注解生成
  （`ToolDefinition.inputSchema()`），不手写 JSON。
- **US-4 不交付**：`tool list` 命令与 `GET /api/v1/tools`（US-5 从 `all()` 取列表）。

## 2. 适配器

```java
// 包内可见：@Tool 注解路径的 NivroTool 适配
final class AnnotatedToolAdapter implements NivroTool {
    AnnotatedToolAdapter(ToolCallback callback);
    // 名称/描述/schema ← callback.getToolDefinition()
    // execute(JsonNode) → callback.call(json) → JSON 还原 ToolResult
    //   ToolExecutionException → 解包后抛原异常（保留 SandboxViolationException 类型与文案）
}

public final class McpToolAdapter implements NivroTool {
    McpToolAdapter(String serverName, McpSchema.Tool tool, McpSyncClient client);
    // execute(JsonNode) → callTool(new CallToolRequest(name, args))
    //   isError == true → ToolResult(false, null, 文本, false)
    //   Jackson 2（JsonNode）↔ Jackson 3（SDK）桥接只在此类
}
```

**不变量**：Provider 层永不调用 `execute`；`internalToolExecutionEnabled(false)` 保持不变；
工具选择与执行时机只由 `ReActLoop` + `ToolExecutor` 掌握（宪法原则二）。

**工具池边界**：`ReActLoop.resolveTools` 按 `Profile.tools` 从注册表解析，**声明几个就只给几个**；
未命中名字 WARN 跳过（工具池是能力边界，多给一个即越权）。

## 3. `Sandbox`（接口不变）与 `WhitelistSandbox`

```java
public interface Sandbox {                      // US-2 已交付，本模块不改
    void enforce(SandboxAction action);
}

public final class WhitelistSandbox implements Sandbox {
    WhitelistSandbox(List<String> allowedPaths,
                     List<String> allowedCommands,
                     List<String> allowedDomains);   // US-2 一参 → 本模块三参（改造点 1）
}
```

| ActionType | 校验 | 空列表 |
| --- | --- | --- |
| `FILE_READ` / `FILE_WRITE` | 绝对化 + `normalize()` 后前缀比对（不解析软连接） | 全拒 |
| `SHELL_COMMAND` | `trim()` 取首 token 逐字比对（大小写敏感） | 全拒 |
| `HTTP_REQUEST` | host 解析 + 通配符匹配（US-2 已交付，行不改） | 全拒 |

- 拒绝抛 `SandboxViolationException`，异常向上冒泡到 `ToolExecutor`；
  **不为 Sandbox 新增审计逻辑**（失败审计由既有路径覆盖：`success=false` + `error_message`）。
- 接口中立性（宪法原则六）：换容器 / microVM 实现**不需要**加方法；
  `NotifyChannelAdapter` 换企业微信官方 SDK 实现**不需要**改签名——两问答案都应为"不需要"。

## 4. 装配契约（`ToolConfiguration`，`nivroos-tool`）

| Bean | 装配要点 |
| --- | --- |
| `WhitelistSandbox` | Binder 读 `file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains` |
| `FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` | `ShellTools` 另接 `shell.timeout-seconds`（默认 30s） |
| `ToolRegistry` | 构造期收集内置工具 Bean + 遍历容器单例 Bean 送 `scanAnnotated`（覆盖 `nivroos-memory` 的 `MemoryTools` 与 boot 侧示例 `@Tool` Bean——**tool 模块对 memory 无编译期依赖**） |
| `McpClientService` | `initMethod = "start"` / `destroyMethod = "close"` |

依赖方向门禁（quickstart §1 有 grep）：`nivroos-tool` 下 `org.springframework.ai` 只允许
`org.springframework.ai.tool.*`（注解与 schema 生成），不得出现 `ChatClient` / `ChatModel` /
任何执行路径；`nivroos-tool` 不得依赖 `nivroos-cli`；`nivroos-core` 不得出现
`io.modelcontextprotocol` / `org.springframework.ai`。
