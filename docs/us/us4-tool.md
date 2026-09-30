# US-4 模块执行颗粒度文档：Tool 体系（内置 Tool 补全 + Plugin Tool 三档 + Sandbox 完整版）

> 裁决声明：本文档是 `/module-dev` 的输入。内容从《NivroOS 技术方案》§6 提炼，与
> 最新技术方案冲突时以技术方案为准（冲突必须停下报告，不得自行裁决）。
> 前序交付物（US-1：ProviderService/审计接口；US-2：ReActLoop/ToolExecutor/工具池/
> WhitelistSandbox HTTP 档；US-3：MemoryTools）已就位，本文档直接引用并单列改造点。

## 1. 模块概述

- **定位**：把"Agent 能干事"补齐——九个内置 Tool 全部到位（US-2 只交付 `http_get`）、
  Plugin Tool 三档基础设施（方式一零代码 Agent 目录复用 MCP / 方式二自写 MCP server /
  方式三重代码 `@Tool` Bean）、统一 `ToolRegistry`、`WhitelistSandbox` 三档白名单全量、
  `ContextLoader` 的 Skill 元数据（L1）注入与 `bootstrap` 字段消费。
- **价值**：业务方零 Java 代码就能给 Agent 长出新能力（需求文档 §5.6 三档按门槛取舍）；
  工具是 Agent 与外部世界的唯一单向通道，也是宪法六"安全是地基"的落点——所有
  文件/命令/HTTP 动作都必须先过白名单（技术方案 §6.7）。验收锚点：需求文档 §13
  「Demo 二：每日科技日报」的能力四部分（方式一 Agent 目录 + 方式二 MCP）与
  技术方案 §12.3（`shell` 跑 Agent 捆绑脚本）。

## 2. 设计要点与约束

### 2.1 职责边界

| 本模块职责 | 非本模块职责 |
| --- | --- |
| 九个内置 Tool 到齐（File/Shell/Http 补全，Notify 新建；`save_memory`/`recall_memory` 由 US-3 交付，本模块只换注册机制） | 工具调度与审计写入 → `ToolExecutor`（US-2 已交付，签名不变） |
| `ToolRegistry`（`@Tool` Bean 扫描 + MCP 工具注册 + 名查找） | 逐轮按 `Profile.tools` 过滤 → `ReActLoop.resolveTools`（US-2 已交付） |
| MCP Client（stdio：连接管理 + `tools/list` + `tool/call` + 失联容错） | SSE / Streamable HTTP transport → 扩展阶段（AiProgrammingGuide §4.4） |
| `WhitelistSandbox` 文件/Shell 两档补全（同一实现类，接口不变） | 容器 / microVM 沙箱 → 扩展阶段（技术方案 §6.7 升级表） |
| `NotifyTools` + `NotifyChannelAdapter`/`WebhookNotifyAdapter` + `notify_channels` 表 | 通知渠道 CRUD 端点与管理台 → 扩展阶段（技术方案 §7.3） |
| `AGENT.md` frontmatter 完整派生（`mcp_servers` 等字段）；`ContextLoader` 补 `bootstrap` 字段消费 + **Skill 元数据（L1）注入** | `AGENT.md` 正文注入机制本身 → `ContextLoader`（US-2 已交付；本模块只加注入内容，不改机制） |

### 2.2 关键约束

1. **工具执行唯一入口**：所有 Tool 经 `ToolExecutor` 执行；Provider 层只翻译不执行
   （宪法原则二）。既有先例：`FunctionCallingAdapter` 的 `ToolCallback.call` 抛
   `UnsupportedOperationException`；`internalToolExecutionEnabled(false)` 保持不变。
2. **Sandbox 校验在工具自身 `execute` 开头**（技术方案 §6.7/§6.2）：拒绝抛
   `SandboxViolationException`，走 `ToolExecutor` 既有失败审计路径（`success=false`
   + `error_message`），**不为 Sandbox 新增审计逻辑**。
3. **ActionType 路由**（技术方案 §6.7）：`FILE_READ`/`FILE_WRITE` 同路由
   `checkFilePath`；`SHELL_COMMAND` → `checkShellCommand`（拆首 token 比对）；
   `HTTP_REQUEST` → `checkHttpUrl`（US-2 已交付）。
4. **ReAct 循环不感知工具来源**（技术方案 §6.1）：内置 Tool、`@Tool` Bean、
   MCP Tool 都包装成 `NivroTool` 注册进 `ToolRegistry`，循环只按名解析。
5. **审计关联标识传导**（易漏维度）：`ToolExecutor.execute(String sessionId, …)` 的
   `sessionId` 来自 `ReActLoop` 的 `session.getSessionId()`；MCP/notify/文件/Shell
   工具**都不新增审计路径**，由既有机制覆盖。
6. **上下文对象传递机制**：Profile 经 `ProfileContext` ThreadLocal 传递（US-2 裁决，
   签名不变）；新增工具不读 Profile，不引入新的上下文传递方式。
7. **缓存语义**（易漏维度，四档各不同）：
   ① `AGENT.md` 正文每轮重读（US-2 已交付，改正文下一轮生效）；
   ② Profile 派生（frontmatter）在**启动扫描时一次性**完成，改 frontmatter 需重启或
   重新扫描（运行时目录监听归扩展阶段，技术方案 §11.3）；
   ③ MCP 工具列表在**启动连接时**拉取并常驻内存，`mcp_servers.yaml` 改动需重启；
   ④ Skill 元数据（L1）**每轮重扫软连接、不缓存**（本模块交付，与 ① 同语义：改动下一轮生效；
   断链 / 缺 frontmatter 的技能 WARN 跳过不阻断）。
8. **凭证纪律**（技术方案 §8.8）：MCP server 的 `env` 只允许 `${ENV_VAR}` 占位，缺失
   大声报错、明文不落盘；解析在 nivroos-tool 内本地实现（**不复用 nivroos-cli 的
   `ConfigLoader`**——会造成 tool → cli 反向依赖），规则与 `ConfigLoader.resolveEnv` 一致。
