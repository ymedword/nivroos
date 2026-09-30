# Research: US-4 Tool 体系（Phase 0）

**输入**：颗粒度文档 `docs/us/us4-tool.md` §2（设计要点与约束）、§3（交付物清单）；
技术方案 §6（Tool 体系）、§8.2（Profile）、§8.3（ContextLoader / Skill 渐进披露）、
§11.1（Skill 绑定）、§12.3（脚本信任边界）；宪法 v2.0.0。

**H3 核实记录（2026-09-30，本机 JDK 21 temurin + `~/.m2` 本地仓库 jar 实测）**：
本文件所有涉及第三方 API 的结论均来自 `javap` / class 常量池实测，非文档推测；
核实清单汇总在 §12。**无遗留 NEEDS CLARIFICATION**。

---

## 1. 注册机制：`scanAnnotated` 与 `@Tool` 扫描路径

**Decision**：`ToolRegistry.scanAnnotated(Object... beanCandidates)` 内部用
`MethodToolCallbackProvider.builder().toolObjects(beanCandidates).getToolCallbacks()`
拿到全部回调，逐个包成 `AnnotatedToolAdapter`（`implements NivroTool`）并 `register`。
工具名取 `ToolCallback.getToolDefinition().name()`（即 `@Tool(name = ...)` 显式声明，
**不依赖 Java 方法名**）；schema 取 `.inputSchema()`（JSON Schema 字符串，直接塞进
`JsonSchema` 值包装）。没有 `@Tool` 方法的 bean 静默跳过（返回空增量）。

**Rationale**：颗粒度文档 §2.3 / §3.1 已定字面量（`scanAnnotated(Object...)`、
`getToolDefinition()`、`AnnotatedToolAdapter`）；`MethodToolCallbackProvider` 是
Spring AI 1.1.2 里唯一做"`@Tool` 方法发现 + 参数绑定 + schema 生成"的现成组件，
自己写等于重实现一套参数绑定（宪法原则二只禁"自动执行"，不禁 schema 生成）。

**Alternatives considered**：

- 自己反射扫 `@Tool` 方法并逐个 `MethodToolCallback.builder()` 构造：
  放弃（要自己处理参数绑定、`ToolContext` 注入、默认值语义，纯属重造轮子）。
- 直接用 `ToolCallbacks` 类：**1.1.2 不存在该类**（实测确认，见 §12）。

## 2. 关键决策：`AnnotatedToolAdapter` 的返回值与失败语义

**Decision**：`@Tool` 方法的返回类型是 **`ToolResult`**（core 既有 record），
`AnnotatedToolAdapter.execute(JsonNode)` 的实现是：

```java
String json = callback.call(input.toString());   // 默认转换器 → JsonParser.toJson(ToolResult)
return MAPPER.readValue(json, ToolResult.class); // Jackson 记录反序列化，字段无损
```

失败语义分两类，与手写工具**完全同构**：

1. **业务性失败**（参数缺失/非法等）：方法**返回** `ToolResult(false, null, 文案, false)`
   —— 与 US-3 `MemoryTools` 现行为逐字一致；
2. **异常性失败**（沙箱拒绝、IO 失败）：方法抛异常 → `MethodToolCallback` 包成
   `ToolExecutionException` → 适配器**解包后抛原异常**（`RuntimeException` 原样重抛，
   非 Runtime 用 `RuntimeException(cause.getMessage(), cause)` 包装）→ 由既有
   `ToolExecutor` 落失败审计 + WARN 日志（宪法原则六、可观测性双轨）。

**Rationale**：

- **为什么不让方法直接返回 `String`**：实测 `DefaultToolCallResultConverter` 走
  `JsonParser.toJson(Object)`（Jackson `writeValueAsString`），对 `String` 会**加引号**；
  且"String 返回 + 抛异常表失败"会改掉 US-3 已交付的失败语义（参数非法返回失败结果
  而非异常），属行为契约漂移。返回 `ToolResult` 则 **US-2/US-3 既有断言体原样成立**，
  只需换获取路径（改从 `ToolRegistry` 取），是改造点里 churn 最小的方案。
