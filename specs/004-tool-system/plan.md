# Implementation Plan: US-4 Tool 体系（内置 Tool 补全 + Plugin Tool 三档 + Sandbox 完整版）

**Branch**: `004-tool-system` | **Date**: 2026-09-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-tool-system/spec.md`
（骨架由 `/speckit-specify` 从颗粒度文档 `docs/us/us4-tool.md` §1/§2 组装；
plan 生成后须按 AiProgrammingGuide §3.4 做人工 review，清单见文末）

**注意**：本 plan 由 `/speckit-plan` 生成，前置于 `tasks.md`（`/speckit-tasks` 产出）。
`plan.md` 只回答"是什么形状、落在哪、受哪些约束"，任务拆分与执行顺序不在此文件。

## Summary

把"Agent 能干事"补齐——**九个内置 Tool 全部到位**（US-2 只交付 `http_get`），
**Plugin Tool 三档基础设施**（方式一：零代码 Agent 目录 + 复用社区 MCP server；
方式二：自写 MCP server；方式三：`@Tool` 注解 Java Bean），**统一 `ToolRegistry`**
（三类来源同一份登记、同一套取用），**`WhitelistSandbox` 三档白名单全量**
（文件路径 / 命令首 token / HTTP 域名，空配置 = 全拒），**通知工具 + 全局渠道注册表**
（SQLite `notify_channels` + `WebhookNotifyAdapter`），以及 **`AGENT.md` 完整派生 +
`ContextLoader` 的 Bootstrap 消费与 Skill 元数据（L1）注入**。

工具同时是安全边界：文件 / 命令 / HTTP 动作**在真正发生之前**先过白名单，拒绝即失败并
留下审计 + WARN 双轨痕迹；被拒动作**不得真正发生**（spec FR-002/FR-006/SC-002）。
本模块零新增第三方坐标（依赖增补见下），模块数仍为 9。

**核心阶段不做**：框架自动执行工具、工具调用并行、MCP 的 SSE / Streamable HTTP 与
resources / prompts、容器 / microVM 沙箱与脚本网络隔离、Agent 级 Tool Policy、
企业微信 / 飞书 / 钉钉专用 API、通知渠道 CRUD 端点与管理台、Agent 目录热加载、
技能正文预载、Shell 参数级路径校验与资源限额。

## Technical Context

**Language/Version**: Java 21（宪法约束；虚拟线程处理并发）

**Primary Dependencies**: 沿用锁定矩阵（Spring Boot 3.5.16 / Spring AI 1.1.2 /
Spring AI Alibaba 1.1.2.3 / MCP SDK 1.1.3 / SnakeYAML / Jackson 2 / JDK `HttpClient`）。
**零新增第三方坐标**——本模块新增两条依赖声明，均为既有 BOM 管理、且同一坐标已在
仓库其他模块声明：`nivroos-memory` 增 `org.springframework.ai:spring-ai-model`
（`@Tool` 注解，同坐标已在 `nivroos-tool` 声明）、`nivroos-tool` 增
`org.springframework.boot:spring-boot`（`@Configuration` / `Environment` / `Binder`，
非 starter、不带自动装配，`autoconfigure.exclude` 清单不变）。

**Storage**: SQLite 增第四张表 `notify_channels`（`nivroos-boot/src/main/resources/schema.sql`
手工幂等建表，`ddl-auto: none` 不变）；配置文件 `.nivroos/mcp_servers.yaml`（`init` 已生成）。

**Testing**: JUnit 5 + Mockito + AssertJ（骨架已配）。单测**不碰真实网络、不起真实子进程**：
文件用 `@TempDir`、Shell 用 `echo` 无副作用命令、HTTP / webhook 用 mock `HttpClient`、
MCP 用包内可见测试构造注入 mock transport。真机冒烟（真 MCP server / 真 webhook / 真模型）
列人工项，见 [quickstart.md §3](./quickstart.md)。

**Target Platform**: Linux（部署目标；大小写敏感、POSIX `bash -c`），Windows 开发机需 Git Bash

**Project Type**: Maven 多模块（9 个固定）+ Spring Boot 单体

**Performance Goals**: 无新增量化目标；沙箱校验为进程内字符串 / 路径比对（可忽略）；
MCP 工具列表启动期拉取一次并常驻

**Constraints**: 同步阻塞（原则七），不引 Reactor / CompletableFuture / 自建线程池
（MCP 一律 `McpSyncClient`）；审计双轨（审计落库 + WARN 日志，异常不吞）；
凭证只允许 `${ENV_VAR}` 占位；`Provider` 层只翻译不执行（原则二）

**Scale/Scope**: 九个内置工具 + 每部署若干 MCP server（每个 server 的工具数取决于其自身）；
单个工具调用同步执行、一轮响应内多个调用顺序执行

## 模块落位（颗粒度文档 §3.1 直取；两处落在 module-dev 落位表之外，已由用户 2026-09-30 裁决）

| 模块 | 交付物 |
| --- | --- |
| `nivroos-tool` | `ToolRegistry`、`AnnotatedToolAdapter`（包内可见）、`FileTools`、`ShellTools`、`HttpTools`（补 `http_post` + `http_get` 改标注）、`NotifyTools`、`NotifyChannelAdapter` + `NotifyTarget` + `WebhookNotifyAdapter`、`McpServerConfig`、`McpClientService`、`McpToolAdapter`、`WhitelistSandbox` 三档补全、`ToolConfiguration` |
| `nivroos-core` | `Profile` 字段补齐（`description` / `identity` / `mcp_servers` / `bootstrap` / `channels` / `schedules`）、`AgentLoader.scan()`、`NotifyChannel` record + `NotifyChannelStore` 接口（依赖倒置：接口在 core、实现在 storage）、`ContextLoader` 的 Bootstrap 按 Profile 消费 + Skill 元数据（L1）注入 |
| `nivroos-storage` | **`NotifyChannelEntity`**（JPA 实体，表 `notify_channels`；类名裁决 2026-09-30，见软门禁报告项 4）+ `NotifyChannelRepository` + `JpaNotifyChannelStore` + `NotifyChannelStoreConfiguration` |
| `nivroos-boot` | `ProfileConfiguration`（启动扫描 + 跨模块校验，单 Agent 失败只 WARN）、`application.yml` 增 `file.allowed_paths` / `shell.allowed_commands`（`http.allowed_domains` 沿用）、`schema.sql` 增 `notify_channels`、示例 `@Tool` Bean（方式三演示，编译进进程） |
| `nivroos-memory` | **改造点**：`MemoryTools` 改标 `@Tool`（工具名 / 参数名 / 失败语义逐字不变）+ `pom.xml` 增 `spring-ai-model` |
| `nivroos-cli` | **改造点**：`ChatCommand` 工具池由手工 `Map.of(...)` 换成容器注入的 `ToolRegistry` |

> **两处超落位表（已裁决）**：① `NotifyChannelStore` 接口在 core、JPA 实现在 storage
> （依赖倒置，同 `ToolInvocationStore` 先例）；② 启动扫描 `ProfileConfiguration` 在
> `nivroos-boot`（装配层同时可见 ProviderService / ToolRegistry / 文件系统）。
> **US-5 范围不在本模块**：`tool list` 命令、`GET /api/v1/tools`、`GET /api/v1/profiles`
> 需要的 `ProfileRegistry` 列表能力。

## 前序改造点（颗粒度文档 §3.2 已明确授权，软门禁例外条件成立）

1. **`WhitelistSandbox` 构造签名扩展**（`nivroos-tool`，US-2 交付）：一参 →
   `WhitelistSandbox(List<String> allowedPaths, List<String> allowedCommands, List<String> allowedDomains)`；
   `Sandbox` 接口**不变**；`WhitelistSandboxTest` 同步（原"文件 / Shell 留 US-4 明确拒绝"
   用例替换为三档放行 / 拒绝用例）。
2. **`MemoryTools`（US-3）与 `HttpTools`（US-2）改标 `@Tool`**：工具名与参数名逐字不变
   （`save_memory` / `recall_memory` / `http_get` 及各自参数）；schema 来源从手写 JSON
   切到注解生成；注册机制由"手工 `Map.of`"切到 `ToolRegistry.scanAnnotated`；
   失败语义**不变**（业务性失败返回失败结果、异常性失败抛异常——见 research §2）；
   `MemoryToolsTest` / `HttpToolsTest` 同步（仅换获取路径，断言体保留）。
3. **`ChatCommand` 装配改造**（`nivroos-cli`，US-2 交付）：工具池 = `toolRegistry.all()`；
   `ToolExecutor` / `ReActLoop` 构造签名不变。
4. **`ContextLoader` 改造**（`nivroos-core`，US-2 交付）：Bootstrap 列表改为按
   `Profile.bootstrap`（缺失回退默认三件 + WARN）；**同批交付** Skill 元数据（L1）注入
   （软连接扫描 + frontmatter 取 `name`/`description` + Agent 本地绝对路径注入）；
   `loadSystemPrompt()` 签名不变；`ContextLoaderTest` 同步。
5. **`Profile` / `AgentLoader` / `ProfileRegistry` 字段与派生补齐**（US-1/US-2 交付）：
   只增不删；`AgentLoader` 增 `scan()`（`loadProfile` 语义不变）；`ProfileRegistry`
   **不加新方法**；`AgentLoaderTest` 同步。
6. **`ReActLoop` 工具池过滤补测**（US-2 交付）：`resolveTools` 是私有方法、**不改签名**，
   只补 `ReActLoopTest` 用例——池内恰好是 `Profile.tools` 声明的那几个（不多不少）。
   风险来源：US-2 时池内只有 2 个工具，本模块后变 9 个 + MCP 若干，过滤一旦回归即
   **工具池泄漏**（模型可调未声明的工具）。
7. **`ApiController` / `tool list` 命令归 US-5**：本模块**只**交付 `ToolRegistry`。

## 软门禁报告项（实施前置，需用户知悉）

> 以下三条不是"新概念发明"，但都超出了**颗粒度文档字面**，按 module-dev 软门禁纪律登记：

1. **技能软连接真实目标越界校验**（spec FR-028）：技术方案 §8.3 明写
   「验证真实目标位于公共 Skill 根」，而颗粒度文档 §3.1/§3.2 未列。按"文档与最新技术
   方案冲突以技术方案为准"落地，已进 spec FR-028 + `ContextLoaderTest` 用例。
2. **`NotifyChannelStore` 增加第二个接口方法 `channelNames()`**：FR-016 要求 `notify`
   失败信息回填"可用渠道名清单"，而 `findByName` 单个方法取不到清单；该方法是
   core 侧接口的**新增公开成员**（类型本身在 §3.1 点名）。若否决，则退化为
   错误信息只回显调用方给的渠道名（`NotifyToolsTest` 的清单断言同步弱化）。
3. **依赖声明增补两条**（均非新第三方坐标）：`nivroos-memory` 增 `spring-ai-model`
   （`@Tool` 注解，改标注的必然结果）、`nivroos-tool` 增 `spring-boot`
   （`@Configuration` / `Binder`）。二者均由既有 BOM 管理，且同一坐标已在仓库内声明。
4. **storage 通知实体改名为 `NotifyChannelEntity`**（用户裁决 2026-09-30）：颗粒度文档
   §3.1 写的 `NotifyChannel` 实体与 core 侧 `NotifyChannel`（record）**简单名撞车**，
   `JpaNotifyChannelStore` 同包引用实体时 core 的同名类型须写全限定名。仓库既有
   `ToolInvocation` / `LlmCall` 两个实体在 core 侧**没有**同名对应物，故无先例可循。
   裁决：storage 实体取 `NotifyChannelEntity`，doc 的 `NotifyChannel` 一名让给 core record。
   **DoD 存在性核对（步骤 7 第 3 项）按本名核对，不按 doc 原名**。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查点 | 结论 |
| --- | --- | --- |
| I 自实现 ReAct Loop | `ReActLoop` 签名与循环体不改；`ToolRegistry` 只提供登记与查找，不接管循环 | PASS |
| II 只用 Spring AI 两件事 | `@Tool` 只用于 **schema 生成**；`AnnotatedToolAdapter` 的 `call` 由 `ToolExecutor` 触发（无自动执行路径）；`internalToolExecutionEnabled(false)` 保持；`nivroos-tool` 无 `ChatClient` / `ChatModel` | PASS |
| III Provider 显式映射 | 不涉及（US-1 已保证） | PASS |
| IV 一目录一 Agent / Skill 渐进披露 | 技能**只注入 L1 元数据**、正文不预载、不进 `ToolRegistry`、不新增 `use_skill`；`AGENT.md` 归 `ContextLoader`，不进工具模块 | PASS |
| V 审计 Day One | 全部工具经既有 `ToolExecutor` → `tool_invocations`（成败都落）；**本模块不新增审计路径**（含 MCP 与 notify） | PASS |
| VI Sandbox 接口先行 | `Sandbox` 接口不变，同一实现类扩三档；`notify` 发送前过 HTTP 域名白名单；失败抛 `SandboxViolationException` 走既有失败审计路径 | PASS |
| VII 同步执行 | 全程同步；MCP 用 `McpSyncClient`；源码无 Reactor / `CompletableFuture` / 自建线程池（仅测试用虚拟线程执行器做并发隔离用例） | PASS |
| VIII Tool 三合一 | 内置 Tool + MCP Client + `ToolRegistry` + Sandbox 全在 `nivroos-tool`；不新增 / 不拆分模块 | PASS |
| IX 核心能力优先 | Tool Policy、容器沙箱、渠道专用适配器、CRUD 端点、热加载全部留在扩展阶段 | PASS |
| X 每 US 可演示 | Demo 锚点：需求 §13「Demo 二 能力四部分」（方式一 + 方式二）+ 技术方案 §12.3（`shell` 跑捆绑脚本）；定时触发归 US-5，本模块用手动补跑验收 | PASS |
| 模块结构 9 个固定 | 涉及 core / tool / memory / storage / cli / boot 六个既有模块，零新增零拆分 | PASS |
| 依赖与版本约束 | 零新增第三方坐标；SQLite 表结构手工维护 `schema.sql`；凭证全 `${ENV_VAR}` 占位 | PASS |
| 日志纪律 | 统一 SLF4J；失败路径 WARN + 审计双轨；无 `System.out` | PASS |

## Project Structure

### Documentation (this feature)

```text
specs/004-tool-system/
├── plan.md              # 本文件（/speckit-plan 产出）
├── spec.md              # /speckit-specify 产出（含 3 条 Clarifications、FR-001~FR-028）
├── research.md          # Phase 0 产出：注册机制/适配器语义/白名单匹配/MCP/通知/配置/装配 + H3 核实清单
├── data-model.md        # Phase 1 产出：沙箱/注册表/九工具/渠道/DDL/MCP/Profile/Skill L1/配置
├── quickstart.md        # Phase 1 产出：门禁命令 + 不变量 grep + 人工冒烟五项
├── contracts/           # Phase 1 产出：builtin-tools / registry-and-sandbox / mcp-and-notify / agent-context
├── checklists/
│   └── requirements.md  # /speckit-specify 产出（16/16 通过，clarify 后复核）
└── tasks.md             # Phase 2 产出（/speckit-tasks 命令，本命令不生成）
```

### Source Code (repository root)

```text
nivroos-tool/src/main/java/com/nivroos/tool/
├── ToolRegistry.java                     # 【新增】register / get / all / scanAnnotated
├── AnnotatedToolAdapter.java             # 【新增】包内可见，@Tool → NivroTool
├── FileTools.java                        # 【新增】read_file / write_file / list_dir
├── ShellTools.java                       # 【新增】shell（ProcessBuilder + 超时强杀）
├── HttpTools.java                        # 【修改】http_get 改标注 + 新增 http_post（工具名/参数名不变）
├── NotifyTools.java                      # 【新增】notify
├── NotifyChannelAdapter.java             # 【新增】接口
├── NotifyTarget.java                     # 【新增】record（channelType / config）
├── WebhookNotifyAdapter.java             # 【新增】唯一实现（Sandbox, HttpClient）
├── McpServerConfig.java                  # 【新增】record + yaml 解析 + env 占位校验
├── McpClientService.java                 # 【新增】start / close（失联容错）
├── McpToolAdapter.java                   # 【新增】isError → 失败结果
├── ToolConfiguration.java                # 【新增】@Configuration：Binder 读配置 → 全部 Bean
└── sandbox/
    ├── Sandbox.java                      # 【不改】接口（原则六）
    ├── SandboxAction.java                # 【不改】
    ├── SandboxViolationException.java    # 【不改】
    └── WhitelistSandbox.java             # 【修改】一参 → 三参；补 checkFilePath / checkShellCommand