9. **脚本信任边界（技术方案 §12.3 注，必须如实告知）**：`shell` 跑 Agent 捆绑脚本时，
   脚本可自己发起网络请求，**绕过 `http_get` 的域名白名单**——白名单只管内置 HTTP 工具。
   装一个带脚本的 Agent = 信任这个 Agent 的作者；核心阶段对脚本只做"解释器 + 命令/路径"
   两道白名单，容器与网络隔离留扩展阶段。
10. **可观测性双轨**（易漏维度）：工具失败与沙箱拒绝必须**审计落库 + WARN 日志**两者都有
    （`ToolExecutor` 已实现；新增工具内部 catch 的失败同样记 WARN、不吞）。
11. **配置键是已定字面量**（技术方案 §6.7）：`file.allowed_paths`、
    `shell.allowed_commands`、`http.allowed_domains`。YAML 列表必须经 `Binder` 读取
    （`Environment.getProperty(…, List.class)` 返回 null，2026-08-31 实测踩坑）。
12. **依赖与版本**（纪律：坐标先核实）：MCP SDK 坐标 `io.modelcontextprotocol.sdk:mcp`
    1.1.3 **已在 `nivroos-tool/pom.xml` 声明**，无需新增第三方依赖。实测（2026-09-30，
    本地仓库 jar 核实）：`mcp` 是 POM 聚合器（空 jar），实体在 `mcp-core`，另带
    `mcp-json-jackson3`（Jackson 3 `tools.jackson.core` 3.0.3）；调用一律走
    **`McpSyncClient`**，不触碰 Reactor 异步 API（原则七）；Jackson 2（项目侧
    `JsonNode`）与 Jackson 3（SDK 侧）的桥接只允许出现在 `McpToolAdapter` 一处。
    装配侧新增 `org.springframework.boot:spring-boot`（`@Configuration`/`Environment`）
    属 Boot BOM 管理坐标，非新增第三方坐标（同 US-3 披露方式）。
13. **Skill 渐进披露契约**（宪法原则四、技术方案 §8.3；本模块交付）：`ContextLoader` 每轮
    只注入 **L1**（`name` + `description` + 本地绝对路径）；`SKILL.md` 正文**不预载、不进
    system prompt**，由模型经 `read_file` 现取（L2）；**不新增 `use_skill` 之类工具**——
    Skill 不进 `ToolRegistry`，只注入元数据。

### 2.3 核心逻辑（装配链路 + 执行链路：流程图 + 伪代码）

```text
[启动装配（Spring 容器）]
ToolConfiguration
  ├─ Binder 读三组白名单 + shell 超时 → WhitelistSandbox(paths, commands, domains)
  ├─ 内置 Tool Bean：FileTools / ShellTools / HttpTools / NotifyTools（nivroos-tool）
  │                    + MemoryTools（nivroos-memory，US-3 交付）
  ├─ ToolRegistry.scanAnnotated(beans)   ← 内置 Tool 与方式三 @Tool Bean 走同一条扫描注册
  │                                         路径（§6.6：扫描所有 @Tool 注解的方法）
  │                                         → 逐个包成 AnnotatedToolAdapter（implements NivroTool）
  └─ McpClientService.start()            ← 读 .nivroos/mcp_servers.yaml
        ├─ 每个 server：McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper())).build()
        ├─ initialize() → listTools() 逐页取尽（nextCursor 为空为止）
        ├─ 每个 MCP 工具 → new McpToolAdapter(serverName, tool, client) → registry.register（方式二）
        └─ 连接失败：WARN + 跳过该 server（不阻断启动，§6.4「失联容错」）

ProfileConfiguration（启动扫描）
  └─ AgentLoader.scan() → 逐个 AGENT.md 派生完整 Profile → 跨模块校验（provider 存在 /
     tools 已注册 / bootstrap 文件存在 / mcp_servers 已配置）→ ProfileRegistry.register
     （单个 Agent 失败仅 WARN，不阻断其它 —— 技术方案 §8.2）
```

```text
[执行（模型返回 tool call）]
ReActLoop → ToolExecutor.execute(sessionId, call)
  ├─ 按名解析（US-2：Map<String, NivroTool>，装配自 ToolRegistry.all()）+ Profile.tools 过滤
  ├─ 未注册 → 失败审计（Unknown tool）
  └─ 命中 → tool.execute(input)
        ├─ sandbox.enforce(FILE_READ|FILE_WRITE|SHELL_COMMAND|HTTP_REQUEST, target)
        │     （File/Shell/Http 在方法首步；notify 在适配器内、发送前）
        │     └─ 拒绝抛 SandboxViolationException（../ 穿越 / 首 token / 域名）
        ├─ 内置：FileTools（路径规范化）/ ShellTools（ProcessBuilder + 超时）/
        │        HttpTools（JDK HttpClient）/ NotifyTools（注册表解析 → WebhookNotifyAdapter
        │        → 发送前再过 HTTP 域名白名单）
        └─ MCP：McpToolAdapter.execute → McpSyncClient.callTool(CallToolRequest) → ToolResult
  ├─ 成功 / 失败都写 tool_invocations（sessionId 关联）；异常路径 WARN 日志
  └─ 失败回填对话上下文：content 为空时用 errorMessage（US-2 已交付，MCP 错误同路）
```

```text
ToolRegistry:
    register(NivroTool tool)                        # MCP 工具注册路径；重名策略见待决事项
    get(String name): NivroTool                     # 未注册返回 null（ReActLoop 告警跳过）
    all(): Map<String, NivroTool>                   # 装配进 ReActLoop / ToolExecutor（签名不变）
    scanAnnotated(Object... beanCandidates)         # 内置 Tool 与方式三共用（§6.6）：@Tool 方法 →
                                                    #   ToolCallback.getToolDefinition() 取 schema
                                                    #   → 包成 AnnotatedToolAdapter（调用 = MethodToolCallback.call）

McpClientService:
    start()                                         # 连全部 server；单个失败 WARN 跳过
    close()                                         # 关停子进程

McpToolAdapter implements NivroTool:
    execute(JsonNode input): ToolResult             # callTool 转发；isError=true → success=false

AnnotatedToolAdapter implements NivroTool:          # 包内可见；@Tool 注解路径的适配器（与 McpToolAdapter 对称）
    execute(JsonNode input): ToolResult             # schema = getToolDefinition()；执行 = MethodToolCallback.call(json)

WhitelistSandbox:
    enforce(SandboxAction action)
    checkFilePath(String path)                      # 规范化后前缀比对；../ 越界即拒
    checkShellCommand(String command)               # 首 token 比对
    checkHttpUrl(String url)                        # US-2 已交付
```