- **为什么适配器要解包 `ToolExecutionException`**：`ToolExecutor` 用 `e.getMessage()`
  落审计并回填对话上下文（`ToolExecutor.java:82-83`）；不解包会把
  `ToolExecutionException` 的 cause 描述（`java.lang.IllegalStateException: xxx`）
  写进审计与模型上下文，而沙箱拒绝的**失败原因文案是契约可见的**（spec FR-006）。
  解包保证 `SandboxViolationException.getMessage()` 逐字到达审计与模型。
- 往返成本：一次 JSON 编解码，工具调用本身含 IO/子进程/HTTP，可忽略。

**Alternatives considered**：

- 自定义 `resultConverter` 直传：`MethodToolCallbackProvider.Builder` **只暴露
  `toolObjects(...)`**（实测），拿不到回调的单点定制；要走这条路必须放弃 Provider
  自己构造回调（见 §1 备选，已弃）。
- 适配器内用 `ThreadLocal` 捕获返回值：脏，且与虚拟线程语义纠缠，弃。
- 让工具方法返回 `String`（成功）并抛异常（失败）：见上，行为漂移 + 引号问题，弃。

## 3. `@Tool` 参数名依赖 `-parameters`（已实测确认开启）

**Decision**：`@ToolParam` **没有 `name` 属性**（只有 `required` / `description`），
schema 里的属性名来自 Java 参数名 ⇒ 编译期必须保留参数名。

**事实**：根 POM 的 parent 是 `spring-boot-starter-parent:3.5.16`，其
`maven-compiler-plugin` 配置 `<parameters>true</parameters>`（本地仓库 parent POM
第 88-92 行实测）⇒ 本项目**已满足**，无需改构建。实现期用 `ToolContractTest`
断言九个工具的参数名逐字（`path` / `content` / `command` / `url` / `body` / `query` / `scope`）
作为回归钉。

**Rationale**：参数名是已定字面量（US-3 契约），一旦 `-parameters` 漂移，schema 会
静默变成 `arg0`，模型调用全线失效——必须有测试钉住。

## 4. 白名单三档的匹配语义

**Decision**：

| 档 | 匹配算法 |
| --- | --- |
| FILE_READ / FILE_WRITE | 目标路径 `Path.of(target).toAbsolutePath().normalize()` 后，与每个白名单项（同样 normalize 后的**绝对**路径）做**前缀比对**（相等或 `startsWith(root + File.separator)`）；相对路径以进程工作目录为基准展开。**不解析软连接**（`toRealPath` 会引入 IO 与竞态，且会改变"白名单内路径被链接出去"的语义） |
| SHELL_COMMAND | `command.trim()` 后按空白拆出**首个 token**，与白名单项做**逐字**比对（大小写敏感，Linux 语义） |
| HTTP_REQUEST | US-2 已交付：解析 host + 通配符匹配（`*.example.com` 命中裸域名与子域名，点号边界由 `matches()` 保证） |

任一档**列表为空 = 全拒**（不是"不校验"），三档一视同仁（spec FR-005）。

**Rationale**：技术方案 §6.7「路径标准化后比对白名单，处理 `../`」+ §11.3 既有
`normalize() + startsWith(root)` 先例（InitCommand 已用同款判定）；"空 = 全拒" 是
spec FR-005 与颗粒度文档 §2.2-11 的显式要求（安全默认拒绝）。

**Alternatives considered**：`toRealPath()` 解析真实路径后比对（能防软连接逃逸，
但引入 IO 失败分支且与"劝阻级防线"定位不符，核心阶段不做——§2.1 已声明白名单
不防蓄意绕过）。

## 5. Skill L1 路径语义与越界校验（含一条软门禁报告项）

**Decision**：

- L1 注入的路径 = **Agent 本地绝对路径**（`.nivroos/agents/<agent>/skills/<name>/SKILL.md`，
  即软连接路径本身），**不是**解析后的真实目标路径。依据技术方案 §8.3 原文
  「只注入 Skill frontmatter 的 name/description 与 **Agent 本地绝对读取路径**」；
  该路径落在 §3.3 示例白名单（`.nivroos/agents`）之内，L2 `read_file` 零额外配置可用。
- 软连接**真实目标必须位于公共技能库**（`.nivroos/skills/`）之内，越界绑定 WARN
  跳过、不注入、不阻断（技术方案 §8.3「验证真实目标位于公共 Skill 根」）。
