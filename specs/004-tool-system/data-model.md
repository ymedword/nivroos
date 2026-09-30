# Data Model: US-4 Tool 体系（Phase 1）

> 数据形状与落库形状的权威描述；类名 / 字段名 / 表列名均为**已定字面量**，
> 来源：颗粒度文档 §3.1/§3.4 + 技术方案 §6/§8.2。契约视角见 [contracts/](./contracts/)，
> 验证步骤见 [quickstart.md](./quickstart.md)。

## 1. WhitelistSandbox（唯一沙箱实现，三档补全）

| 成员 | 形态 |
| --- | --- |
| 构造 | `WhitelistSandbox(List<String> allowedPaths, List<String> allowedCommands, List<String> allowedDomains)`（US-2 为一参构造，本模块扩展——**前序改造点 1**；`Sandbox` 接口不变） |
| `enforce(SandboxAction)` | 按 `action.type()` 路由：`FILE_READ` / `FILE_WRITE` → `checkFilePath`；`SHELL_COMMAND` → `checkShellCommand`；`HTTP_REQUEST` → `checkHttpUrl`（US-2 已交付） |
| `checkFilePath(String)` | 私有。目标与白名单项双双 `toAbsolutePath().normalize()` 后前缀比对（相等或 `startsWith(root + separator)`）；不解析软连接；空列表 = 全拒 |
| `checkShellCommand(String)` | 私有。`trim()` 后取首个空白分隔 token，与白名单项逐字比对（大小写敏感）；空列表 = 全拒 |
| 拒绝 | 抛 `SandboxViolationException`（失败信息含目标值），异常上抛给 `ToolExecutor`，不在此类内记审计 |

## 2. ToolRegistry（运行期工具登记表）

| 成员 | 语义 |
| --- | --- |
| 内部结构 | `Map<String, NivroTool>`（并发安全容器，启动期写入、运行期只读） |
| `register(NivroTool)` | 工具名重复 = **保留先注册者 + WARN**，不覆盖（内置不被子进程工具静默顶掉） |
| `get(String)` | 未命中返回 `null`，不抛（`ReActLoop.resolveTools` 据此 WARN 跳过） |
| `all()` | 返回不可变 `Map`，装配进 `ReActLoop` / `ToolExecutor`（两处构造签名不变） |
| `scanAnnotated(Object... beanCandidates)` | 经 `MethodToolCallbackProvider` 取全部 `ToolCallback`，逐个包 `AnnotatedToolAdapter` 后 `register`；无 `@Tool` 方法的 bean 静默跳过 |

**两类适配器**（与工具来源一一对应，均 `implements NivroTool`）：

| 适配器 | 构造 | 名称/描述/schema 来源 | 执行 |
| --- | --- | --- | --- |
| `AnnotatedToolAdapter`（包内可见） | `AnnotatedToolAdapter(ToolCallback callback)` | `callback.getToolDefinition()` 的 `name()` / `description()` / `inputSchema()` | `callback.call(json)` → JSON 还原为 `ToolResult`；`ToolExecutionException` 解包后抛原异常 |
| `McpToolAdapter` | `McpToolAdapter(String serverName, McpSchema.Tool tool, McpSyncClient client)` | `tool.name()` / `tool.description()` / `tool.inputSchema()`（**缺失时合成空对象 schema**） | `client.callTool(new CallToolRequest(name, args))`；`isError == true` → `ToolResult(false, null, 文本, false)` |

## 3. 九个内置工具（名称与参数名是已定字面量）