> 伪代码中的方法名/签名与 §3.1 交付物清单逐字一致（实施时按此落地，不得发明签名）。

### 2.4 差异裁决注

1. **内置 Tool 数量**：需求文档 §5.6 标题写"核心阶段 5 个"、表内 8 行（缺 `notify`）；
   技术方案 §6.2 为"九个"（含 `notify`），需求文档 §13 功能验收清单也含 `notify`
   → **以 §6.2 九个为准**。
2. **`notify_channels` 表**：技术方案 §9.2 的"核心表五张"未列该表，§6.8 明确"持久化在
   SQLite 的 `notify_channels` 表" → **以 §6.8 为准**，建表进 `schema.sql`；§9.2 枚举
   属未同步表述。
3. **Demo 口径漂移**：CLAUDE.md 五大能力表与 AiProgrammingGuide §4.4 写"Demo 三：零代码
   PR digest"，技术方案 §12.3 是"每日 GitHub 日报（AGENT.md + `scripts/`）"，需求文档
   §13 只有两个 Demo（每日天气 / 每日科技日报）→ 本模块**人工验收锚点**取需求文档 §13
   「Demo 二的能力四部分」+ 技术方案 §12.3（`shell` 跑捆绑脚本）；Demo 命名的统一
   归后续文档收尾（见待决事项）。
4. **`shell` 的沙箱粒度**：技术方案 §12.3 演示文案写"`file.allowed_paths` 限定到该 Agent
   的 `scripts/` 目录"，§6.7 机制是"拆出命令首个 token 比对白名单" → **机制以 §6.7
   为准**，本模块**不**实现"shell 命令参数里的路径校验"；若要加严，属行为契约变更，
   先裁决再改（软门禁）。
5. **`ToolRegistry` 落位与过滤职责**：§6.6 把"按 `Profile.tools` 过滤"列为 Registry 职责。
   本模块把 `ToolRegistry` 放 `nivroos-tool`（module-dev 落位表），**逐轮过滤保留在
   `ReActLoop.resolveTools`**（US-2 已交付、签名不变），Registry 提供装配期目录与查找
   ——core 不反向依赖 tool 模块，且不改已交付签名。
6. **启动校验的职责分工**：§8.2 把"Provider 是否存在 / Tool 是否注册 / Bootstrap 是否存在"
   的校验挂在 `AgentLoader`。`AgentLoader` 在 core，看不到 `ToolRegistry`（tool 模块）
   与容器 Bean → 本模块拆为：`AgentLoader.scan()` 负责**派生与结构校验**（frontmatter
   合法性，US-2 已有），**跨模块校验在装配层 `ProfileConfiguration`**（同时可见
   `ProviderService.providerNames()`、`ToolRegistry`、文件系统），失败 WARN 不阻断。
   校验意图不变，只换落点。
7. **工作区目录图**：需求文档 §5.1 目录图缺 `mcp_servers.yaml`（技术方案 §8.1 有，
   `InitCommand` 已创建）→ 以 §8.1 为准。
8. **参考讲义（`output/markdown/` OryxOS 课程）的边界**（skill 规则：只借结构与讲法，
   不借事实）：讲义第 19 节把通知渠道放在 Profile 字段、第 20 节另有 5 个本项目未定义的
   内置工具（`edit_file` / `grep` / `glob` / `ask_user` / `web_search`）、并含
   `PermissiveSandbox` 档 → **均不引入**：渠道以 §6.8 的 SQLite 注册表为准（见注 2）、
   工具数以 §6.2 九个为准（见注 1）、沙箱以 §6.7 唯一 `WhitelistSandbox` 为准。
   本模块只从讲义借鉴讲法与结构（契约测试、顺序断言、绕过用例、接口中立性自查）。

## 3. 交付物清单

### 3.1 代码