- 每轮重扫、不缓存（与 `AGENT.md` 正文同语义）；断链 / 缺 `name`/`description` /
  正文为空 → WARN 跳过。frontmatter 复用小节：`ContextLoader.stripFrontmatter` +
  core 已有 SnakeYAML。

**Rationale**：两条都直接落在技术方案原文上；路径取软连接路径同时解决了
"白名单只有 `.nivroos/agents` 时 L2 会被拒"的自洽问题（见 spec Clarifications）。

**软门禁报告项（登记给用户）**：越界校验是**技术方案 §8.3 明写、而颗粒度文档
§3.1/§3.2 未列出**的项。按 module-dev「文档与技术方案冲突以技术方案为准 + 停下报告」
处理：本 plan 已把它落成 spec FR-028 与 `ContextLoaderTest` 用例，实施时不再二次打断。

## 6. MCP 接入（stdio）的实现细节

**Decision**：

```java
McpClient.sync(new StdioClientTransport(
        ServerParameters.builder(commandToken0).args(restTokens).env(resolvedEnv).build(),
        McpJsonDefaults.getMapper()))
    .requestTimeout(Duration.ofSeconds(30))     // 待决事项默认建议值，硬编码常量，不新增配置键
    .build();
client.initialize();
// tools/list 翻页取尽：cursor 为 null 为止
ListToolsResult page = client.listTools();
while (page.nextCursor() != null) { page = client.listTools(page.nextCursor()); }
```

- 工具注册：`registry.register(new McpToolAdapter(serverName, tool, client))`；
- 单个 server 任一环节失败 → WARN + `continue`（不阻断启动）；
- `close()` 逐个 `client.closeGracefully()`，失败降级 `close()` + WARN；
- `command` 按空白拆首个 token 为可执行文件、其余为 `args`（§3.3 例：
  `npx -y @modelcontextprotocol/server-github`）；`env` 占位符在 `McpServerConfig`
  解析期展开，明文被拒。
- **Jackson 2（项目侧 `JsonNode`）↔ Jackson 3（SDK 侧 `tools.jackson.core`）的桥接
  只允许出现在 `McpToolAdapter` 一处**：入参用 Jackson 2 的 `ObjectMapper.convertValue`
  转 `Map<String,Object>`，出参把 `TextContent.text()` 拼成 `content`。

**Rationale**：颗粒度文档 §2.2-12 / §3.1 已定（`McpSyncClient`、显式超时、只有一处桥接）；
`McpSyncClient` 是同步门面（内部另有异步实现，但**我们的源码不出现 Reactor 类型**，
原则七与不变量 #5 的判定标准是源码 grep，见 quickstart §1）。

**Alternatives considered**：`McpAsyncClient` + `block()`（引 Reactor，违反原则七）；
`ProcessBuilder` 自实现 JSON-RPC（重造协议栈，§6.4 明确用 MCP Java SDK）。

## 7. 通知链路：注册表 → 解析 → 校验 → 发送

**Decision**：

- 通知渠道持久化在 SQLite `notify_channels`（技术方案 §6.8；§9.2 五张表清单未列该表，
  属未同步表述——颗粒度文档差异裁决注 2）。
- 接口在 core（`NotifyChannel` record + `NotifyChannelStore.findByName`），JPA 实现在
  `nivroos-storage`（`NotifyChannel` 实体 + `JpaNotifyChannelStore` + `NotifyChannelStoreConfiguration`）
  ——**依赖倒置，同 `ToolInvocationStore` 先例**（用户 2026-09-30 裁决）。
- `NotifyTools.execute`：无 `channel` / 渠道不存在 / 渠道类型不受支持 → **失败结果**
  且错误信息回填**可用渠道名清单**（`NotifyChannelStore.findByName` 之外需要一个"列全部"
  的能力 → 由 `NotifyChannelStore` 的 `findByName` 之外**不新增方法**：
  实现类内部提供 `all()` 供 `NotifyTools` 构造时注入？**否**——见下方"实现约束"）。
- 发送前 `WebhookNotifyAdapter` 先 `sandbox.enforce(HTTP_REQUEST, url)`，再经
  `HttpClient` POST `{"content": "<文本>"}`，非 2xx 抛异常不吞。

