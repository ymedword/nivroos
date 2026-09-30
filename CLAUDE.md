# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## NivroOS — Claude Code 项目指南

NivroOS 是用 Java 实现的面向企业场景的 **Agent OS**。装在企业自己的 K8s 或服务器上，作为统一底座运行多个业务 Agent，共享渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业自己的基础设施，不锁任何云生态。定位严监管企业（银行、政府、能源），差异化是私有部署、可审计、Java 技术栈对齐——业界参照 OpenClaw（Node.js）、Hermes Agent（Python），Java 生态在 Agent OS 层是空白。

**交付分两段**：核心阶段 = 运行时内核（五大核心能力，当前要做的事）；扩展阶段 = 企业级治理层（多租户、SSO、完整审计、Tool Policy），不在当前范围。

> README 标注本项目 inspired by OryxOS（兄弟项目），但两者文档已各自演进，本文件内容一律以 **nivroos 自己的 4 份文档**为准。
>
> **文档体系（各司其职）**：4 份文档按职责划分——调研（Why）、需求（What）、技术方案（How）、编程指南（How 落地），每份只管自己的视角，**不要"修正"它们**；万一有交叉，细节不一致时以最新技术方案为准。
>
> - [docs/IndustryResearch.md](docs/IndustryResearch.md) — 业界调研（Why）
> - [docs/DemandAnalysis.md](docs/DemandAnalysis.md) — 需求文档（What）
> - [docs/TechnicalSolution.md](docs/TechnicalSolution.md) — 技术方案（How，**最权威**）
> - [docs/AiProgrammingGuide.md](docs/AiProgrammingGuide.md) — AI 编程实施指南（Spec-Kit 拆解）

---

## 技术栈

| 组件 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 21（必须，virtual thread 处理并发） |
| 框架 | Spring Boot 3.x 单体应用 |
| LLM 调用 | Spring AI + Spring AI Alibaba（仅用协议转换 + `@Tool` schema 生成） |
| HTTP 服务 | Spring MVC + Java 21 Virtual Thread |
| 命令行 | Picocli |
| YAML 解析 | SnakeYAML |
| 持久化 | SQLite + Spring Data JPA |
| MCP Client | MCP Java SDK（社区项目，可能需要部分自实现） |
| 日志 | Logback + SLF4J（结构化日志） |
| 指标 | Micrometer + Prometheus（扩展阶段） |
| 构建 | Maven 多模块（9 个），`mvn clean package` 产出 fat JAR，`java -jar` 启动 |
| 工程质量门禁 | Spotless 3.10.0（google-java-format 1.36.1）+ Checkstyle 3.6.0 + SpotBugs 4.10.4.0（挂 findsecbugs 1.14.0）+ JaCoCo 0.8.15 + OWASP dependency-check 13.0.0（`-Psecurity`）+ logstash-logback-encoder 9.0（prod JSON 日志；版本 2026-08-27/28 实测锁定） |

---

## 模块结构（9 个，固定）

```text
nivroos/
├── nivroos-core          # 核心抽象与引擎：NivroTool 接口、Session、Profile、ContextLoader、
│                         #   AgentLoader、ReActLoop、PromptBuilder、ToolExecutor、AgentService、
│                         #   AgentScheduler、MetricsRegistry（指标接口预留，扩展阶段换 Micrometer 实现）
├── nivroos-provider      # 能力一：ProviderService、Function Calling 适配、Provider 配置
│                         #   （provider name → ChatModel 显式映射）
├── nivroos-memory        # 能力三：MemoryService 统一门面、LongTermMemory、MemoryTools
├── nivroos-tool          # 能力四（三合一）：内置 Tool（File/Shell/Http/Notify）、MCP Client、
│                         #   ToolRegistry、Sandbox 接口 + WhitelistSandbox
├── nivroos-channel-cli   # CLI Channel：CliChannel、nivroos chat 命令
├── nivroos-web           # 能力五：WebServer、6 个 ApiController、ApiResponse/ErrorCode 信封、
│                         #   GlobalExceptionHandler、OpenAPI
├── nivroos-storage       # 持久化：SQLite、SessionRepository、ToolInvocationRepository、
│                         #   LlmCallRepository
├── nivroos-cli           # 命令行入口：Picocli 主入口、12 个子命令、ConfigLoader
└── nivroos-boot          # Spring Boot 启动模块：主类、自动配置、依赖聚合
```

模块之间通过接口解耦。**模块结构固定为 9 个，不拆不并**——Spec-Kit 生成的 plan 必须跟技术方案第 10 章一致，Tool 相关三合一为一个 `nivroos-tool` 模块，不拆 builtin/skill/mcp。

---

## 不可违背的原则（Constitution）

以下原则来自 `docs/AiProgrammingGuide.md` 第 3.2 节和 `docs/TechnicalSolution.md` 第 1.1 节，所有代码必须遵守。

### 原则一：自实现 ReAct Loop

`ReActLoop` 必须自己实现（核心循环约数十行 Java），**不得**使用 Spring AI 的 Agent 抽象。理由：完全可控，保留未来定制循环行为的空间。