| 模块 | 交付物 |
| --- | --- |
| nivroos-tool | `ToolRegistry`（`register(NivroTool)` / `get(String)` / `all(): Map<String, NivroTool>` / `scanAnnotated(Object... beanCandidates)`：内置 Tool 与方式三 Bean 共用一条扫描注册路径，@Tool 方法逐个包成 `AnnotatedToolAdapter`，schema 取自 `ToolCallback.getToolDefinition()`）；`FileTools`（`@Tool` 方法：工具名 `read_file` / `write_file` / `list_dir`，入参 `path`（`write_file` 另带 `content`）；每个方法首步 `sandbox.enforce`）；`ShellTools`（`ShellTools(Sandbox, Duration timeout)`；`@Tool` 方法：工具名 `shell`，入参 `command`；ProcessBuilder 执行 + 超时强杀）；`HttpTools`（US-2 已交付 `http_get`；**本模块**：`http_get` 改 `@Tool` 标注（工具名 / 参数名不变）+ 新增 `@Tool` 方法 `http_post`）；`NotifyTools`（`NotifyTools(Sandbox, NotifyChannelStore, NotifyChannelAdapter)`；`@Tool` 方法：工具名 `notify`，入参 `content` / `channel`）；`NotifyChannelAdapter`（接口：`send(NotifyTarget target, String content)`）+ `NotifyTarget`（record：`channelType` / `config`，技术方案 §6.8 定义）+ `WebhookNotifyAdapter`（唯一实现；`WebhookNotifyAdapter(Sandbox, HttpClient)`——HttpClient 可注入以便单测 mock，同 `HttpTools` 先例；发送前 `Sandbox.enforce(HTTP_REQUEST, url)`）；`McpServerConfig`（record：`name` / `transport` / `command` / `env` + 从 `.nivroos/mcp_servers.yaml` 解析，env 占位经本地 `${ENV_VAR}` 解析）；`McpClientService`（`McpClientService(List<McpServerConfig>, ToolRegistry)`；`start()`：连接 + `tools/list` + 注册 + 失联容错；`close()`；另有包内可见测试构造，第三参 `Function<McpServerConfig, McpClientTransport>` 供单测注入 mock transport）；`McpToolAdapter implements NivroTool`（`McpToolAdapter(String serverName, McpSchema.Tool tool, McpSyncClient client)`；execute → `callTool` 转发 + 结果映射，Jackson 2↔3 桥接只在此类）；`AnnotatedToolAdapter implements NivroTool`（包内可见；`@Tool` 注解路径的 NivroTool 适配，与 `McpToolAdapter` 对称；`execute(JsonNode input)` 内部用 Spring AI 的 `MethodToolCallback`——`getToolDefinition()` 取 schema、`call(json)` 执行；裁决 2026-09-30）；`WhitelistSandbox` 补全（构造扩展为 `WhitelistSandbox(List<String> allowedPaths, List<String> allowedCommands, List<String> allowedDomains)`；新增私有 `checkFilePath` / `checkShellCommand`）；`ToolConfiguration`（`@Configuration`：Binder 读三组白名单与 shell 超时 → 装配 Sandbox + 各 Tool Bean + `ToolRegistry` Bean（注入 `ApplicationContext` 取容器 Bean 逐个送入 `scanAnnotated`，与 nivroos-memory 的 `MemoryTools` 无编译期依赖）+ `McpClientService` Bean，生命周期用 Bean 的 `initMethod`/`destroyMethod`） |
| nivroos-core | `Profile` 字段补齐（`description` / `identity`（`agentName` / `prompt`）/ `mcp_servers` / `bootstrap` / `channels` / `schedules`，逐项对齐技术方案 §8.2）；`AgentLoader` 完整派生（US-2 的 `loadProfile(String)` 保留 + 新增 `scan()` → 扫 `.nivroos/agents/*/AGENT.md` 逐个派生，单 Agent 结构非法时抛清晰异常）；`NotifyChannel`（record：`name` / `type` / `url` / `description`，字段同 §6.8）+ `NotifyChannelStore` 接口（`findByName(String) → Optional<NotifyChannel>`，依赖倒置：接口在 core、实现在 storage，同 `ToolInvocationStore` 先例）；`ContextLoader` 扩展（US-2 改造点）：新增 **Skill 元数据（L1）注入**——扫 `agentDir/skills/` 下的软连接 → 读各 `SKILL.md` frontmatter 取 `name` / `description`（复用公开的 `ContextLoader.stripFrontmatter` + SnakeYAML，core 内已有依赖）→ 系统提示词追加「可用技能」段（**只有 name + description + 本地绝对路径**）；`loadSystemPrompt()` 签名不变、每轮重扫不缓存 |
| nivroos-storage | `NotifyChannel` 实体 + `NotifyChannelRepository`（Spring Data JPA）+ `JpaNotifyChannelStore`（实现 core 的 `NotifyChannelStore`）+ `NotifyChannelStoreConfiguration`（同 `ToolInvocationStoreConfiguration` 模式） |
| nivroos-boot | `ProfileConfiguration`（启动扫描：`AgentLoader.scan()` → 跨模块校验（provider / tools / bootstrap / mcp_servers）→ `ProfileRegistry` Bean；单个 Agent 失败 WARN 不阻断）；`application.yml` 增加 `file.allowed_paths` / `shell.allowed_commands` 段（`http.allowed_domains` 沿用）；`schema.sql` 追加 `notify_channels` 建表；示例包内一个**方式三示例 `@Tool` Bean**（演示用途、非产品工具，类名实施时定；编译进进程供人工验收调用——落位裁决 2026-09-30） |
| 前序改造点（软门禁，见 §3.2） | 见下 |

### 3.2 前序改造点（软门禁：实施时停下报告）

1. **`WhitelistSandbox`（US-2 已交付）构造签名扩展**：由 `WhitelistSandbox(List<String> allowedDomains)`
   扩为三组白名单入参，`Sandbox` 接口本身**不变**（技术方案 §6.7 要求"同一实现类扩展"）；
   `WhitelistSandboxTest` 同步补路径/命令用例。
2. **`MemoryTools`（US-3 已交付）与 `HttpTools`（US-2 已交付）改标 `@Tool`**：工具名与
   参数名是已定字面量（`save_memory`/`recall_memory`：`content`/`scope`/`query`；
   `http_get`：`url`），**改标注不改名字**；schema 来源从手写 JSON 切到注解生成；
   注册机制由"US-2 手工 Map"切换为 `ToolRegistry.scanAnnotated`（§6.6：内置 Tool 与
   方式三同一路径）；`MemoryToolsTest` / `HttpToolsTest` 同步。
3. **`ChatCommand`（US-2 已交付）装配改造**：`Map.of("http_get", …)` 手工工具池替换为容器注入的
   `ToolRegistry` / Sandbox / 各 Tool 组（工具池 = `toolRegistry.all()`），`ToolExecutor` 与
   `ReActLoop` 构造签名不变；新增 `file/shell` 配置与 notify 渠道依赖。
4. **`ContextLoader`（US-2 已交付）Bootstrap 列表改为按 `Profile.bootstrap`**：字段缺失时
   回退默认三件（AGENTS.md / SOUL.md / USER.md）+ WARN；缺失文件仍不阻断（US-2 规格
   FR-010：改文件下一轮立即生效、引导文件缺失告警不阻断）；`ContextLoaderTest` 同步。
   **同批交付**（裁决 2026-09-30）：Skill 元数据（L1）注入——软连接扫描 + `SKILL.md`
   frontmatter 取 `name` / `description` + 绝对路径注入；正文不预载（L2 由模型 `read_file`
   现取，宪法原则四）。
5. **`Profile` / `AgentLoader` / `ProfileRegistry`（US-1/US-2 已交付）字段与派生补齐**：
   `Profile` 增字段（不删不改既有字段）、`AgentLoader` 增 `scan()`（`loadProfile` 语义不变）、
   `ProfileRegistry` 不加新方法；`AgentLoaderTest` 同步。
6. **`ApiController`/`tool list` 命令（US-5 范围）**：本模块**只**交付 `ToolRegistry`，
   端点与命令由 US-5 接（见 §3.5 占位）。
