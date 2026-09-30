# Implementation Plan: US-3 Memory 三层记忆（让 Agent 跨对话记得住）

**Branch**: `003-agent-memory` | **Date**: 2026-09-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-agent-memory/spec.md`

**Note**: 本 plan 由 `/speckit-plan` 生成。生成后须按 AiProgrammingGuide §3.4 做人工
review（清单见文末），review 通过后锁定；`tasks.md` 由 `/speckit-tasks` 生成。

## Summary

实现核心能力三——Memory 三层记忆（核心阶段做**会话**与**长期**两层，情景记忆留扩展
阶段）：跨对话保留状态的统一门面，让 Agent 自然记住用户偏好、项目信息、关键决策。
交付 `MemoryService` 统一入口（`loadContext` / `save` / `recall`）、可插拔的
`LongTermMemoryStore` 接口墙与三种后端（Markdown 默认 / SQLite / 自托管 Mem0）、
两个内置工具（`save_memory` / `recall_memory`），并把 ReAct 循环的 Prompt 组装收口
到记忆门面之后——`PromptBuilder` 不再分别向会话存储与长期记忆两处索取上下文。
本模块**零新增第三方依赖**；`memory_entries` 审计无关表随本模块落库（手工 schema）。

**核心阶段不做**：自动抽取、向量库/语义检索、情景记忆、Memory Wiki、记忆压缩、
知识图谱、门面层缓存、会话落库（US-5 换 SQLite）。

## Technical Context

**Language/Version**: Java 21（宪法约束）

**Primary Dependencies**: 沿用 US-1/US-2 锁定矩阵；**本模块零新增第三方坐标**——
`spring-jdbc` / `spring-boot` / `sqlite-jdbc`(test) 三者均在 Boot BOM 或根 POM
`<dependencyManagement>` 之下（research §6）；Mem0 档用 JDK `java.net.http.HttpClient`；
`nivroos-core` 零增补

**Storage**: SQLite 增第三张表 `memory_entries`（`schema.sql` 手工维护、幂等）；
Markdown 档载体 `.nivroos/memory/MEMORY.md`（`nivroos init` 已生成两分区骨架）；
Mem0 档走外部自托管 REST 服务

**Testing**: JUnit 5 + Mockito（骨架已配）；Markdown 档 `@TempDir`、SQLite 档内存库、
Mem0 档 mock `HttpClient`——**全部单测不碰网络**；Demo 二真模型人工验收

**Target Platform**: Linux / Windows，JDK 21，单二进制 fat JAR

**Project Type**: Maven 多模块（9 个固定）+ Spring Boot 单体

**Performance Goals**: 无新增目标；契约①「不缓存」的代价由 `archive-max-chars` 默认
4000 与 MEMORY.md 体量天然约束（小文件读 / 小表查 / 一次 HTTP）

**Constraints**: 同步阻塞（原则七），不引 Reactor/CompletableFuture/自建线程池；
凭证只允许 `${ENV_VAR}` 占位（双通道）；异常不吞（读写**区分语义**，见下）；core 仍
零 Spring AI 依赖（可 grep 验证）

**Scale/Scope**: 单部署长期记忆量级数十~数百条；归档区注入上限 4000 字符；核心区无上限

## 模块落位（含对颗粒度文档 §3.1 的修正，用户 2026-09-30 裁决）

| 位置 | 交付物 |
| --- | --- |
| `nivroos-core` | `MemoryScope`、`LongTermMemoryStore`（接口）、`MemoryService`（具体门面类） |
| `nivroos-memory` | `MarkdownMemoryStore`、`SqliteMemoryStore`、`Mem0MemoryStore`、`MemoryTools`、`MemoryProperties`、`MemoryConfiguration` |
| `nivroos-storage` | 无代码交付；`schema.sql` 中 `memory_entries` 建表语句（**位于 `nivroos-boot/src/main/resources/schema.sql`**，同 US-1/US-2 先例） |
| `nivroos-cli` | `ChatCommand` 改造（**前序改造点**）；`pom.xml` 增 `nivroos-memory` |
| `nivroos-boot` | `application.yml` 增 `memory.*` 段 |

> **修正说明（软门禁 1 登记）**：颗粒度文档 §3.1 把九项交付物全列在 `nivroos-memory`
> 名下。本 plan 按用户裁决把其中三项上移 `nivroos-core`，原因与备选方案见
> [research.md §6.1](./research.md)：`PromptBuilder`（在 core）必须持有 `MemoryService`
> 而 `nivroos-memory` 已依赖 `nivroos-core`，反向引用构成 Maven 循环依赖；仓库既有
> `LlmCallStore`（core 接口）/ `JpaLlmCallStore`（storage 实现）先例即此形态。
> **零新增公开类型名**（`MemoryService` 取具体类，不引入 `DefaultMemoryService`）。
> 文档侧待同步：`docs/us/us3-memory.md` §3.1 与 CLAUDE.md 模块结构表的 `nivroos-memory`
> 行（可用 `/module-doc-gen` 重生成，本模块不手改生成物）。

## 前序改造点（颗粒度文档 §3.2 已明确授权，软门禁例外条件成立）

1. **`PromptBuilder`**（US-2 已交付，`nivroos-core/src/main/java/com/nivroos/core/react/PromptBuilder.java`）
   构造签名扩展，注入 `MemoryService`，`build` 的「Memory 注入」占位点替换为
   `memoryService.loadContext(session)`；`PromptBuilderTest` 同步更新（mock
   `MemoryService`）。
   **含一处职责迁移（research §1）**：历史截断（`truncateHistory`，保留最近
   `max_turns * 2` 条）从 `PromptBuilder` 迁入 `MemoryService` 的会话历史委托路径。
   `max_history_turns` 语义不变（仍从 `ProfileContext.current()` 读）。
2. **`ChatCommand`**（US-2 已交付，`nivroos-cli/src/main/java/com/nivroos/cli/ChatCommand.java`）
   工具池 `Map` 增加 `save_memory` / `recall_memory`，构造注入 `MemoryService`。

**连带影响**：US-2 的 `PromptBuilderTest` 中历史截断用例迁为 `MemoryServiceTest` 的
截断用例（断言逻辑不丢，只换宿主）；`PromptBuilderTest` 改为断言「system 消息 +
委托 `loadContext`」。§3.2-1 的「PromptBuilderTest 同步更新」已授权此改动。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查点 | 结论 |
| --- | --- | --- |
| I 自实现 ReAct Loop | 不新增循环逻辑；`MemoryService` 只用 JDK + core 既有类型，core 仍零 Spring AI | PASS |
| II 禁自动执行 | `MemoryTools` 是手写 `NivroTool`（**不标 `@Tool`**），由 `ToolExecutor` 调度；不回退 US-1 的自动执行开关 | PASS |
| III Provider 显式映射 | 不涉及（US-1 已保证） | PASS |
| IV 一目录一 Agent / Skill 渐进披露 | 不改 Agent 目录结构与 Skill 机制；`AGENT.md` 仅在 `tools:` 按名引用两个工具，不进 `ToolRegistry` 前置逻辑 | PASS |
| V 审计 Day One | `save_memory` / `recall_memory` 走既有 `ToolExecutor` → `tool_invocations`（成败都落）；**本模块不新增审计路径** | PASS |
| VI Sandbox 接口先行 | `MEMORY.md` 路径由系统固定，不经 `FileTools` 路径白名单（FR-015、颗粒度 §2.2-5）；`Sandbox` 接口与 `WhitelistSandbox` 本模块不改不拆 | PASS |
| VII 同步执行 | 全程同步阻塞；Markdown 写入用进程内互斥（虚拟线程并发下不丢写），不引异步 | PASS |
| VIII Tool 三合一 | `MemoryTools` 落 `nivroos-memory`（能力三自有），不新增模块、不拆 `nivroos-tool` | PASS |
| IX 核心能力优先 | 自动抽取 / 向量 / 情景记忆 / 压缩 / 知识图谱 / 缓存 / 会话落库 **全部不做** | PASS |
| X 每 US 可演示 | Demo 二（跨对话记偏好）+ 后端切换验证；Mem0 档列人工项 | PASS |
| 模块结构 9 个固定 | 涉及 core / memory / cli / boot 四个既有模块，**零新增零拆分** | PASS |
| 依赖版本约束 | 零新增第三方坐标（三者均在既有 BOM 管理下） | PASS |
| SLF4J 日志纪律 | 读取失败记 WARN；无 `System.out` | PASS |

## Project Structure

### Documentation (this feature)

```text
specs/003-agent-memory/
├── plan.md              # 本文件（/speckit-plan 产出）
├── spec.md              # /speckit-specify 产出（含 1 条 Clarifications）
├── research.md          # Phase 0 产出：职责边界/数据访问/Mem0 契约/解析格式/落位修正等 10+1 项决策
├── data-model.md        # Phase 1 产出：MemoryScope/Store/三后端/门面/配置/工具/装配
├── quickstart.md        # Phase 1 产出：门禁命令 + Demo 二人工验收 + 后端切换
├── contracts/           # Phase 1 产出：memory-tools.md（模型可见接口） / memory-config.md（部署方接口）
├── checklists/
│   └── requirements.md  # /speckit-specify 产出（12/12 通过）
└── tasks.md             # Phase 2 产出（/speckit-tasks 命令，本命令不生成）
```

### Source Code (repository root)

```text
nivroos-core/
└── src/main/java/com/nivroos/core/
    ├── memory/                              # 【新增】能力三抽象层（落位修正，见上）
    │   ├── MemoryScope.java                 # CORE / ARCHIVAL 枚举
    │   ├── LongTermMemoryStore.java         # 接口墙：append / load / recallByKeyword
    │   └── MemoryService.java               # 统一门面（具体类）：loadContext / save / recall
    └── react/PromptBuilder.java             # 【改造点①】注入 MemoryService；截断迁出