### 原则二：Spring AI 只用两件事 ⚠️（最容易被写错的一条）

Spring AI 在 NivroOS 里只做：

1. LLM Provider 抽象和向各家 LLM 的协议转换
2. `@Tool` 注解的 JSON Schema 生成

**必须禁用** Spring AI 的自动 tool 执行。Tool 的调度和执行完全由 `ReActLoop` + `ToolExecutor` 控制。违反此原则会导致 **tool 被调两次**。

```java
// 错误：不得用 Spring AI 自动执行 tool
chatClient.prompt(prompt).tools(tools).call().content();

// 正确：只用 Spring AI 做 LLM 调用，tool call 自己检查、自己执行
ChatResponse response = chatModel.call(new Prompt(messages, options));
// 然后检查 response 里的 tool call，由 ToolExecutor 执行并回填
```

### 原则三：Provider 必须显式映射

多 Provider 并存时，**不得**靠扫描容器里所有 `ChatModel` Bean 区分 Provider（Bean 类型相同、Bean name 未必等于 provider name）。必须维护 `provider name → ChatModel` 的显式映射表：

```java
// 正确：显式映射
Map<String, ChatModel> providerMap = Map.of(
    "deepseek", deepseekChatModel,
    "qwen",     qwenChatModel,
    "kimi",     kimiChatModel
);
```

### 原则四：一个目录 = 一个 Agent；Skill 软连接绑定 + 渐进披露

**一个目录 = 一个 Agent**：`.nivroos/agents/<name>/` 里 `AGENT.md` = frontmatter（运行配置）+ 正文（任务指令），外加可选 `skills/`（公共 Skill 软连接）、`scripts/`、`REFERENCE.md`。`AgentLoader.deriveProfile(agentDir)` 把 frontmatter 派生成底座认识的 `Profile`。

- **`AGENT.md` 不是 Tool**：加载归 `ContextLoader`（正文注入 system prompt），Skill 不进 `ToolRegistry`
- Skill 绑定由 Agent 本地 `skills/` 相对软连接表达，每轮只注入 `name + description + 本地绝对路径`（L1）；模型命中后 `read_file` 读 `SKILL.md` 正文（L2）；附属参考/脚本按需再取（L3）。不预载正文、不新增 `use_skill`

### 原则五：审计表 Day One 写入

`tool_invocations` 和 `llm_calls` 两张审计表**核心阶段就必须写入**（不要求查询接口，但写入不能省）。不得以"日志够了"为由跳过落库，可审计是 NivroOS 的差异化能力。

### 原则六：Sandbox 接口先行，核心阶段唯一实现 WhitelistSandbox

`SecurityManager` 在 JDK 17 起废弃、JDK 21 已不可用。`Sandbox` 接口只表达"在受控环境里执行一个动作"（`Sandbox.enforce(SandboxAction)`，ActionType 取 `FILE_READ | FILE_WRITE | SHELL_COMMAND | HTTP_REQUEST`），不携带任何实现细节。核心阶段唯一实现 `WhitelistSandbox`（应用层白名单，配置在 `application.yaml`）：

- 文件操作：路径白名单（`file.allowed_paths`，需处理 `../` 路径穿越）
- Shell：命令首 token 白名单（`shell.allowed_commands`）
- HTTP：域名通配符白名单（`http.allowed_domains`）

校验失败抛 `SandboxViolationException`，走 `ToolExecutor` 既有失败审计路径（`success=false`、`error_message`）。`NotifyTools` 的 webhook 发送同样过 HTTP 域名白名单。扩展阶段按信号驱动升级（容器 → microVM），接口不变只新增实现类。

### 原则七：同步执行模型

核心阶段全程同步阻塞，配合 Java 21 Virtual Thread 处理并发。**不引入** Reactor / WebFlux / CompletableFuture 等异步模型（SSE 流式响应放扩展阶段）。

### 原则八：Tool 模块三合一

内置 Tool、MCP Client、`ToolRegistry`、Sandbox 合并在一个 `nivroos-tool` 模块，**不拆成多个模块**。

### 原则九：五大核心能力优先

核心阶段交付运行时内核（能力上对齐业界开源 Agent OS 基础层），企业级治理层（多租户、SSO、完整审计、Tool Policy）放扩展阶段。范围卡紧，完不成的功能挪到扩展阶段而不是砍验收。

### 原则十：每个 user story 完成后有可演示 Demo

优先级是跑通而非完美，每周末有可演示成果。

---

## 工作区结构（运行时）

NivroOS 启动后在当前目录创建 `.nivroos/` 工作区（`nivroos init`，幂等，已存在的文件不覆盖）：

```text
.nivroos/
├── agents/             # 每个子目录 = 一个 Agent（AGENT.md + skills/软连接 + scripts/ REFERENCE.md）
├── skills/             # 公共 Skill 实体库（每个子目录一个 SKILL.md + 可选附属资源）
├── memory/
│   └── MEMORY.md       # 长期记忆（## 核心记忆 / ## 归档记忆 两个分区，Agent 经 save_memory 写入）
├── mcp_servers.yaml    # MCP server 配置
├── sessions/           # Session 数据（已迁入 SQLite，此目录备用）
├── logs/               # 结构化日志
├── AGENTS.md           # Bootstrap：项目级 agent 行为说明
├── SOUL.md             # Bootstrap：agent 人格定义
├── USER.md             # Bootstrap：用户偏好（NivroOS 只读不写）
└── nivroos.db          # SQLite 数据库
```