7. **`ReActLoop`（US-2 已交付）工具池过滤补测**：`resolveTools` 是私有方法、**不改签名**，
   只补 `ReActLoopTest` 用例——「池内恰好是 `Profile.tools` 声明的那几个（不多不少）」
   （见 §4.2 `ReActLoopTest` 行）。风险来源：US-2 时池子里只有 2 个工具，本模块后变 9 个 + MCP 若干，
   过滤一旦回归就是**工具池泄漏**（模型可调 Agent 未声明的工具）——工具池是能力边界，
   必须钉死。

### 3.3 配置

```yaml
# application.yml —— Sandbox 三档白名单（技术方案 §6.7；键名是已定字面量）
# 配置边界（部署说明必须写明）：三组白名单留空 = 全部拒绝，不是"不校验"
file:
  allowed_paths:            # 文件工具路径白名单
    - .nivroos/agents
shell:
  allowed_commands:         # Shell 命令首 token 白名单
    - python
    - git
  # timeout-seconds: 30     # 见待决事项（值技术方案未规定）
http:
  allowed_domains:          # 沿用 US-2 交付的键（notify webhook 共享同一份）
    - wttr.in
    - api.github.com
```

```yaml
# .nivroos/mcp_servers.yaml —— 文件结构见待决事项（技术方案 §6.4 只定字段：name/transport/command/env）
servers:
  - name: github-mcp
    transport: stdio
    command: "npx -y @modelcontextprotocol/server-github"   # 首 token 为可执行文件
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}    # 只允许占位，明文被拒
```

### 3.4 数据表

`notify_channels`（通知渠道全局注册表，字段同技术方案 §6.8；手工建表脚本，随 `schema.sql` 维护）：

```sql
CREATE TABLE IF NOT EXISTS notify_channels (
    name        VARCHAR(64)  PRIMARY KEY,
    type        VARCHAR(32)  NOT NULL,   -- 渠道类型（核心阶段仅 webhook）
    url         VARCHAR(512) NOT NULL,   -- webhook 地址
    description VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL
);
```

- 核心阶段注册渠道的方式（裁决 2026-09-30）：**手工 SQL 直插**（核心阶段不提供 CRUD 端点 /
  CLI 子命令，技术方案 §7.3 归扩展阶段）：

  ```sql
  INSERT INTO notify_channels (name, type, url, description, created_at)
  VALUES ('ops-team', 'webhook', '<真实 webhook URL>', '运维值班群', CURRENT_TIMESTAMP);
  ```

  URL 是凭证本身（拿到即能发消息）：操作者从环境变量取真实值填入，**真实 URL 不写进文档、
  脚本和 git**（同 §3.3 MCP env 的凭证纪律）。

### 3.5 后续模块占位（本模块不实现，仅登记接口点）

- US-5：`tool list` 命令与 `GET /api/v1/tools` 从 `ToolRegistry.all()` 取列表；
  `GET /api/v1/profiles` 需要 `ProfileRegistry` 的列表能力（本模块不加，按 US-5 需要补）。
- 扩展阶段：通知渠道 CRUD 端点（`/api/v1/notify-channels`）、容器/microVM 沙箱实现、
  MCP 的 SSE / Streamable HTTP transport、Profile 级 Tool Policy（allow/deny）。

## 4. 验收测试（Harness）

### 4.1 测试分层

- **单测**（默认全量执行）：**不碰真实网络、不起真实子进程联网**。文件工具用 `@TempDir`；
  Shell 用 `echo` 之类无副作用命令；HTTP 与 webhook 用 mock `HttpClient`（沿用 `HttpToolsTest`
  写法）；MCP 用 mock `McpClientTransport` / mock `McpSyncClient`，**不启动真实 MCP server**。
- **测试接缝**：MCP 单测经包内可见测试构造注入 mock transport（生产构造两参，SDK 侧自建
  `StdioClientTransport`）；工具一律经 `ToolRegistry` 取用，测试路径与生产一致；契约测试
  从 `ToolRegistry.all()` 取全量——内置 / 方式三 / MCP 三类工具一次覆盖。
- **越界用例的判定标准**：安全类负向用例光断言"抛了异常"不够，必须证明危险动作**没有发生**
  （mock 底层执行器 `verify(never())`、断言文件未落盘、断言请求未发出）——路径穿越、
  白名单外命令/域名、未认证渠道各一条。
- **人工冒烟**（第 6 节，不进 CI）：真实社区 MCP server（stdio）+ 真模型跑方式一/方式二；
  `shell` 跑真实脚本（方式三由单测 + 人工各覆盖一半）。

### 4.2 测试类与验收点映射