nivroos-tool/src/test/java/com/nivroos/tool/   # 11 个测试类（见 spec/颗粒度文档 §4.2 映射表）

nivroos-core/src/main/java/com/nivroos/core/
├── profile/Profile.java                  # 【修改】+description / identity / mcp_servers / bootstrap / channels / schedules
├── loader/AgentLoader.java               # 【修改】+scan()（loadProfile 语义不变）
├── notify/NotifyChannel.java             # 【新增】record
├── notify/NotifyChannelStore.java        # 【新增】接口（findByName + channelNames）
└── context/ContextLoader.java            # 【修改】Bootstrap 按 Profile + Skill L1 注入（签名不变）
nivroos-core/src/test/java/com/nivroos/core/   # AgentLoaderTest / ContextLoaderTest / ReActLoopTest 同步

nivroos-storage/src/main/java/com/nivroos/storage/notify/
├── NotifyChannelEntity.java              # 【新增】JPA 实体（表 notify_channels；类名裁决见软门禁报告项 4）
├── NotifyChannelRepository.java          # 【新增】
├── JpaNotifyChannelStore.java            # 【新增】实现 core 接口
└── NotifyChannelStoreConfiguration.java  # 【新增】装配（同 ToolInvocationStoreConfiguration 模式）
nivroos-storage/src/test/java/com/nivroos/storage/notify/JpaNotifyChannelStoreTest.java   # 【新增】