**MEMORY.md vs USER.md 区别**：

| 文件 | 来源 | 读写方 | 用途 |
| --- | --- | --- | --- |
| `USER.md` | 用户手写 | NivroOS 只读不写 | 用户的"初始设定"（Bootstrap 文件） |
| `MEMORY.md` | Agent 通过 `save_memory` 写入 | NivroOS 读写 | Agent 的"成长记录"（长期记忆） |

两者都进 system prompt。MEMORY.md 内部按 `## 核心记忆` / `## 归档记忆` 两个一级分区组织：核心区永远完整不截断，截断和关键词检索只作用在归档区。

---

## 核心数据模型

### AGENT.md（`.nivroos/agents/<name>/AGENT.md`）

frontmatter（运行配置，`AgentLoader.deriveProfile` 派生成 `Profile`）+ 正文（任务指令，注入 system prompt）：

```markdown
---
name: ops-agent
description: 运维助手
identity:
  agent_name: 运维小欧
  prompt: 你是一个专业的运维助手...
provider:
  name: deepseek            # 对应 ProviderService 里的显式映射 key
  model: deepseek-chat
  temperature: 0.7          # 可选
tools:
  - read_file
  - shell
  - http_get
  - save_memory
  - recall_memory
mcp_servers:
  - github-mcp
channels:
  - name: cli
    config: {}
bootstrap:
  - AGENTS.md
  - SOUL.md
  - USER.md
settings:
  max_iterations: 10        # 最大 ReAct 迭代次数
  max_history_turns: 20     # 最大对话历史轮数
schedules:                  # 定时任务（可选，AgentScheduler 钟推触发）
  - cron: "0 8 * * *"
    message: "查一下今天的天气"
---

你是一个专业的运维助手。被触发时……（Agent 的任务指令正文，注入 system prompt）
```

- 敏感配置（API key 等）用 `${ENV_VAR}` 占位，从环境变量解析，**不明文写死**
- `notify_channels` **不属于** frontmatter——通知渠道由 SQLite 全局注册表（`notify_channels` 表）管理，Agent 只在正文中按名称引用

### SQLite 核心表（五张）

#### sessions

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `session_id` | VARCHAR PK | channel+user+profile 联合生成 |
| `profile_name` | VARCHAR | 关联 Profile |
| `channel` | VARCHAR | 接入渠道 |
| `user_id` | VARCHAR | 用户标识 |
| `messages_json` | TEXT | JSON 序列化的对话历史 |
| `status` | VARCHAR | `active` / `archived` |
| `created_at` | TIMESTAMP | 创建时间 |
| `last_active_at` | TIMESTAMP | 最后活跃时间 |
| `archived_at` | TIMESTAMP | 归档时间（可空） |

#### tool_invocations（审计，day one 写入）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 主键 |
| `session_id` | VARCHAR | 关联 Session |
| `tool_name` | VARCHAR | Tool 名称 |
| `input_json` | TEXT | 调用参数（JSON） |
| `result_json` | TEXT | 执行结果（JSON） |
| `success` | BOOLEAN | 是否成功 |
| `error_message` | TEXT | 错误信息（可空） |
| `duration_ms` | BIGINT | 执行耗时 |
| `created_at` | TIMESTAMP | 调用时间 |

#### llm_calls（审计，day one 写入）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 主键 |
| `session_id` | VARCHAR | 关联 Session |
| `provider` | VARCHAR | Provider 名称 |
| `model` | VARCHAR | 模型名 |
| `prompt_tokens` | INT | 输入 token 数 |
| `completion_tokens` | INT | 输出 token 数 |
| `total_tokens` | INT | 总 token 数 |
| `duration_ms` | BIGINT | 调用耗时 |
| `created_at` | TIMESTAMP | 调用时间 |

#### scheduled_tasks / task_executions

定时任务状态与执行历史，归 `AgentScheduler`，见下文"定时任务"一节。

> **SQLite 迁移注意**：`hibernate.ddl-auto=update` 在 SQLite 上 `ALTER TABLE` 支持很弱。表结构变更时**不要**依赖 Hibernate 自动迁移，手动维护建表脚本或引入 Flyway/Liquibase。

---

## ReAct Loop 工作机制