| 测试类 | 验收点 |
| --- | --- |
| `WhitelistSandboxTest`（US-2 改造点同步） | 域名（已交付，回归）+ **通配符边界：`*.example.com` 命中 `api.example.com`、不命中 `evil-example.com`**（点号边界——`endsWith` 经典漏洞；US-2 实现已正确，本用例防重构回归）；**路径白名单：白名单内放行、`../` 穿越必拒**（关键回归）；命令首 token 白名单：命中放行、未命中拒绝、前导空白按 `trim` 处理、大小写逐字比对（Linux 大小写敏感）；空白名单 → 全拒（不静默放行） |
| `FileToolsTest` | `read_file` 读到内容；`write_file` 落盘；`list_dir` 列出条目；越界路径经 Sandbox 拒绝（异常冒泡到 ToolExecutor 路径）**且越界 `write_file` 后目标文件确实不存在**；IO 失败不吞 |
| `ShellToolsTest` | 白名单命令执行成功（stdout 进 `ToolResult.content`）；非白名单命令拒绝；超时终止且返回失败（不挂死）；**并发两路调用的输出不串**（关键回归） |
| `HttpToolsTest`（US-2 改造点同步） | `http_get` 回归（**改标 `@Tool` 后工具名 / 参数名逐字不变**）；**新增 `http_post`：方法 / URL / body 正确，且先过域名白名单**；白名单外域名 → mock `HttpClient` 断言**请求未发出**（`never()`） |
| `NotifyToolsTest` | 渠道名解析 → 适配器收到正确 `NotifyTarget`（`type` / `url` 来自注册表）；`channel` 缺省 / 渠道不存在 → 清晰失败（不静默）并回填可用渠道名（语义见待决事项）；审计由既有 `ToolExecutor` 路径覆盖（不新增） |
| `WebhookNotifyAdapterTest` | **`enforce` 先于发送（`InOrder` 顺序断言，关键回归）**；body 含 `content`、URL 取自 `NotifyTarget.config`（非硬编码）；白名单外域名 → 请求未发出且异常上抛；非 2xx → 异常上抛不吞 |
| `ToolContractTest` | 参数化遍历 `ToolRegistry.all()`（测试内三类各注册一个）：名称非空且唯一、描述非空、`getInputSchema()` 非空——**三类工具（内置 / 方式三 / MCP）一次覆盖**（关键回归：`FunctionCallingAdapter` 直接取 `tool.getInputSchema().value()` 拼 LLM 请求，任一工具 schema 为空会让**全部** LLM 调用失败） |
| `ReActLoopTest`（US-2 改造点同步） | 既有回归；**工具池按 `Profile.tools` 精确匹配：声明几个就只给几个，未声明的内置工具不进池**（关键回归：工具池是能力边界）；未注册的名字 WARN 跳过 |
| `ToolRegistryTest` | 内置 Tool 与方式三 Bean 走同一条扫描注册路径（§6.6）；`get` 命中/未命中（未命中返回 null，不抛）；**`@Tool` 扫描：名称/描述/JSON Schema 来自注解**（关键回归：schema 生成来自 Spring AI，且容器内不存在自动执行路径）；MCP 工具经 `register` 注册；重名策略见待决事项 |
| `McpToolAdapterTest` | `McpSchema.Tool` → `NivroTool`（名称/描述/schema 字符串）；`execute` → `callTool` 参数转发正确；`TextContent` → `content`；**`isError=true` → `success=false` 且错误信息回填**（关键回归） |
| `McpClientServiceTest` | `mcp_servers.yaml` 解析（name/transport/command/env）；env 占位缺失大声报错、明文拒绝；`tools/list` 结果全部注册；调用超时设定生效（值见待决事项）；**单个 server 连接失败 → WARN 且该 server 工具不注册、其它 server 不受影响、启动不阻断**（关键回归） |
| `ToolConfigurationTest` | 三组白名单经 Binder 绑定（列表非 null）；空白名单全拒；shell 超时配置生效；`ToolRegistry` Bean 含全部内置工具 |
| `AgentLoaderTest`（US-2 改造点同步） | 既有解析回归；**完整派生：`mcp_servers` / `bootstrap` / `channels` / `identity` / `description` / `schedules` 逐字段**；`scan()` 扫多 Agent；单个 Agent frontmatter 非法不阻断其它 |
| `ContextLoaderTest`（US-2 改造点同步） | 既有拼接/无缓存回归；**Bootstrap 列表按 `Profile.bootstrap` 生效**（非默认三件）；字段缺失回退默认三件 + WARN；**Skill L1 注入：软连接命中 → 注入 name + description + 绝对路径，且注入文本不含 `SKILL.md` 正文**（关键回归：渐进披露不得预载正文）；断链 / 缺 frontmatter → WARN 跳过不阻断；**每轮重扫**（新增技能下一轮可见、移除即消失） |
| `JpaNotifyChannelStoreTest` | 建表可写可读；`findByName` 命中/未命中；字段映射（type/url/description）正确 |
| `MemoryToolsTest`（US-3 改造点同步） | 既有行为回归；**改标 `@Tool` 后工具名与参数名逐字不变**（`save_memory`/`recall_memory`、`content`/`scope`/`query`） |

### 4.3 关键回归测试

```java
// 验收点：路径穿越必拒（宪法原则六；白名单是"劝阻级"防线，但穿越必须拦）
@Test
@DisplayName("路径穿越：../ 规范化后越出白名单必拒，白名单内相对路径放行")
void enforce_pathTraversalOutsideWhitelist_rejected() {
    WhitelistSandbox sandbox = new WhitelistSandbox(List.of("/tmp/ws"), List.of(), List.of());

    assertThatCode(() -> sandbox.enforce(
            new SandboxAction(ActionType.FILE_READ, "/tmp/ws/agents/a/AGENT.md")))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> sandbox.enforce(
            new SandboxAction(ActionType.FILE_READ, "/tmp/ws/agents/../../etc/passwd")))
        .isInstanceOf(SandboxViolationException.class);
}

// 验收点：白名单不能被"往外推"绕过——enforce 必须先于发送（顺序反了就是漏洞）
@Test
@DisplayName("notify 发送前必须先过 HTTP 域名白名单（顺序断言）")
void send_enforcesWhitelistBeforeDispatch() {
    Sandbox sandbox = mock(Sandbox.class);
    HttpClient client = mock(HttpClient.class);
    stubOk(client);                                  // 测试内助手：mock HttpClient 返回 200
    NotifyChannelAdapter adapter = new WebhookNotifyAdapter(sandbox, client);
    String url = "https://qyapi.example.com/hook";

    adapter.send(new NotifyTarget("webhook", Map.of("url", url)), "hello");

    InOrder inOrder = inOrder(sandbox, client);
    inOrder.verify(sandbox).enforce(argThat(a -> a.type() == ActionType.HTTP_REQUEST
        && a.target().equals(url)));
    inOrder.verify(client).send(any(), any());       // 校验在前、发送在后
}

// 验收点：并发执行不串号——各自的输出必须回到各自的结果里
@Test
@DisplayName("Shell 并发：两路调用的输出不串（虚拟线程复用下的隔离）")
void shell_concurrentCalls_outputsDoNotMix() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.scanAnnotated(
        new ShellTools(new WhitelistSandbox(List.of(), List.of("echo"), List.of()),
            Duration.ofSeconds(5)));
    NivroTool shell = registry.get("shell");

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<ToolResult> a = executor.submit(() -> shell.execute(shellInput("echo AAA")));
        Future<ToolResult> b = executor.submit(() -> shell.execute(shellInput("echo BBB")));

        assertThat(a.get().content()).contains("AAA").doesNotContain("BBB");
        assertThat(b.get().content()).contains("BBB").doesNotContain("AAA");
    }
}

// 验收点：MCP 工具失败不吞——isError 映射成失败结果并带回错误文本
@Test
@DisplayName("MCP 工具返回 isError=true → success=false，错误文本回填给模型")
void execute_mcpToolReturnsError_mappedToFailure() {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.callTool(any())).thenReturn(
        McpSchema.CallToolResult.builder()
            .content(List.of(new McpSchema.TextContent("upstream 403: rate limited")))
            .isError(true)
            .build());
    NivroTool tool = new McpToolAdapter("github-mcp", toolNamed("search_prs"), client);

    ToolResult result = tool.execute(MAPPER.createObjectNode().put("q", "is:open"));

    assertThat(result.success()).isFalse();
    assertThat(result.content() == null ? result.errorMessage() : result.content())
        .contains("403");   // 失败信息必须能进对话上下文（否则模型不会收敛）
}

// 验收点：MCP server 失联不阻断启动，且该 server 的工具不注册（§6.4 容错）
@Test
@DisplayName("单个 MCP server 连接失败：WARN 跳过，不阻断启动，其它 server 工具正常注册")
void start_oneServerUnreachable_othersStillRegistered() {
    ToolRegistry registry = new ToolRegistry();
    McpClientService service =                       // 包内可见测试构造：注入 mock transport
        new McpClientService(
            List.of(configOf("broken", "definitely-not-a-real-command"), configOf("ok", "fake")),
            registry,
            transports(failingTransport(), workingTransport("ok-tool")));

    assertThatCode(service::start).doesNotThrowAnyException();

    assertThat(registry.get("ok-tool")).isNotNull();
    assertThat(registry.get("broken-tool")).isNull();
}
```

