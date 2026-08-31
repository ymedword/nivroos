<!--
Sync Impact Report
==================

版本变更：脚手架占位（[CONSTITUTION_VERSION]）→ 2.0.0

升级理由：宪法首次物化到 .specify/memory/constitution.md。版本起点对齐技术方案
§11.1 引用的"宪章 v2.0.0"——该修订将公共 Skill 实体与 Agent 绑定分离，改为
Agent 本地 skills/ 相对软连接绑定（frontmatter 不再使用 skills: 字段）。本文件
内容已包含此修订，取 2.0.0 避免宪法与引用它的文档发生版本漂移。

- 修改的原则：无（脚手架此前无实质内容）
- 新增章节：Core Principles（10 条原则）、架构约束、开发工作流、Governance
- 移除章节：无
- 待办占位：RATIFICATION_DATE（首次采纳日期未知，待项目方确认）
-->

# NivroOS Constitution

NivroOS 是 Java 21 实现的面向企业场景的 Agent OS。本宪法是项目开发的
**non-negotiable 原则集**，所有 spec / plan / tasks / implement 流程必须遵守。
原则提炼自《NivroOS 需求文档》第 3 章设计目标与《NivroOS 技术方案》第 1.1 节
关键技术决策，与项目 CLAUDE.md「不可违背的原则」一节同源。

## Core Principles

### I. 自实现 ReAct Loop

- `ReActLoop` **必须**由 NivroOS 自己实现（核心循环约数十行 Java），
  **不得**使用 Spring AI 的 Agent 抽象。
- Tool 的调度和执行完全由 `ReActLoop` + `ToolExecutor` 控制。

**理由**：完全可控，保留未来定制循环行为的空间（技术方案决策一）。

### II. Spring AI 只用两件事（最易写错的一条）

Spring AI 在 NivroOS 里**只能**做两件事：

1. LLM Provider 抽象和向各家 LLM 的协议转换
2. `@Tool` 注解的 JSON Schema 生成

- **必须禁用** Spring AI 的自动 tool 执行；LLM 响应里的 tool call 由
  `ReActLoop` 自己检查、`ToolExecutor` 自己执行并回填。
- **不得**调用 `chatClient.prompt(...).tools(...).call()` 这类自动执行链路。

**理由**：违反此条会导致 **tool 被调两次**——Spring AI 自动执行一次，
`ToolExecutor` 再执行一次（技术方案决策二）。

### III. Provider 必须显式映射

- 多 Provider 并存时，**不得**靠扫描容器里所有 `ChatModel` Bean 区分
  Provider（Bean 类型相同、Bean name 未必等于 provider name）。
- **必须**维护 `provider name → ChatModel` 的显式映射表（如
  `Map.of("deepseek", deepseekChatModel, ...)`），Profile 按 provider name
  引用。

**理由**：类型扫描在多 Provider 并存时产生歧义、路由错乱（技术方案 §3.2）。

### IV. 一个目录 = 一个 Agent；Skill 软连接绑定 + 渐进披露

- 一个目录 = 一个 Agent：`.nivroos/agents/<name>/` 里 `AGENT.md` =
  frontmatter（运行配置）+ 正文（任务指令），外加可选 `skills/` 软连接、
  `scripts/`、`REFERENCE.md`。`AgentLoader.deriveProfile(agentDir)` 把
  frontmatter 派生成底座认识的 `Profile`。
- **`AGENT.md` 不是 Tool**：正文由 `ContextLoader` 注入 system prompt；
  Skill 不进 `ToolRegistry`，不新增 `use_skill`。
- Skill 绑定由 Agent 本地 `skills/` **相对软连接**表达，软连接集合是唯一
  绑定真相源（frontmatter 不使用 `skills:` 字段）。注入遵循渐进披露：
  L1 每轮只注入 name + description + 本地绝对路径 → L2 模型命中后经
  `read_file` 读 `SKILL.md` 正文 → L3 附属参考/脚本按需再取。不预载正文。

**理由**：v2.0.0 修订——公共 Skill 实体与 Agent 绑定分离（技术方案 §11.1）。