nivroos-memory/src/main/java/com/nivroos/memory/MemoryTools.java   # 【修改】改标 @Tool（名/参数/语义不变）
nivroos-memory/src/test/java/com/nivroos/memory/MemoryToolsTest.java  # 【同步】

nivroos-cli/src/main/java/com/nivroos/cli/ChatCommand.java         # 【修改】工具池 = toolRegistry.all()

nivroos-boot/src/main/java/com/nivroos/
├── ProfileConfiguration.java             # 【新增】启动扫描 + 跨模块校验
└── example/…ToolBean.java                # 【新增】方式三示例 @Tool Bean（类名实施时定）
nivroos-boot/src/main/resources/
├── application.yml                       # +file.allowed_paths / shell.allowed_commands / shell.timeout-seconds
└── schema.sql                            # +notify_channels 建表（幂等）
```

**Structure Decision**: 零新增模块。内置工具实现、注册表、MCP 客户端、沙箱、通知出站
全部收在 `nivroos-tool`（宪法原则八三合一）；Agent 目录派生与上下文装配归
`nivroos-core`（`AGENT.md` 不是 Tool，原则四）；通知渠道的接口在 core、JPA 实现在
storage（依赖倒置，同 `ToolInvocationStore` 先例）；启动装配在 `nivroos-boot`
（唯一能同时看到 ProviderService / ToolRegistry / 文件系统的模块）。
依赖方向恒为 `tool → core`、`memory → core`、`storage → core`、`boot → 全部`，
`core` 不反向依赖任何实现模块（quickstart §2 有 grep 门禁）。
键字面量与工具契约逐字对齐 [contracts/](./contracts/)；数据形状见
[data-model.md](./data-model.md)；验证路径见 [quickstart.md](./quickstart.md)。

## Complexity Tracking

无宪法违规，本节不适用。（两处超出 module-dev 落位表的落点已由用户 2026-09-30 裁决并
登记在「模块落位」；`NotifyChannelStore.channelNames()` 与两条依赖声明已按软门禁登记在
「软门禁报告项」——三者都不改变 9 模块结构与原则界线。）

## 人工 Review 清单（AiProgrammingGuide §3.4，review 通过后本 plan 锁定）

- [ ] **Memory 没有被简化成与 Session 合并**——本模块只改 `MemoryTools` 的标注与注册
      路径，`MemoryService` 门面与三层结构不动
- [ ] **Tool 没有被拆成多个模块**——九工具 + MCP + Registry + Sandbox 全在 `nivroos-tool`
- [ ] **`AgentLoader` / `AGENT.md` 没有被当成 Tool**——`AgentLoader.scan()` 归 core，
      `AGENT.md` 正文归 `ContextLoader`，技能不进 `ToolRegistry`、不新增 `use_skill`
- [ ] **没有启用 Spring AI 的自动 tool 执行**——`@Tool` 只用于 schema 生成；
      `AnnotatedToolAdapter` 的 `call` 只有 `ToolExecutor` 调得到；`internalToolExecutionEnabled(false)` 不变
- [ ] **白名单是"劝阻级"防线而非隔离墙**——脚本绕过网络白名单的事实已在
      contracts/builtin-tools.md 与 spec Assumptions 如实告知，不粉饰

## Post-Design Constitution Re-check

| 原则 | 复检结论（对照 data-model / contracts / quickstart） |
| --- | --- |
| I | 契约中 `ReActLoop` / `ToolExecutor` 构造签名均标注"不变"；注册表只做登记查找 |
| II | contracts/registry-and-sandbox.md 明确"Provider 层永不调用 `execute`"；quickstart §2 有 `ChatClient` grep 门禁 |
| IV | contracts/agent-context.md 的 L1 表只列 name / description / path 三项，并显式写"正文不注入"；quickstart §3-4 有人工核对项 |
| V | data-model §9 关系图显示所有工具执行都汇入既有 `ToolExecutor`；quickstart §2-⑦ 门禁断言 tool 模块内无审计写入 |
| VI | data-model §1 与 contracts/registry-and-sandbox.md §3 逐档写明匹配算法与"空 = 全拒"；接口中立性两问写进契约 |
| VII | contracts/mcp-and-notify.md §2 明确"源码不得出现 `Mono`/`Flux`/`McpAsyncClient`"；quickstart §2-④ 有 grep 门禁 |
| VIII | Project Structure 显示零新增模块；九工具 + MCP + Registry + Sandbox 同目录 |
| 依赖版本 | research §10 表逐项：两条增补均为既有 BOM 管理的同坐标再声明 |
| 凭证纪律 | contracts/mcp-and-notify.md §1/§3 复刻 `${ENV_VAR}` 纪律；notify URL 按凭证处理（不进 git / 不进对话） |
| 缓存语义 | contracts/agent-context.md §4 四档逐档写明生效时机（正文每轮 / L1 每轮 / Profile 启动 / MCP 启动） |