> 方法名英文 + `@DisplayName` 保留中文验收点；mock HTTP / mock transport 的构造
> 复用 US-2/US-3 测试既有写法；`shellInput(...)` / `stubOk(...)` 为测试内助手
> （分别生成 `{"command": …}` 入参与 mock 200 响应）；`InOrder` 断言用
> `org.mockito.Mockito.inOrder`；断言里用到的 SDK 类型以实测为准（本地 jar 已核实：
> `McpSchema.CallToolResult.builder().content(…).isError(Boolean)`）。

## 5. 范围边界

| 核心阶段不做 | 依据 |
| --- | --- |
| 让 Spring AI 自动执行工具（只做 schema 生成与协议翻译） | 宪法原则二 |
| 工具调用并行（一次响应多个按顺序执行） | 技术方案 §4.3 |
| MCP 的 SSE / Streamable HTTP transport（核心阶段只做 stdio） | AiProgrammingGuide §4.4 |
| MCP 的 resources / prompts 消费（只做 `tools/list` + `tool/call`） | 技术方案 §6.4 |
| 容器 / microVM 沙箱、脚本的网络隔离 | 技术方案 §6.7 升级表、§12.3 信任边界注 |
| Profile 级 Tool Policy（allow/deny 策略） | 技术方案 §6.7 要点二（扩展阶段） |
| 企业微信/飞书/钉钉专用 API（签名、AccessToken 刷新） | 技术方案 §6.8（只做通用 webhook） |
| 通知渠道 CRUD 端点与管理台 | 技术方案 §7.3（扩展阶段） |
| Agent 目录热加载 / 实时监听（改 frontmatter 需重启） | 技术方案 §11.3（扩展阶段） |
| Skill 正文预载（只注入元数据，正文经 `read_file` 现取） | 宪法原则四、技术方案 §8.3 |
| Shell 命令参数中的路径校验（只校验首 token） | 技术方案 §6.7（见差异裁决注 4） |
| `shell` 的资源占用限制（CPU/内存限额） | 需求文档 §5.6 提到"资源占用限制"但技术方案 §6.7 只给白名单机制 → 见待决事项 |

## 6. 验证与验收

1. **全量门禁**：`mvn clean verify` 全绿（含 US-1/US-2/US-3 全部测试回归绿——
   前序改造点见 §3.2）；新增坐标仅 `spring-boot`（BOM 管理，同 US-3 披露方式；**非 starter，
   不带自动装配**，故 `autoconfigure.exclude` 清单保持不变——MCP SDK 同理），
   `mvn -Psecurity verify` 复核抑制清单（MCP SDK 的 Jackson 3 传递依赖是否触发新告警，
   触发则按既有评审流程登记）。
2. **依赖方向核验**（机器可判）：`grep -rn "org.springframework.ai" nivroos-tool/src/main`
   只允许出现 `org.springframework.ai.tool.*`（注解与 schema 生成），**不得**出现
   `ChatClient` / `ChatModel` / 任何执行路径；`nivroos-core` 不出现 `McpSchema` /
   `io.modelcontextprotocol`（MCP 只在 tool 模块）；`nivroos-tool` 不依赖 `nivroos-cli`。
3. **全局不变量自查**：无 Spring AI 自动 tool 执行路径（`internalToolExecutionEnabled(false)`
   保持）；工具成败都落 `tool_invocations`；grep 无明文 key（MCP env 全 `${ENV_VAR}`）；
   Provider 显式映射不变；无 Reactor/`CompletableFuture`/自建线程池（MCP 走 `McpSyncClient`）；
   模块数仍为 9。
4. **人工冒烟项（等用户执行）**：
   - **方式二真机**：配一个真实 stdio MCP server（社区 `github-mcp` 或任一本地 server）→
     `nivroos chat --profile <agent>` 让模型调 MCP 工具 → 核对 `tool_invocations` 有该工具名
     与 `success=1`；
   - **方式一零代码**（需求 §13 Demo 二能力四部分）：Agent 目录 + `mcp_servers.yaml`
     全程零 Java 代码跑通一次日报类任务；
   - **方式三**：进程内 `@Tool` Bean（示例 Bean 落位见 §3.1 的 nivroos-boot 行）被模型调用一次；
   - **Skill 渐进披露**（宪法原则四）：Agent 目录挂一个真实技能软连接 → 模型从 L1 元数据
     命中 → `read_file` 读 `SKILL.md` 正文（L2）→ 完成一次任务；同时核对系统提示词里
     只有 name + description + 路径、**无正文**；
   - **`shell` 跑捆绑脚本**（技术方案 §12.3）：`python scripts/xxx.py` 经 `shell.allowed_commands`
     放行、脚本产出 JSON 进上下文、脚本代码不进；
   - **`notify` 真 webhook**：一个真实群机器人 webhook 收到推送，且未白名单域名被拒（负向）。