### V. 审计表 Day One 写入

- `tool_invocations` 和 `llm_calls` 两张审计表核心阶段**就必须写入**
  （不要求查询接口，但写入不能省）。
- **不得**以"日志够了"为由跳过落库。

**理由**：可审计是 NivroOS 的差异化能力，数据地基 day one 立起来，
避免后期从日志反解析返工（技术方案决策七）。

### VI. Sandbox 接口先行，核心阶段唯一实现 WhitelistSandbox

- **不得**使用 Java `SecurityManager`（JDK 17 起废弃、JDK 21 已不可用）。
- `Sandbox` 接口只表达"在受控环境里执行一个动作"：
  `Sandbox.enforce(SandboxAction)`，`ActionType` 取
  `FILE_READ | FILE_WRITE | SHELL_COMMAND | HTTP_REQUEST`，
  接口不携带任何实现细节。
- 核心阶段唯一实现 `WhitelistSandbox`（配置在 `application.yaml`）：
  文件操作路径白名单（需处理 `../` 路径穿越）、Shell 命令首 token 白名单、
  HTTP 域名通配符白名单。
- 校验失败抛 `SandboxViolationException`，走 `ToolExecutor` 既有失败审计
  路径（`success=false`、`error_message`）。
- `NotifyTools` 的 webhook 发送同样必须过 HTTP 域名白名单。
- 扩展阶段按信号驱动升级（容器 → microVM），接口不变、只新增实现类。

**理由**：接口独立于实现，未来换重隔离方案不用改调用方（技术方案决策六）。

### VII. 同步执行模型

- 核心阶段全程同步阻塞，配合 Java 21 Virtual Thread 处理并发。
- **不得**引入 Reactor / WebFlux / CompletableFuture 等异步模型；
  SSE 流式响应放扩展阶段。

**理由**：直观简洁、无需响应式编程，单节点靠 Virtual Thread 撑高并发
（技术方案决策三）。

### VIII. Tool 模块三合一

- 内置 Tool、MCP Client、`ToolRegistry`、Sandbox 合并为一个 `nivroos-tool`
  模块，**不得**拆成 builtin/skill/mcp 等多个模块。

**理由**：共享同一个 `NivroTool` 抽象和 `ToolRegistry`，耦合度高
（技术方案 §6）。

### IX. 五大核心能力优先

- 核心阶段交付运行时内核（对接 LLM、ReAct、Memory、Tool、Web Service
  五大核心能力）；企业级治理层（多租户、SSO、完整审计、Tool Policy）
  放扩展阶段。
- 范围卡紧：完不成的功能挪到扩展阶段，而不是砍验收。

### X. 每个 User Story 完成有可演示 Demo

- 每个 user story 结束必须有可演示成果；优先级是跑通而非完美，
  每周末有可演示成果。
- 验收 Demo 与 user story 的映射见「开发工作流」。

## 架构约束（Architecture Constraints）

技术栈在核心阶段锁定，不得擅自替换：

| 组件 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 21（**必须**，不得使用非 JDK 21 特性） |
| 框架 | Spring Boot 3.x 单体应用 |
| LLM 调用 | Spring AI + Spring AI Alibaba（仅协议转换 + `@Tool` schema 生成） |
| HTTP 服务 | Spring MVC + Java 21 Virtual Thread |
| 命令行 | Picocli |
| YAML 解析 | SnakeYAML |
| 持久化 | SQLite + Spring Data JPA |
| MCP Client | MCP Java SDK（社区项目，可能需要部分自实现） |
| 日志 | Logback + SLF4J（结构化日志） |
| 指标 | Micrometer + Prometheus（扩展阶段） |
| 构建 | Maven 多模块（9 个），`mvn clean package` 产出 fat JAR，`java -jar` 启动 |

**模块结构固定为 9 个，不拆不并**：plan 里的模块划分必须与技术方案第 10 章
一致（`nivroos-core` / `nivroos-provider` / `nivroos-memory` / `nivroos-tool` /
`nivroos-channel-cli` / `nivroos-web` / `nivroos-storage` / `nivroos-cli` /
`nivroos-boot`）。