nivroos-core/src/test/java/com/nivroos/core/
    ├── memory/MemoryServiceTest.java        # 【新增】拼接顺序 / 截断迁移 / 读写区分失败语义
    └── react/PromptBuilderTest.java         # 【改造点①】system + 委托 loadContext

nivroos-memory/
├── pom.xml                                  # +spring-jdbc / spring-boot；test +sqlite-jdbc
└── src/main/java/com/nivroos/memory/
    ├── MarkdownMemoryStore.java             # 默认后端（.nivroos/memory/MEMORY.md，宽容解析）
    ├── SqliteMemoryStore.java               # JdbcTemplate + memory_entries
    ├── Mem0MemoryStore.java                 # 自托管 REST（JDK HttpClient，构造注入便于 mock）
    ├── MemoryTools.java                     # save_memory / recall_memory（手写 NivroTool）
    ├── MemoryProperties.java                # memory.* 绑定 + 双通道凭证校验
    └── MemoryConfiguration.java             # 按 backend 显式 switch 装配

nivroos-memory/src/test/java/com/nivroos/memory/
    ├── MarkdownMemoryStoreTest.java         # 含关键回归 load_coreSectionNeverTruncated / load_rereadsFileAfterAppend
    ├── SqliteMemoryStoreTest.java
    ├── Mem0MemoryStoreTest.java             # mock HttpClient，不碰网络
    ├── MemoryToolsTest.java                 # 「无匹配」文案 / 失败上抛 / 缺参
    └── MemoryPropertiesTest.java            # 明文拒绝 / 占位符未解析 / 非法 backend