5. **验收 Demo 锚点**：需求文档 §13「Demo 二：每日科技日报」的能力四部分（方式一 +
   方式二 MCP）；技术方案 §12.3（`shell` + 捆绑脚本）。定时触发本身归 US-5
   （`AgentScheduler`），本模块用手动补跑（`nivroos chat`）验收。
6. **接口中立性自查**（思维练习，测不出来）：`Sandbox.enforce(SandboxAction)` 换成容器 /
   microVM 实现需要加方法吗？`NotifyChannelAdapter.send(NotifyTarget, String)` 换成企业
   微信官方 SDK 实现需要改签名吗？两问答案都应是"不需要"——需要就说明接口被某一档实现
   带偏了（宪法原则六与 §6.7 升级表成立的前提）。

## 已决事项（用户裁决 2026-09-30）

| 事项 | 裁决 |
| --- | --- |
| Skill 软连接扫描归属 | **归 US-4**，与 `ContextLoader` 改造同批交付（交付物见 §3.1 nivroos-core 行，前序改造点见 §3.2 第 4 条） |
| `@Tool` 方法的调用路径 | **复用 Spring AI `MethodToolCallback`**：`getToolDefinition()` 取 schema、`call(json)` 执行，落在 `AnnotatedToolAdapter`（§3.1）。依据：本地 javap 实证 1.1.2 的 `call` 不吞异常（`ToolExecutionException` 上抛），失败审计语义与手写工具一致，执行时机仍由 `ToolExecutor` 掌握，无自动执行路径 |
| 落位超出 module-dev 落位表的两处 | ① `NotifyChannelStore` 接口在 core、JPA 实现在 nivroos-storage；② 启动扫描 `ProfileConfiguration` 在 nivroos-boot（均见 §3.1） |
| 通知渠道注册入口 | 核心阶段**手工 SQL 直插** `notify_channels`（示例见 §3.4）；CRUD 端点归扩展阶段 |
| 方式三示例 Bean 落位 | `nivroos-boot` 示例包，演示用途、编译进进程（见 §3.1 nivroos-boot 行） |

## 待决事项

| 事项 | 说明 | 默认建议 |
| --- | --- | --- |
| MCP 调用超时值 | §6.4 要求"处理 server 失联、超时、错误恢复"，但未规定超时值与配置键；SDK 侧 `McpClient.SyncSpec.requestTimeout` 可设定 | 显式设一个值（建议 30s，同 `shell` 建议值风格）；如需按 server 可配置再加 `mcp` 段配置键（届时报软门禁） |
| 内置工具入参名 | §6.2 只给工具名与职责；已定字面量只有 `http_get`（`url`）、`save_memory`/`recall_memory`（US-3 已交付）、`notify`（§6.8 给 `content`/`channel`）；`read_file`/`write_file`/`list_dir`/`shell`/`http_post` 的参数名技术方案未规定 | `read_file`/`list_dir`: `path`；`write_file`: `path` + `content`；`shell`: `command`；`http_post`: `url` + `body`（与下方 `http_post` 行同一建议）；schema 由 `@Tool` 注解生成 |
| `shell` 行为参数 | 超时值/配置键、非零退出码语义、跨平台调用，技术方案均未规定（§6.2 只说"带超时"） | `shell.timeout-seconds: 30`；非零退出 → `success=false` 且 stderr 进 `errorMessage`；命令经 POSIX `bash -c` 执行（部署目标为 Linux，Windows 开发机需 Git Bash 在 PATH） |
| 文件白名单匹配语义 | §6.7 只说"路径标准化后比对白名单，处理 `../`" | 规范化后按**绝对路径前缀**比对；相对路径以工作区根为基准；越界即拒。白名单为空 = 全拒（已在 §4.2 断言） |
| `http_post` 参数与默认 Content-Type | 技术方案只列工具名 | 参数 `url` + `body`；默认 `Content-Type: application/json` |
| webhook 发送的 body 格式 | §6.8 只说"把 `content` 包成对方 webhook 约定的 JSON 格式"，未定具体形态；核心阶段只有一个通用适配器，发不出多种格式 | 建议 `{"content": "<文本>"}`（通用档覆盖接受该形态的渠道）；其余渠道的格式差异归扩展阶段按 `channelType` 加专用 Adapter（§5 已列，接口不变），取舍依据 §6.8 + §5 范围边界 |
| `notify` 的 `channel` 缺省语义 | §6.8 写 `channel: String = 默认渠道`，但"默认渠道"无定义（渠道由 SQLite 注册表管理） | 缺省即**失败**并返回可用渠道名列表（不猜）；如需"默认渠道"概念，需先定义标记方式 |
| `mcp_servers.yaml` 文件结构 | §6.4 只定字段（name/transport/command/env），未定顶层形态与 `command` 拆参方式 | 顶层 `servers:` 列表；`command` 为空格分隔的完整命令行，首 token 为可执行文件（不引入未定的 `args` 键） |
| 工具重名注册策略 | §6.6 未规定（内置 / `@Tool` Bean / MCP 三方可能重名） | 后注册者**不覆盖**、保留先注册 + WARN（内置优先，避免 MCP 静默顶掉内置工具） |
| `identity` 字段消费点 | §8.2 要求派生；正文与 SOUL.md 已覆盖人格，`identity.prompt` 是否额外注入未定义 | 本模块只派生登记、**不额外注入**（避免与正文/SOUL.md 双重人格提示）；消费点留扩展阶段裁决 |
| Shell 资源限制 | 需求文档 §5.6 写"执行超时和资源占用限制"，技术方案 §6.7 只给白名单机制 | 核心阶段只做**超时**；CPU/内存限额随容器沙箱（扩展阶段）一起做 |
| Demo 命名统一 | CLAUDE.md/AiProgrammingGuide §4.4"零代码 PR digest" vs 技术方案 §12.3"每日 GitHub 日报" vs 需求 §13 两个 Demo | 本模块不裁决命名；建议文档收尾时以技术方案 §12 的三个每日 Demo 为准统一，并同步 CLAUDE.md 五大能力表 |