**实现约束（本 plan 定的最小解，避免软门禁）**：错误信息里的"可用渠道名清单"由
`NotifyChannelStore` 的**既有查询能力**支撑。为避免新增公开方法，`NotifyChannelStore`
接口按颗粒度文档 §3.1 只声明 `findByName`，`JpaNotifyChannelStore` 实现类**额外**提供
`List<String> channelNames()`（实现类自有方法，非接口方法，不进 core 契约）；
`NotifyTools` 的构造签名是 `NotifyTools(Sandbox, NotifyChannelStore, NotifyChannelAdapter)`
（已定字面量，不加参数）⇒ 清单能力只能来自 `NotifyChannelStore` 的注入实例——
因此 `NotifyTools` 内部按 `store instanceof JpaNotifyChannelStore` 判定？**不可接受**
（tool 模块看不到 storage 类）。

⇒ **最终决策**：把"列全部渠道名"作为 `NotifyChannelStore` 的**第二个接口方法**
`List<String> channelNames()`。它是 core 里的**新增公开方法**，但属于
"交付物清单点名类型的成员"（`NotifyChannelStore` 接口本身在 §3.1），且没有它
FR-016 的"回填可用渠道名清单"无法实现。**登记为软门禁报告项**（用户确认后实施；
若用户否决，退回"错误信息只给渠道名本身、不给清单"的降级形态）。

**Rationale**：`notify` 的失败信息必须"明确且可自纠"（spec US-2 场景 3/4）；
测试（`NotifyToolsTest`）断言的就是可用渠道名回填，缺该能力则断言写不出来。

## 8. 配置读取与默认值

**Decision**：

| 键 | 形态 | 来源 |
| --- | --- | --- |
| `file.allowed_paths` | list，默认 `[.nivroos/agents]`（照 §3.3 示例） | 技术方案 §6.7 |
| `shell.allowed_commands` | list，默认 `[python, git]`（照 §3.3 示例） | 技术方案 §6.7 |
| `http.allowed_domains` | list，US-2 已交付，notify 共享同一份 | 技术方案 §6.7 |
| `shell.timeout-seconds` | int，默认 30 | 颗粒度文档「待决事项」默认建议值 |
| MCP 调用超时 | 常量 30s，**不新增配置键** | 「待决事项」明确"如需按 server 可配置再加配置键（届时报软门禁）" |

列表一律经 `Binder.get(env).bind(key, Bindable.listOf(String.class))` 读取——
`Environment.getProperty(key, List.class)` 返回 null（2026-08-31 实测踩坑，CLAUDE.md 陷阱表）。

**Rationale**：三组键名是已定字面量，不得改名；默认值取自颗粒度文档 §3.3 配置样例与
「待决事项」默认建议（spec Assumptions 已逐条登记）。

## 9. 装配：`ToolConfiguration` 与 `ProfileConfiguration`

**Decision**：

- `ToolConfiguration`（`nivroos-tool`，`@Configuration`）：Binder 读配置 → `WhitelistSandbox`
  三参构造 → 内置 Tool Bean（`FileTools` / `ShellTools` / `HttpTools` / `NotifyTools`）
  → `ToolRegistry` Bean（构造期收集内置 Bean + 遍历容器单例 Bean 送入 `scanAnnotated`，
  以覆盖 `nivroos-memory` 的 `MemoryTools` 与 boot 侧示例 `@Tool` Bean，
  **tool 模块对 memory 无编译期依赖**）→ `McpClientService` Bean（`initMethod = "start"`、
  `destroyMethod = "close"`）。
- `ProfileConfiguration`（`nivroos-boot`，`@Configuration`）：`AgentLoader.scan()` →
  跨模块校验（provider 在 `ProviderService.providerNames()` / `tools` 在 `ToolRegistry` /
  `bootstrap` 文件存在 / `mcp_servers` 已配置）→ `ProfileRegistry` Bean；单个 Agent 失败
  WARN 不阻断（技术方案 §8.2 + 差异裁决注 6：校验落点在装配层，因为 `AgentLoader` 在
  core、看不到 ToolRegistry）。