**依赖与版本约束（实测陷阱，违反即构建/启动失败）**：

- springdoc **必须**用 2.8.x：3.x 整条版本线只适配 Boot 4，混入 fat JAR 会
  启动报 `NoClassDefFoundError: ApplicationServletEnvironment`。
- **不得**引用伞式 `spring-ai-alibaba-starter`（坐标解析 404）；按 provider
  引用 `spring-ai-alibaba-starter-dashscope` 等具体 starter。
- SQLite 表结构变更**不得**依赖 `hibernate.ddl-auto=update` 自动迁移
  （SQLite `ALTER TABLE` 支持很弱）；必须手动维护建表脚本或引入
  Flyway/Liquibase。
- 敏感配置（API key、MCP 凭证）必须用 `${ENV_VAR}` 占位、经环境变量解析，
  **不得**明文落盘；`ConfigLoader` 校验必填项，缺失或非法必须清晰报错。

> 具体版本号以根 `pom.xml` 为唯一事实源，不写死在本宪法中。

## 开发工作流（Development Workflow）

- **Spec-Kit 流程**：constitution → specify → plan → tasks → implement，
  主体开发阶段按此推进；增量阶段允许手动提示词 + Claude Code。
- **User Story 依赖序**：US-1 → US-2 →（US-3 ∥ US-4）→ US-5。
  推进顺序按依赖关系，不按重要性。
- **每个 user story 结束必跑 `/speckit.analyze`**（一致性检查，不能省），
  并以 git commit 标记该 user story 完成。
- **plan 生成后人工 review 是必要环节**，重点检查：

  - [ ] Memory 没有被简化成与 Session 合并（应为 `MemoryService` 统一门面）
  - [ ] Tool 没有被拆成多个模块（应为合并的 `nivroos-tool` 一个模块）
  - [ ] `AgentLoader` / `AGENT.md` 没有被当成 Tool（应归 `ContextLoader`）
  - [ ] 没有启用 Spring AI 的自动 tool 执行（必须禁用）

- **验收 Demo 映射**：US-1+US-2 → Demo 一（查天气穿衣）；US-3 → Demo 二
  （跨对话记偏好）；US-4 → Demo 三（零代码 PR digest）；US-5 → Demo 四 +
  Demo 五（REST 同步调用 + 多端点联动）。
- **提交信息**：Conventional Commits 五段式（Problem / Design / Testing /
  Impact）；版本号只出现在根 POM 和 git tag 里，不写进 commit message。

## Governance

- **宪法地位**：本宪法是非协商原则，spec / plan / tasks / implement 一律
  必须遵守；实现与宪法冲突时，以宪法为准并回到修正实现，不得绕过。
- **修订程序**：宪法写一次定下来，主体开发期间不改。中途发现某条原则
  不对，必须停下来由项目方讨论决议；**AI agent 不得自行修改宪法**。
  修订必须走 `/speckit-constitution` 流程并经项目方确认。
- **版本策略**：SemVer。MAJOR = 原则的删除或重定义（向后不兼容）；
  MINOR = 新增原则/章节或实质扩展；PATCH = 澄清、措辞、非语义修正。
- **合规审查**：每个 user story 结束跑 `/speckit.analyze`；每次 implement
  后人工检查，发现偏离立即让 AI agent 重读宪法修正；社区贡献（增量阶段）
  同样必须遵守本宪法。
- **文档裁决**：宪法原则与项目 CLAUDE.md「不可违背的原则」同源
  （需求文档第 3 章 + 技术方案第 1.1 节）；细节不一致时以最新技术方案为准。
  4 份项目文档各司其职，不互相"修正"。
- **运行时指引**：日常开发的落地细节（代码示例、常见陷阱表）见项目
  CLAUDE.md，本宪法只定原则不定实现细节。

**Version**: 2.0.0 | **Ratified**: TODO(RATIFICATION_DATE): 首次采纳日期未知，
待项目方确认 | **Last Amended**: 2026-08-27