| 工具名 | 承载类 | 参数（`@Tool` 生成） | 沙箱档 | 结果语义 |
| --- | --- | --- | --- | --- |
| `read_file` | `FileTools` | `path` | `FILE_READ` | 文件内容进 `content`；读取失败抛异常 |
| `write_file` | `FileTools` | `path` + `content` | `FILE_WRITE` | 成功文案；IO 失败抛异常 |
| `list_dir` | `FileTools` | `path` | `FILE_READ` | 条目逐行连接；空目录给明确文案 |
| `shell` | `ShellTools` | `command` | `SHELL_COMMAND` | stdout 进 `content`；非零退出 → `success=false` + stderr 进 `errorMessage`；超时 → 强杀 + 失败 |
| `http_get` | `HttpTools` | `url`（不变） | `HTTP_REQUEST` | 响应体进 `content`（US-2 行为不变，仅改标注与注册路径） |
| `http_post` | `HttpTools` | `url` + `body` | `HTTP_REQUEST` | 同 `http_get`；默认 `Content-Type: application/json` |
| `save_memory` | `MemoryTools`（US-3） | `content` + `scope`（不变） | 无（系统固定路径，不走文件白名单） | 与 US-3 逐字一致（改标注不改语义） |
| `recall_memory` | `MemoryTools`（US-3） | `query`（不变） | 无（同上） | 同上 |
| `notify` | `NotifyTools` | `content` + `channel` | `HTTP_REQUEST`（在适配器内、发送前） | 渠道解析失败 → `success=false` + 可用渠道名清单；非 2xx 抛异常 |

## 4. 通知渠道（全局注册表）

**`NotifyChannel`（core record）**：`name` / `type` / `url` / `description`（字段同技术方案 §6.8）。

**`NotifyTarget`（record，技术方案 §6.8 定义）**：`channelType` / `config`（`Map<String,Object>`，
webhook 档取 `config.get("url")`）。

**`NotifyChannelStore`（core 接口，依赖倒置）**：

```java
Optional<NotifyChannel> findByName(String name);
List<String> channelNames();   // 供 notify 失败信息回填可用渠道名清单（见 research §7，软门禁报告项）
```

**`NotifyChannelAdapter`（接口）**：`send(NotifyTarget target, String content)`；
唯一实现 `WebhookNotifyAdapter(Sandbox, HttpClient)`——先 `enforce(HTTP_REQUEST, url)`，
再 POST `{"content": "<文本>"}`，非 2xx 抛异常。

**表 `notify_channels`**（`nivroos-boot/src/main/resources/schema.sql`，幂等建表；手工 SQL 直插登记）：

```sql
CREATE TABLE IF NOT EXISTS notify_channels (
    name        VARCHAR(64)  PRIMARY KEY,
    type        VARCHAR(32)  NOT NULL,   -- 渠道类型（核心阶段仅 webhook）
    url         VARCHAR(512) NOT NULL,   -- webhook 地址（地址即凭证，不入 git）
    description VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL
);
```

**实体映射**（`nivroos-storage`）：**`NotifyChannelEntity`**（`@Entity` + `@Table(name = "notify_channels")`
+ `name` 主键 + `type` / `url` / `description` / `created_at`，注解风格同 `ToolInvocation`；
**类名不与 core 的
`NotifyChannel` record 重名**——裁决 2026-09-30，见 plan.md 软门禁报告项 4）
+ `NotifyChannelRepository` + `JpaNotifyChannelStore`（实现 core 接口，实体 → record 转换在此）
+ `NotifyChannelStoreConfiguration`（`@Bean` 同 `ToolInvocationStoreConfiguration` 模式）。

## 5. MCP 接入

**`McpServerConfig`（record）**：`name` / `transport` / `command` / `env`（`Map<String,String>`）。

**`mcp_servers.yaml`**（`.nivroos/` 下，结构见颗粒度文档 §3.3）：

```yaml
servers:
  - name: github-mcp
    transport: stdio            # 核心阶段仅 stdio；其它值 → WARN 跳过该 server
    command: "npx -y @modelcontextprotocol/server-github"   # 首 token = 可执行文件，其余 = args
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}    # 只允许占位；明文 → 大声报错
```

解析规则：文件不存在 = 无 MCP（正常启动）；`env` 值不含 `${...}` 且非空 → 抛清晰异常
（明文拒绝）；占位符指向的环境变量缺失 → 抛清晰异常。`command` 拆分为
`ServerParameters.builder(firstToken).args(rest).env(env)`。

**`McpClientService`**：`McpClientService(List<McpServerConfig>, ToolRegistry)`（生产构造）
+ 包内可见测试构造（第三参 `Function<McpServerConfig, McpClientTransport>`）；
`start()` = 逐个连接 → `initialize()` → `listTools()` 翻页取尽 → 逐个 `register`；
单 server 失败 WARN 跳过；`close()` 关停全部（失败降级 + WARN）。

## 6. Agent 目录派生（Profile 字段补齐）