```text
用户消息（人推：CLI / Web Service；钟推：AgentScheduler 定时触发）
  → AgentService.process(Session, message)（统一入口，Profile 放进 ProfileContext ThreadLocal）
  → 追加到 Session 对话历史
  → ReActLoop.run：PromptBuilder 组装 Prompt，四部分：
      [1] system prompt（AGENT.md 正文 + Bootstrap + Skill 元数据；末尾附当前日期时间）← ContextLoader
      [2] Memory 注入（会话历史 + 长期记忆 MEMORY.md）                                ← MemoryService
      [3] 对话历史（最近 max_history_turns 轮，默认 20）                             ← SessionManager
      [4] 可用 Tool 列表（Function Calling 格式）                                     ← ToolRegistry
  → ProviderService 调 LLM（写 llm_calls 表）
  → [无 Tool 调用] → 返回最终响应
  → [有 Tool 调用] → ToolExecutor 执行 Tool
      → Sandbox.enforce 白名单校验
      → 执行（内置 Tool 在进程内 / MCP Tool 经 McpToolAdapter 转发）
      → 写 tool_invocations 表
      → 结果作为 tool 消息追加到对话历史
  → 回到组装 Prompt 继续循环（最多 max_iterations 次，默认 10，可在 Profile 覆盖）
```

**核心阶段不做**：Tool 调用并行（一次响应多个 Tool 调用按顺序执行）、Agent 间任务委托、上下文总结压缩（超长简单截断早期对话）、流式响应。

---

## Tool 体系

### NivroTool 接口（所有 Tool 的统一抽象）

```java
interface NivroTool {
    String getName();
    String getDescription();
    JsonSchema getInputSchema();   // JSON Schema
    ToolResult execute(JsonNode input);
}
```

`ToolResult` 包含：成功标识、结果内容、错误信息、是否可重试。内置 Tool、`@Tool` 注解 Plugin Tool、MCP Tool 都包装成 `NivroTool` 注册到 `ToolRegistry`，ReAct 循环不感知 Tool 来源。

### 内置 Tool（核心阶段 9 个，技术方案 6.2 为准）

| Tool | 类 | 说明 |
| --- | --- | --- |
| `read_file` | `FileTools` | 读文件，路径白名单 |
| `write_file` | `FileTools` | 写文件，路径白名单 |
| `list_dir` | `FileTools` | 列目录，路径白名单 |
| `shell` | `ShellTools` | 执行 bash，命令白名单 + 超时 |
| `http_get` | `HttpTools` | GET 请求，域名白名单 |
| `http_post` | `HttpTools` | POST 请求，域名白名单 |
| `save_memory` | `MemoryTools` | 追加到 MEMORY.md（归 memory 模块） |
| `recall_memory` | `MemoryTools` | 关键词检索 MEMORY.md（归 memory 模块） |
| `notify` | `NotifyTools` | 推送到全局通知渠道注册表，核心阶段走 `WebhookNotifyAdapter` |

### Plugin Tool 三档

| 方式 | 门槛 | 推荐 | 实现 |
| --- | --- | --- | --- |
| 零代码 | 最低 | ⭐⭐⭐ 主推 | 写 Agent 目录（AGENT.md）+ 复用社区现成 MCP server，纯 markdown 上线 |
| 轻代码 | 中 | ⭐⭐ | 任意语言写 MCP server，配置在 `.nivroos/mcp_servers.yaml`（name/transport/command/env） |
| 重代码 | 高 | ⭐ | Java `@Tool` 注解 Spring Bean，进程内直接调用，性能最好 |

> 选择原则：能用方式一就不用方式二，能用方式二就不用方式三。

### Sandbox 与 NotifyTools

- `Sandbox` 接口 + `WhitelistSandbox` 见原则六；`FileTools`/`ShellTools`/`HttpTools`/`NotifyTools` 在 `execute` 开头调 `sandbox.enforce(...)`
- `notify` 的渠道解析：通知渠道（`name`/`type`/`url`/`description`）持久化在 SQLite `notify_channels` 表，Agent 在 `AGENT.md` 正文中用自然语言按名引用；`NotifyChannelAdapter` 接口 + `WebhookNotifyAdapter`（唯一实现，HTTP webhook 承接企业微信/飞书/钉钉群机器人场景）

---

## Web Service API

核心阶段 10 个端点，统一前缀 `/api/v1`，`nivroos serve` 启动（默认端口 8080）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/sessions` | 创建会话 |
| `POST` | `/sessions/{id}/messages` | 发消息（触发 ReAct Loop） |
| `GET` | `/sessions/{id}` | 查会话历史 |
| `DELETE` | `/sessions/{id}` | 归档会话 |
| `POST` | `/agents/{name}/invoke` | 无状态调用 Agent |
| `GET` | `/profiles` | 列所有 Profile |
| `GET` | `/memory` | 查长期记忆（MEMORY.md） |
| `GET` | `/tools` | 列可用 Tool |
| `GET` | `/health` | 健康检查 |
| `GET` | `/info` | 运行信息 + Provider 状态 |

**核心阶段不做**：认证（假设内网）、SSE 流式、WebSocket、RBAC、限流。限制：单条消息最大 32KB、Session 历史返回最多最近 100 条、Agent 调用最长 60 秒超时返回 504。错误统一走 `ApiResponse` 信封（`code`/`message`/`data`/`timestamp`）。

---

## 命令行工具（12 个，Picocli）

```bash
# 启动和状态
nivroos init                      # 初始化 .nivroos/ 工作区
nivroos status                    # 查看配置和运行状态
nivroos chat [--profile <name>]   # 交互式多轮对话（--message 可发单条后退出）
nivroos serve                     # 启动 HTTP API 服务
nivroos gateway                   # 守护进程模式（多 Channel）