**Rationale**：§2.3 装配链路图 + 差异裁决注 6 已定；Bean 生命周期用
`initMethod`/`destroyMethod` 是 Spring 常规手法，不新增类型。

## 10. 依赖增补与依赖方向（无新增第三方坐标）

| 模块 | 增补 | 说明 |
| --- | --- | --- |
| `nivroos-memory` | `org.springframework.ai:spring-ai-model`（compile） | `@Tool` / `@ToolParam` 注解；**同一坐标已在 `nivroos-tool/pom.xml` 声明**（BOM 管理），非新第三方坐标 |
| `nivroos-tool` | `org.springframework.boot:spring-boot`（compile） | `@Configuration` / `Environment` / `Binder`；Boot BOM 管理，非 starter、不带自动装配 |
| 其余 | 无 | MCP SDK 坐标已在 `nivroos-tool/pom.xml` 声明（1.1.3） |

依赖方向恒为 `tool → core`、`memory → core`、`storage → core`、`boot → 全部`；
**core 不出现 `io.modelcontextprotocol` / `org.springframework.ai`**（quickstart 有 grep 门禁）。

## 11. 测试策略与 mock 手法

| 被测面 | 手法 |
| --- | --- |
| 文件工具 / 白名单 | `@TempDir` 真实文件系统；越界用例断言**目标文件不存在** |
| Shell | 白名单 `echo` 无副作用命令；并发用例用 `Executors.newVirtualThreadPerTaskExecutor`（既证并发隔离，也覆盖虚拟线程复用下的输出隔离） |
| HTTP / webhook | mock `java.net.http.HttpClient`（沿用 `HttpToolsTest` 写法）；顺序断言用 `InOrder` |
| MCP | 包内可见测试构造注入 `Function<McpServerConfig, McpClientTransport>` mock 传输实现 + mock `McpSyncClient`；**不启动真实子进程** |
| 配置 | `MockEnvironment`/`ApplicationContextRunner` 绑三组列表；空白名单全拒 |
| JPA | 沿用 `nivroos-storage` 既有测试配置（`StorageTestConfiguration`） |
| 契约 | 参数化遍历 `ToolRegistry.all()`：名称/描述/schema 非空 + 参数名逐字 |

## 12. H3 核实清单（2026-09-30 本地 jar 实测）

| 结论 | 出处 |
| --- | --- |
| `@Tool` = `name()` / `description()` / `returnDirect()` / `resultConverter()` | `spring-ai-model-1.1.2.jar` javap |
| `@ToolParam` = `required()` / `description()`（**无 `name`**） | 同上 |
| `ToolDefinition` = `name()` / `description()` / `inputSchema()` | 同上 |
| `MethodToolCallback.getToolDefinition()` / `call(String)` / `builder()`（Builder 有 `toolCallResultConverter`） | 同上 |
| `MethodToolCallbackProvider.builder().toolObjects(Object...)` → `getToolCallbacks()` | 同上 |
| `DefaultToolCallResultConverter.convert` → `JsonParser.toJson(Object)`（Jackson 序列化，String 会带引号） | class 常量池实测 |
| `MethodToolCallback` 捕获异常后抛 `ToolExecutionException(ToolDefinition, Throwable)` | class 常量池实测 |
| `ToolCallbacks` 类**不存在**于 1.1.2 | `unzip -l` 实测 |
| Boot parent 3.5.16 的 `maven-compiler-plugin` 带 `<parameters>true</parameters>` | parent POM 实测 |
| `McpSyncClient`：`initialize()` / `listTools()` / `listTools(String cursor)` / `callTool(CallToolRequest)` / `close()` / `closeGracefully()` | `mcp-core-1.1.3.jar` javap |
| `McpClient.sync(McpClientTransport)` → `SyncSpec.requestTimeout(Duration)` → `build()` | 同上 |
| `StdioClientTransport(ServerParameters, McpJsonMapper)`；`ServerParameters.builder(String).args(...).env(...)` | 同上 |
| `McpSchema.Tool` record：`name()` / `description()` / `inputSchema()`；`ListToolsResult.tools()` / `nextCursor()`；`CallToolRequest(String, Map)`；`CallToolResult.builder().content(...).isError(Boolean)`；`TextContent(String)` | 同上 |