`Profile`（core）**新增字段**（不删不改既有字段，技术方案 §8.2）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `description` | `String` | Agent 描述 |
| `identity` | `Identity`（`agentName` + `prompt`） | 身份；本模块只派生登记，**不额外注入**（颗粒度文档「待决事项」默认建议） |
| `mcpServers` | `List<String>` | 引用的 MCP server 名（frontmatter 键 `mcp_servers`） |
| `bootstrap` | `List<String>` | 引导文件列表（frontmatter 键 `bootstrap`） |
| `channels` | `List<Channel>`（`name` + `config`） | 接入渠道声明 |
| `schedules` | `List<Schedule>`（`cron` + `message`） | 定时任务声明（消费归 US-5） |

`AgentLoader.scan()`：扫 `.nivroos/agents/*/AGENT.md` 逐个派生（`loadProfile` 语义不变），
**单 Agent frontmatter 非法 → 抛清晰异常，由装配层捕获 WARN 跳过**（一个坏目录不拖垮全部）。
`ProfileRegistry` 不加新方法。

## 7. Skill L1 元数据（注入形态，不新增公开类型）

`ContextLoader.loadSystemPrompt()`（签名不变，每轮重扫）在 AGENT.md 正文与 bootstrap 之后
追加「可用技能」段，每个技能三行元数据：

```text
- name: <frontmatter name>
  description: <frontmatter description>
  path: <agentDir>/skills/<entryName>/SKILL.md    ← Agent 本地绝对路径（软连接路径）
```

- 采集：遍历 `agentDir/skills/` 下的软连接 → 解析真实目标必须位于 `.nivroos/skills/` 之内
  （越界 → WARN 跳过）→ 读目标 `SKILL.md` 的 frontmatter 取 `name` / `description`
  （缺任一 → WARN 跳过；正文为空不算无效）；
- **正文绝不进入 system prompt**（L2 由模型经 `read_file` 现取）；
- 技能**不进** `ToolRegistry`，不新增 `use_skill` 之类工具。

## 8. 配置绑定（`nivroos-tool` 装配侧）

| 键 | 类型 | 默认 | 消费者 |
| --- | --- | --- | --- |
| `file.allowed_paths` | `List<String>` | `[.nivroos/agents]` | `WhitelistSandbox.checkFilePath` |
| `shell.allowed_commands` | `List<String>` | `[python, git]` | `WhitelistSandbox.checkShellCommand` |
| `http.allowed_domains` | `List<String>` | US-2 既有值 | `WhitelistSandbox.checkHttpUrl` + `notify` |
| `shell.timeout-seconds` | `int` | `30` | `ShellTools(Sandbox, Duration)` |

读取一律经 `Binder`（`Environment.getProperty(key, List.class)` 返回 null，实测踩坑）。
三组白名单**留空 = 全拒**（部署说明必须写明）。

## 9. 关系

```text
ToolConfiguration（tool 模块装配）
  ├─ WhitelistSandbox ──► Sandbox(接口)
  ├─ FileTools / ShellTools / HttpTools / NotifyTools ──► NivroTool（经 @Tool 适配）
  ├─ ToolRegistry ◄── AnnotatedToolAdapter（内置 + 方式三 Bean）
  │                ◄── McpToolAdapter（方式二 MCP）◄── McpClientService ◄── mcp_servers.yaml
  └─ 注入 ApplicationContext 收集 Bean（含 memory 模块的 MemoryTools）→ scanAnnotated

NotifyTools ──► NotifyChannelStore(接口，core) ◄── JpaNotifyChannelStore（storage）◄── notify_channels 表
NotifyTools ──► NotifyChannelAdapter(接口) ◄── WebhookNotifyAdapter ──► Sandbox.enforce(HTTP_REQUEST) → HttpClient

ProfileConfiguration（boot 装配）
  └─ AgentLoader.scan() ──► Profile（含 mcp_servers / bootstrap / channels / schedules）
        └─ 跨模块校验：ProviderService.providerNames() / ToolRegistry / 文件系统
              └─ ProfileRegistry ──► AgentService（US-2 不变）

ContextLoader（core，每轮重扫）──► system prompt = AGENT.md 正文 + bootstrap（按 Profile）+ Skill L1 + 当前时间
```