# Profile 管理
nivroos profile list
nivroos profile create <name>     # 生成最小 AGENT.md 模板
nivroos profile show <name>
nivroos profile delete <name>

# 查询
nivroos provider list
nivroos tool list
nivroos session list
```

不需要 Spring 上下文的命令（`init`、`profile list`）直接走文件操作启动快；需要 LLM 调用的命令（`chat`、`serve`、`gateway`）启动 Spring 上下文。

---

## 配置加载规则

敏感配置（LLM API key、MCP server 凭证）通过环境变量注入或独立的本地配置文件加载，**不得**明文写在 Profile YAML 里：

```yaml
provider:
  name: deepseek
  api_key: ${DEEPSEEK_API_KEY}   # 从环境变量读取
```

`ConfigLoader` 加载时做必填项和格式校验，缺失或非法时给清晰报错，不静默失败。完整的加密存储、密钥轮转、KMS/Vault 对接放扩展阶段。

---

## 定时任务 AgentScheduler（第三触发源）

定时任务不是新核心能力，而是给 `AgentService` 加第三条触发路径：CLI / Web Service 是"人推"，`AgentScheduler` 是"钟推"——按 cron 到点自动生成消息，链路跟人推完全一样，`ReActLoop` 不感知消息从哪个入口来。

- 实现：Spring `ThreadPoolTaskScheduler` + `CronTrigger` **动态注册**（不用静态 `@Scheduled`），启动时扫描所有 Profile 的 `schedules` 字段逐个注册
- 并发控制：每个任务一把进程内 `ReentrantLock`（按 task id），上一次没跑完、下一次触发点到了就跳过，不排队不并行
- 失败处理：只记日志不崩调度器；失败的调用依然走完整 `llm_calls`/`tool_invocations` 审计路径
- 会话身份：session_id 沿用既有公式，channel 和 user 都固定为 `scheduler`，同一 Profile 历次触发复用同一 Session
- 状态持久化：`scheduled_tasks`（任务登记与运行状态）、`task_executions`（每次执行历史）两张表；`ScheduledTaskStore` 接口在 `nivroos-core`、`JpaScheduledTaskStore` 实现在 `nivroos-storage`（依赖倒置）；定义源仍是 Profile 的 `schedules`，两张表只存状态与历史，重启时从文件重新注册
- 管理端点（`ScheduleApiController`，前缀 `/api/v1/schedules`）：`GET /schedules`、`GET /schedules/{id}/executions`、`POST /schedules/{id}/run`（立即执行）、`PUT /schedules/{id}`（启用/停用）

---

## 五大核心能力与验收 Demo

| 能力 | 核心组件 | 验收 Demo |
| --- | --- | --- |
| 一：对接 LLM | `ProviderService`，显式 provider 映射 | —（与能力二同跑 Demo 一） |
| 二：ReAct 循环 | `ReActLoop`、`PromptBuilder`、`ToolExecutor` | Demo 一：`nivroos chat` 查天气穿衣 |
| 三：Memory | `MemoryService`、`LongTermMemory`、`MEMORY.md` | Demo 二：跨对话记偏好 |
| 四：Plugin Tool | `ToolRegistry`、MCP Client、`Sandbox` | Demo 三：零代码 PR digest |
| 五：Web Service | `WebServer`、`ApiController` × 6 | Demo 四 + 五：REST 同步调用 + 多端点联动 |

验收 Demo 共 5 个（跨文档总数）；需求文档 §13 / 技术方案 §12 另有 3 个**每日自动运行**的端到端 Demo（每日天气、每日科技日报、每日 GitHub 日报），由 `AgentScheduler` 钟推触发，覆盖 Skill 渐进披露的 L1/L2/L3，其中 GitHub 日报 Demo 演示带 `scripts/` 脚本的 Agent 目录。

---

## 实施节奏

### User Story 依赖序（Spec-Kit 拆解，AI 编程指南）

```text
US-1 → US-2 → ┌─ US-3（Memory）┐ → US-5
               └─ US-4（Tool） ─┘
```

US-1 对接 LLM → US-2 ReAct 循环 →（US-3 Memory ∥ US-4 Plugin Tool 并行）→ US-5 Web Service。**每个 user story 结束必跑 `/speckit.analyze`** 一致性检查；git commit 标记每个 user story 完成。

### 四周节奏（每周 3 小时）

| 周次 | 核心任务 | 涉及模块 | 可演示成果 |
| --- | --- | --- | --- |
| 第一周 | Provider 抽象 + ReAct Loop | `nivroos-core` `nivroos-provider` `nivroos-channel-cli` `nivroos-cli` | `nivroos chat` 多轮对话，调 HTTP Tool 完成天气查询 |
| 第二周 | Memory + Tool 体系 | `nivroos-memory` `nivroos-tool` | Agent 记住偏好并用到；调本地文件 + 外部 MCP server |
| 第三周 | Web Service | `nivroos-web` `nivroos-storage` | 外部系统通过 10 个 REST 端点完整调用 |
| 第四周 | 多 Agent 演示 + 工程化收尾 | 所有模块收尾 | 多 Agent 并存；Session SQLite 跨重启恢复；`AgentScheduler` 到点触发；12 命令完整；项目主页 |

---

## 提交信息规范

**格式**：Conventional Commits（标题）+ 五段式正文。模板已配置于 `.gitmessage`（`git config commit.template`，仓库级），`git commit` 打开编辑器自动带出：

```text
<type>(<scope>): <subject>