nivroos-cli/
├── pom.xml                                  # +nivroos-memory
└── src/main/java/com/nivroos/cli/ChatCommand.java   # 【改造点②】注入门面 + 工具池扩为三项

nivroos-boot/src/main/resources/
├── application.yml                          # +memory.backend / archive-max-chars / mem0 注释占位
└── schema.sql                               # +memory_entries 建表（幂等 CREATE TABLE IF NOT EXISTS）
```

**Structure Decision**: 零新增模块。core 内新增 `memory` 包（三个类型：抽象 + 门面），
`nivroos-memory` 承接三个后端实现、两个工具、配置绑定与装配。依赖方向恒为
`memory → core`（单向），`cli → memory`（构造 `MemoryTools`），`boot` 已依赖 memory。
键字面量与工具契约逐字对齐 [contracts/](./contracts/)；数据形状见
[data-model.md](./data-model.md)；验证路径见 [quickstart.md](./quickstart.md)。

## Complexity Tracking

无宪法违规，本节不适用。（唯一偏离是颗粒度文档 §3.1 的模块列，属文档级修正而非宪法
违规——9 模块结构未变，已在「模块落位」节登记并给出裁决依据。）

## 人工 Review 清单（AiProgrammingGuide §3.4，review 通过后本 plan 锁定）

- [ ] **Memory 没有被简化成与 Session 合并**——`MemoryService` 是独立门面，会话历史
      经 `SessionManager` 委托、长期记忆经 `LongTermMemoryStore` 委托，两者不混
- [ ] **Tool 没有被拆成多个模块**——`MemoryTools` 落 `nivroos-memory`（能力三自有），
      `nivroos-tool` 不因本模块新增文件
- [ ] **`AgentLoader` / `AGENT.md` 没有被当成 Tool**——本模块不改 `AgentLoader`；
      `AGENT.md` 只在 `tools:` 里按名引用，不注册进任何工具表
- [ ] **没有启用 Spring AI 的自动 tool 执行**——`MemoryTools` 不标 `@Tool`，
      `nivroos-memory` 不引 Spring AI 依赖，`core` 仍零 Spring AI

## Post-Design Constitution Re-check

| 原则 | 复检结论 |
| --- | --- |
| I | data-model / contracts 无 Spring AI 引用；`MemoryService` 只用 JDK + core 类型 |
| II | `MemoryTools` 手写 Schema（contracts/memory-tools.md 逐字落地），唯一执行路径仍是 `ToolExecutor` |
| V | 工具契约表明确「本模块不新增审计写入」，成败两条路径均由既有 `ToolExecutor` 覆盖 |
| VI | `Sandbox` 接口/`WhitelistSandbox` 未被本模块触碰；`MEMORY.md` 路径由系统固定（FR-015）非用户可指定路径 |
| VII | 契约与数据模型中无 Reactor / CompletableFuture / 线程池；Markdown 写入用 `ReentrantLock` |
| 模块固定 | 未新增模块；前序改造点限于已授权的 `PromptBuilder` / `ChatCommand` |
| 依赖版本 | research §6 表逐项已核实均在 Boot BOM 或根 POM `<dependencyManagement>` 之下 |
| 凭证纪律 | contracts/memory-config.md 4 条规则逐条复刻 US-1 双通道；无明文 key |