**Problem / Background:**
<为什么改：现象、触发场景、根因>

**Design:**
<怎么改：关键决策、结构、对应的原则/文档>

**Testing:**
<怎么验证：命令、用例、结果>

**Impact:**
<影响范围：涉及模块、依赖/兼容性、后续项>
```

| 字段 | 取值 |
| --- | --- |
| `type` | `feat` / `fix` / `docs` / `refactor` / `chore` / `test` / `perf` / `style` |
| `scope` | 模块名：`core` / `provider` / `memory` / `tool` / `web` / `storage` / `cli` / `boot`；全局改动省略 |
| `subject` | 英文、祈使句、动词开头、≤72 字符 |
| 正文 | 英文；小改动（如 `docs:`）可只留标题，删掉不需要的小节 |

**配套约定**：

- 每个 user story 完成打一个 commit（见"实施节奏"），commit 标记 US 完成
- 发版：改根 POM `<version>`（唯一事实源）→ `mvn clean package` 验证 → `chore(release):` 提交 → `git tag v<版本>`（SemVer，tag 是版本锚点）
- 版本号只出现在根 POM 和 tag 里，**不写进 commit message**

## 常见陷阱

| 陷阱 | 症状 | 修复 |
| --- | --- | --- |
| Spring AI 自动执行 tool | Tool 被调两次，结果重复 | 禁用自动 tool 执行，由 `ToolExecutor` 接管（原则二） |
| Provider 靠类型扫描区分 | 多 Provider 时路由错乱 | 改用显式 `Map<String, ChatModel>` 映射（原则三） |
| `AGENT.md` / 子指令放进 Tool 模块 | Agent 目录被当 Tool 注册，执行时报错 | 归 `ContextLoader`：正文注入 system prompt（原则四） |
| 审计表只写日志不落库 | 扩展阶段审计功能需要反解析日志 | `tool_invocations` + `llm_calls` 核心阶段就写入 SQLite（原则五） |
| 用 `hibernate.ddl-auto=update` 迁移表结构 | SQLite ALTER TABLE 报错 | 手动维护建表脚本或引入 Flyway |
| 在 ReAct Loop 里用异步 | 复杂度激增，Virtual Thread 优势消失 | 保持同步阻塞（原则七） |
| Tool 模块拆成多个 | 模块间依赖混乱 | 内置 Tool + MCP Client 合并为一个 `nivroos-tool` 模块（原则八） |
| 用非 JDK 21 特性 | 构建/运行报错 | 强制要求 JDK 21 |
| `ProviderService` 结果按容器扫描 | 多 Provider Bean 类型相同产生歧义 | provider name 到 `ChatModel` 显式映射 |
| `notify` 的 webhook 不过 Sandbox | 推送绕过域名白名单 | `WebhookNotifyAdapter` 发送前同样 `Sandbox.enforce(HTTP_REQUEST, url)` |
| springdoc 用 3.x | fat JAR 混入 `spring-boot-webmvc`/`spring-boot-tomcat` 等 4.x 模块，启动报 `NoClassDefFoundError: ApplicationServletEnvironment` | 固定 springdoc 2.8.x（2.8.17；3.x 整条版本线只适配 Boot 4，已实测） |
| 伞式 `spring-ai-alibaba-starter` | 1.1.2.x 坐标解析 404 / 版本缺失（BOM 只管理 8 个 artifact） | 按 provider 引用 `spring-ai-alibaba-starter-dashscope` 等，版本显式写 `${spring-ai-alibaba.version}`；DeepSeek/Zhipu/Anthropic/OpenAI 用 Spring AI 官方 `spring-ai-starter-model-*`；配套实测：alibaba 1.1.2.3 ↔ Spring AI 1.1.2 ↔ Boot 3.5.x |
| Kimi 用官方 kimi/moonshot starter | `spring-ai-starter-model-kimi` 不存在（404）；`spring-ai-starter-model-moonshot` 仅 1.0.0-M7 断更里程碑；1.1.x 最新 BOM（1.1.5）也不管理（均实测 2026-08-27） | Kimi 走 OpenAI 兼容通道：`spring-ai-starter-model-openai` 1.1.2 + Moonshot 兼容端点，base-url 配 `https://api.moonshot.cn`（**不带 /v1**——OpenAiApi 自行追加路径，带 /v1 会 404 /v1/v1，实测 2026-08-31）（US-1 research §1） |
| 格式问题手改代码 | `mvn verify` 的 spotless:check 挂掉，CI 与本地不一致 | `mvn spotless:apply` 后提交，不手改格式（init-foundation skill） |
| OWASP dependency-check 绑进默认构建 | 每次构建下载 NVD 库，分钟级拖慢 demo 节奏 | 只放 `-Psecurity` profile 手动/夜间跑，`NVD_API_KEY` 加速 |
| 质量插件版本随手升级 | 与实测锁定版本漂移、坐标解析失败 | 先 curl repo1.maven.org metadata 核实，再改根 POM properties 并标注日期 |
| 用 actuator 做健康检查 | 与文档定死的 `/api/v1/health` 自定义端点重复 | 核心阶段不引 actuator/Micrometer；监控=结构化日志+MDC+审计表+`MetricsRegistry` 接口预留（原则九） |
| Spring AI eager 自动装配 | 启动即创建 `ChatModel` 并索要 api-key，绕过 Provider 显式映射（原则二/三） | application.yml `autoconfigure.exclude` 排除：`OpenAiAutoConfiguration` + `DeepSeekChatAutoConfiguration` + OpenAI 系全部六个（Chat/Embedding/Image/AudioSpeech/AudioTranscription/Moderation）；ChatModel 一律由 `ProviderAutoConfiguration` 显式构造（2026-08-31 实测补全清单）。另：Boot 3.5 绑定器对 `@ConfigurationProperties` 的 `${ENV_VAR}` 保持字面量不解析，构建 ChatModel 前必须 `environment.resolvePlaceholders(...)` 显式解析 |
| `Environment.getProperty(key, List.class)` 读 YAML 列表 | 返回 null（列表以索引键存储），白名单变空导致 Sandbox 全拒 | 用 `Binder.get(environment).bind("key", Bindable.listOf(String.class))` 读取（2026-08-31 Demo 实测） |
| 用 `System.out` 打日志 | 输出无时间戳/级别/MDC，绕过 logback 与日志采集 | 统一 SLF4J（init-foundation skill 宪法条目） |
| `schema.sql` 只有注释 | 启动报 `'script' must not be null or empty`（ScriptUtils 剥离注释后脚本为空） | 保留一条占位语句（如 `SELECT 1;`）直到首张表落地（已实测 2026-08-28） |
| `AGENT.md` 不声明 `tools:` | 本轮工具池为空，模型把 tool call 当**文本**吐出（不执行、不报错，看起来像"答了但没生效"） | frontmatter 必须显式列出工具名——`ReActLoop` 按 `Profile.tools` 名从注册池解析，不声明 = 空列表，本轮无工具可选（2026-09-30 Demo 二实测） |
| `recall_memory` 传多词 query | 归档区按整串 `contains` 匹配：`"SQLite 方案 评估"` 必不命中，单独给 `SQLite` 才命中 | query 只传**单个关键词**（契约语义：关键词匹配，不分词、不扩展）；若要支持多词属行为契约变更，先裁决再改（2026-09-30 Demo 二实测） |

---

## 设计原则

- **底座优先于 Agent**：最重要的交付不是某个强大的 Agent，而是让任意 Agent 可靠运行的环境（运行时内核）
- **自实现核心，复用管道**：ReAct 循环手写；LLM 协议适配委托给 Spring AI Alibaba（只用一半）
- **一个目录 = 一个 Agent**：业务 Agent 由目录定义（AGENT.md frontmatter 配置 + 正文指令），业务方不需要写 Agent 后端代码
- **对接开放标准**：工具用 MCP 协议，兼容 OpenAI 协议（LLM）、agentskills.io（Skill）
- **统一、私有、易接入、可观测**：企业内多 Agent 共享底座；数据完全留在企业基础设施；标准 Spring Boot 工程结构；Prometheus 指标 + 结构化日志 + 健康检查
- **安全是地基，不是补丁**：工具来源管控、最小权限、强制 Sandbox 白名单、凭证走环境变量、审计记录 day one 写入 SQLite
- **分阶段克制**：核心阶段先构建最小完整的运行时内核（地基），企业级治理（多租户、SSO、完整审计、Tool Policy）在真实使用数据验证后再做

---

## 当前仓库状态

- **业务代码尚未开始，Maven 骨架已就绪**：根 POM + 9 个模块 POM（结构见上），`mvn clean package` 产出 fat JAR，启动验证通过（Tomcat 8080 + Hikari→SQLite + Hibernate SQLiteDialect）
- 版本矩阵已锁定在根 `pom.xml`：Spring Boot 3.5.16（3.x 最终版）、Spring AI Alibaba 1.1.2.3 + Spring AI 1.1.2、springdoc 2.8.17、sqlite-jdbc 3.53.2.1、picocli 4.7.7、MCP SDK 1.1.3；配套关系与陷阱见"常见陷阱"表
- 骨架期 `spring.ai.dashscope.enabled: false`（无 API key）；US-1 实现 ProviderService 时开启，key 用 `${DASHSCOPE_API_KEY}` 注入
- 构建命令：`mvn clean package`（产物 `nivroos-boot/target/nivroos-boot-0.1.0.jar`，`java -jar` 启动）
- **工程地基已初始化（2026-08-28）**：质量门禁（`mvn verify` = Spotless + Checkstyle + SpotBugs/findsecbugs + 测试 + JaCoCo 报告）、日志 dev/prod 双 profile（dev 彩色控制台+滚动文件 / prod JSON，MDC：sessionId/traceId）、虚拟线程开启（`spring.threads.virtual.enabled=true`）、SQLite WAL + `ddl-auto: none` + `schema.sql` 幂等建表（表结构变更一律改 schema.sql）、Spring AI eager 装配已排除、`nivroos-web` 规范层（ApiResponse/ErrorCode/GlobalExceptionHandler）、`nivroos-core` MetricsRegistry 接口预留、pre-commit（`git config core.hooksPath .githooks`）+ GitHub Actions 门禁工作流；初始化流程固化为项目 skill `/init-foundation`（`.claude/skills/init-foundation/`）
- **依赖安全抑制已评审（2026-08-31，US-1 交付）**：`config/dependency-check-suppressions.xml` 8 组抑制经用户决议——核心阶段接受风险（内网假设+路径不执行）；**发布前必须执行依赖升级专项**（Spring AI/Boot 版本线配套重测）并逐组复核抑制（2026-10-01 已扩至 12 组，见下条 US-4 交付记录）
- **Mem0 真服务联调延后至发布前（2026-09-30，US-3 交付决议）**：`memory.backend: mem0` 的代码与 mock HTTP 单测已交付全绿，配置校验三条分支（url 缺失 / api-key 缺失 / 占位符未解析）已在真机二进制上验证启动即大声报错；真实自托管 Mem0 服务的联调（以部署实例 `/openapi.json` 为准核对路径与字段，research §3 登记的三条语义差异同步复核）**与依赖升级专项同批列为发布前清单项**。markdown / sqlite 两档已实测互通（换后端只改一行配置）
- **US-4 Tool 体系已交付（2026-10-01）**：九内置工具（File×3 / Shell / Http×2 / Memory×2 换注册机制 / Notify）+ 三档扩展路径（Agent 目录零代码 / MCP 轻代码 / `@Tool` 重代码）+ `ToolRegistry.scanAnnotated` 统一注册 + `McpToolAdapter` / `AnnotatedToolAdapter` + `WhitelistSandbox` 三档白名单 + `NotifyTools` / `WebhookNotifyAdapter`（发送前过域名白名单）+ `AgentLoader.scan` 与 `ContextLoader` 的 Skill L1 注入 + `notify_channels` 表；`mvn clean verify` 全绿（226 测试 0 失败，SpotBugs / Checkstyle 零告警），US-1~3 测试无回归
- **依赖安全抑制扩至 12 组，收录口径改为全量（2026-10-01，US-4 交付决议）**：NVD 2026-09-29 数据刷新使 16 条 ≥7.0 的既有 CVE 卡住门禁（非 US-4 引入，本模块未加任何新第三方坐标）；复核时查明历史口径实为"只抑制当轮卡门禁的"，另有 31 条 <7.0 从未被抑制 → 用户裁决"真全量收录"。`config/dependency-check-suppressions.xml` 现为 **12 组（89 条 `cve` + 3 条 `vulnerabilityName`）**。⚠ 两条运维要点：①被抑制的 <7.0 条目不再出现在报告的未抑制清单里，**发布前依赖升级专项复核时以本文件的 CVE 列表为准，不要只看报告**；②对账必须同时覆盖报告按钮 `data-type-to-suppress` 的 `cve` 与 `vulnerabilityName` 两类（RetireJS 来源的条目 NVD 无收录，只用 `<cve>` 匹配不上）。登记待办：`tools.jackson.core:jackson-databind` 3.0.1（logstash-logback-encoder 9.0 带入）与 3.0.3（Spring AI / MCP 带入）跨模块并存未收敛；`spring-data-jpa` CVE-2026-47834（Sort 校验绕过）核心阶段不可达，**US-5 接入 REST 查询参数时必须重评**

## 环境

- 当前会话模型：`deepseek-v4-flash`（`/model` 可查看/切换）
- **双平台交替开发**——同一仓库在 Windows 与 macOS 两台机器上来回切换：

| 项 | Windows 11 | macOS |
| --- | --- | --- |
| 仓库路径 | `d:\code\nivroos` | `/Users/my/program/ai_code/nivroos` |
| Shell | Git Bash | zsh（非交互 shell 需先 `source ~/.zshrc` 才有 JAVA_HOME / PATH） |
| JDK / Maven | 标准安装 | 非标准路径：JDK 21 在 `~/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`，Maven 在 `~/.local/opt/apache-maven-3.9.16/bin/mvn`；**Homebrew 已损坏**（识别不了 macOS 版本，任何 `brew` 命令都崩）——禁用 brew 安装/升级工具链 |
| 依赖仓库 | 直连 Maven Central | **Java TLS 连不上 Central**（同一 URL 用 curl 正常，中间网络只干扰 Java 握手），已配设备级 `~/.m2/settings.xml` 镜像到阿里云 `repository/public`（不入库）；构建报"下不到父 POM / 依赖"先确认该文件在，别误判为 pom.xml 写错 |

- 文档、代码注释使用中文；关键注释（类级/方法级/复杂逻辑段）中英并列，中文在前、英文在后
