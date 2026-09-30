---
description: "Task list for US-3 Memory 三层记忆（让 Agent 跨对话记得住）"
---

# Tasks: US-3 Memory 三层记忆（让 Agent 跨对话记得住）

**Input**: Design documents from `/specs/003-agent-memory/`

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/、quickstart.md、模块执行颗粒度文档 `docs/us/us3-memory.md`

**Tests**: 显式要求——颗粒度文档 §4 定义 7 个测试类与验收点映射，harness 先行
（测试任务先于实现任务，先写、先失败）。

**Organization**: 任务按 user story 分组（US1/US2/US3）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 任务归属的 user story（US1/US2/US3）
- 描述含精确文件路径

**落位提示（plan「模块落位」节，用户 2026-09-30 裁决）**：`MemoryScope` /
`LongTermMemoryStore` / `MemoryService` 落 `nivroos-core`；三个后端实现 +
`MemoryTools` + 配置 + 装配落 `nivroos-memory`。与颗粒度文档 §3.1 的模块列不同，
已在 plan 登记。

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖与配置骨架，全部 story 的前置

- [X] T001 [P] `nivroos-memory/pom.xml` 增补依赖：`org.springframework:spring-jdbc`（`JdbcTemplate`）、`org.springframework.boot:spring-boot`（`@ConfigurationProperties`）；test 作用域增 `org.xerial:sqlite-jdbc`（内存库单测）——三者均在 Boot BOM / 根 POM `<dependencyManagement>` 之下，**零新增第三方坐标**（research §6）
- [X] T002 [P] `nivroos-cli/pom.xml` 增补 `com.nivroos:nivroos-memory` 依赖（`ChatCommand` 构造 `MemoryTools` + 注入 `MemoryService`，T016 依赖此项）
- [X] T003 [P] `nivroos-boot/src/main/resources/application.yml` 增 `memory` 段：`backend: markdown`、`archive-max-chars: 4000`，mem0 段以注释留位（contracts/memory-config.md 逐字，**`url` 取 `http://localhost:8000`**——颗粒度文档 §3.3 的 8080 是过期默认值，research §3 核实 OSS 自托管默认端口为 8000）

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 记忆抽象层与装配骨架，阻塞全部 story

**⛔ CRITICAL**: 本阶段完成前不得开始任何 user story 实现

- [X] T004 [P] `MemoryScope` 枚举（CORE / ARCHIVAL）in `nivroos-core/src/main/java/com/nivroos/core/memory/MemoryScope.java`（data-model §1）
- [X] T005 [P] `LongTermMemoryStore` 接口 in `nivroos-core/src/main/java/com/nivroos/core/memory/LongTermMemoryStore.java`——三方法签名逐字：`append(String, MemoryScope)` → void、`load()` → String、`recallByKeyword(String)` → `List<String>`；类注释写明四个行为契约（不缓存 / 核心区永不截断 / 分区由调用方给定 / 关键词检索）
- [X] T006 `MemoryService` 统一门面 in `nivroos-core/src/main/java/com/nivroos/core/memory/MemoryService.java`——**具体类不开接口**（plan Structure Decision）；`loadContext(Session)` 返回 `[截断后的会话历史 …] + [一条长期记忆 system 消息]`（**拼接顺序：历史在前、长期记忆在后**，research §1）；**US-2 的 `truncateHistory` 逻辑随本任务迁入**（`max_turns * 2`，`max_history_turns` 仍从 `ProfileContext.current()` 读）；`save` 失败**上抛**、读取长期记忆失败**记 WARN 并继续**（spec Clarifications、FR-012 / FR-021）；依赖 T004、T005
- [X] T007 [P] `MemoryProperties` in `nivroos-memory/src/main/java/com/nivroos/memory/MemoryProperties.java`——`memory.backend` / `memory.archive-max-chars` / `memory.mem0.url` / `memory.mem0.api-key`（data-model §7）；凭证双通道校验 4 条规则**逐条复刻 `ProviderProperties` 先例**（明文拒绝按原始值判定、`nivroos-secrets.yml` 放行、占位符未解析报错指明变量名、构造期校验）
- [X] T008 `MemoryConfiguration` 装配 in `nivroos-memory/src/main/java/com/nivroos/memory/MemoryConfiguration.java`——`@Configuration(proxyBeanMethods = false)` + `@EnableConfigurationProperties(MemoryProperties.class)`；**构造期** `properties.validate(environment)`；按 `memory.backend` **显式 switch** 装配（本任务先落 `markdown` 臂，非法取值启动即报错并列出合法取值）；出 `MemoryService` Bean；依赖 T006、T007

**Checkpoint**: 抽象层与默认装配就绪，user story 实现可开始

## Phase 3: User Story 1 - Agent 主动记住偏好并在后续会话复现 (Priority: P1) 🎯 MVP

**Goal**: 写入链路 + 每轮注入链路闭环——Agent 主动记住，重启进程后仍能复现

**Independent Test**: `MarkdownMemoryStoreTest` / `MemoryServiceTest` / `MemoryToolsTest` / `PromptBuilderTest` 全绿（mock，无网络）；Demo 二 2.1~2.2 真模型人工验收（SC-001 / SC-003）

### Tests for User Story 1（先写，先失败）

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] T009 [P] [US1] 编写 `MarkdownMemoryStoreTest` in `nivroos-memory/src/test/java/com/nivroos/memory/MarkdownMemoryStoreTest.java`（`@TempDir` 不碰真实 `.nivroos/`）——颗粒度文档 §4.3 关键回归**原样落地**：`load_rereadsFileAfterAppend`（不缓存，save 后下一次 load 立即读到）；另含追加到指定分区、核心区全量返回、文件不存在 / 空文件视为空记忆（FR-020）、分区 header 缺失时按需补建
- [X] T010 [P] [US1] 编写 `MemoryServiceTest` in `nivroos-core/src/test/java/com/nivroos/core/memory/MemoryServiceTest.java`——**loadContext 拼接顺序（会话历史在前、长期记忆在后）**；**US-2 从 `PromptBuilderTest` 迁入的历史截断用例**（保留最近 `max_turns * 2` 条，断言逻辑逐条保真）；门面委托正确（历史来自 `SessionManager`、长期记忆来自 `LongTermMemoryStore`）；**读写区分失败语义**（save 失败上抛；load 失败记 WARN 并按无长期记忆继续、记忆内容为空但历史仍在）
- [X] T011 [P] [US1] 编写 `MemoryToolsTest` in `nivroos-memory/src/test/java/com/nivroos/memory/MemoryToolsTest.java`——`save_memory` 契约表逐条（contracts/memory-tools.md）：`content`+`scope: CORE` → 核心区、省略 `scope` → **默认 ARCHIVAL**（FR-006）、`content` 缺失/空 → 失败结果、`scope` 非法 → 失败且**不静默按默认处理**、存储 IO 失败 → 上抛；**另含审计落库断言**：工具经 `ToolExecutor` 执行后 `ToolInvocationStore` 收到 `success=true` 记录（FR-011 / SC-005 的自动化承重点）
- [X] T012 [P] [US1] 编写/改造 `PromptBuilderTest` in `nivroos-core/src/test/java/com/nivroos/core/react/PromptBuilderTest.java`——颗粒度文档 §4.3 关键回归**原样落地**：`build_includesMemoryContent`（Prompt 四部分含 Memory 注入，记忆内容出现在请求消息中）；`PromptBuilder` 改为断言「system 消息 + 委托 `loadContext`」（截断断言已迁往 T010）

### Implementation for User Story 1

- [X] T013 [US1] 实现 `MarkdownMemoryStore` in `nivroos-memory/src/main/java/com/nivroos/memory/MarkdownMemoryStore.java`——载体 `.nivroos/memory/MEMORY.md`（路径系统固定，**不走 `FileTools` 路径白名单**，FR-015）；按 `## 核心记忆` / `## 归档记忆` 定位，追加时**每条独立写** `### <yyyy-MM-dd>` header 再写 `- <内容>`（同日多条各带 header，**不合并**——§2.3 `appendToSection(scope, dateHeader + content)` 与 §4.3 截断断言依赖此格式；原「复用或新建」措辞与 §2.3 冲突，2026-09-30 按 §2.3 修正）；**宽容解析**（不引 Markdown 库）；核心区全量、**归档区按 `archive-max-chars` 截断保留最新**；写入走进程内互斥（虚拟线程并发不丢写）；**不缓存，每次重读**——依赖 T005、T009
- [X] T014 [US1] 实现 `MemoryTools` in `nivroos-memory/src/main/java/com/nivroos/memory/MemoryTools.java`——**手写内部类 + 手写 JSON Schema 字符串（同 `HttpTools` 写法），不标 `@Tool`**（用户裁决，research §8）；构造持 `MemoryService`；本任务落 `save_memory`；**不做任何审计写入**（由既有 `ToolExecutor` 覆盖）——依赖 T006、T011
- [X] T015 [US1] **前序改造点①**：`PromptBuilder` 构造签名扩展注入 `MemoryService`，`build` 的「Memory 注入」占位点替换为 `memoryService.loadContext(session)`，**`truncateHistory` 迁出** in `nivroos-core/src/main/java/com/nivroos/core/react/PromptBuilder.java`（颗粒度文档 §3.2-1 已授权）——依赖 T006、T012
- [X] T016 [US1] **前序改造点②**：`ChatCommand` 构造注入 `MemoryService`，工具池由 `Map.of("http_get", …)` 扩为三项（增 `save_memory` / `recall_memory`）in `nivroos-cli/src/main/java/com/nivroos/cli/ChatCommand.java`（颗粒度文档 §3.2-2 已授权）——依赖 T002、T014

**Checkpoint**: P1 闭环可跑——mock 测试全绿，Demo 二 2.1~2.2 可人工验证

## Phase 4: User Story 2 - Agent 按关键词检索历史归档记忆 (Priority: P2)

**Goal**: 归档区截断契约 + 关键词检索闭环；核心区不参与检索

**Independent Test**: `MarkdownMemoryStoreTest` / `MemoryToolsTest` 扩展用例全绿；Demo 二 2.3 真模型人工验收（SC-002 / SC-006）

### Tests for User Story 2（先写，先失败）

- [X] T017 [P] [US2] 扩展 `MarkdownMemoryStoreTest` in `nivroos-memory/src/test/java/com/nivroos/memory/MarkdownMemoryStoreTest.java`——颗粒度文档 §4.3 关键回归**原样落地**：`load_coreSectionNeverTruncated`（归档区塞入 200 条后，核心区依然完整返回、归档区确实被截断）；另含**截断保留最新、丢弃最旧**（spec Assumptions）、`recallByKeyword` 命中归档区、关键词只出现在核心区时返回**空列表**（FR-009）、无命中返回空列表
- [X] T018 [P] [US2] 扩展 `MemoryToolsTest` in `nivroos-memory/src/test/java/com/nivroos/memory/MemoryToolsTest.java`——`recall_memory` 契约表逐条（contracts/memory-tools.md）：命中 → 命中行以换行连接、无命中 → 返回**「无匹配」文案**（不返回编造内容、不返回空串）、`query` 缺失/空 → 失败结果、只命中核心区 → 不返回

### Implementation for User Story 2

- [X] T019 [US2] `MarkdownMemoryStore` 增 `recallByKeyword`（归档区内 `String.contains` 行匹配，核心区不参与）in `nivroos-memory/src/main/java/com/nivroos/memory/MarkdownMemoryStore.java`——**归档区截断已归 T013（属 `load()` 契约，不在此重复实现）**；依赖 T013、T017
- [X] T020 [US2] `MemoryTools` 增 `recall_memory`（委托 `memoryService.recall`；无命中时由工具层产出「无匹配」文案，store 不产出面向用户措辞）in `nivroos-memory/src/main/java/com/nivroos/memory/MemoryTools.java`——依赖 T014、T018

**Checkpoint**: 归档区可检索、可截断，核心区完整性有回归守卫

## Phase 5: User Story 3 - 运维方切换长期记忆的存储形态 (Priority: P3)

**Goal**: 接口墙价值兑现——换后端只改 `memory.backend` 一行，门面及以上零改动

**Independent Test**: `SqliteMemoryStoreTest` / `Mem0MemoryStoreTest` / `MemoryPropertiesTest` 全绿；Demo 二后端切换验证（SC-004）；Mem0 档真实联调列人工项

### Tests for User Story 3（先写，先失败）

- [X] T021 [P] [US3] 编写 `SqliteMemoryStoreTest` in `nivroos-memory/src/test/java/com/nivroos/memory/SqliteMemoryStoreTest.java`——测试内自建 `DataSource`（临时/内存库）+ 执行 `memory_entries` 建表；三方法在 SQLite 档上与 markdown 档**同语义**（断言逐条对齐 T009/T017 的契约）；**不碰真实 `.nivroos/nivroos.db`**
- [X] T022 [P] [US3] 编写 `Mem0MemoryStoreTest` in `nivroos-memory/src/test/java/com/nivroos/memory/Mem0MemoryStoreTest.java`——构造注入 `HttpClient`，**mock `HttpClient` + `HttpResponse`**（与 `HttpToolsTest` 逐字同法，**不碰真实网络**）：`append` → `POST /memories`、`load` → `GET /memories`、`recallByKeyword` → `POST /search`；鉴权头与 URL **不加 `/v1`**（research §3）；非 2xx 与 IO 异常路径。**注**：颗粒度文档 §4.2 把「凭证缺失报错清晰」挂在本类，实际由 T023 承接（凭证校验归 `MemoryProperties`，store 只管 HTTP 映射），类注释注明此映射
- [X] T023 [P] [US3] 编写 `MemoryPropertiesTest` in `nivroos-memory/src/test/java/com/nivroos/memory/MemoryPropertiesTest.java`——`memory.backend` 默认 `markdown`、**未配置时 `archive-max-chars` 默认 `4000`**（FR-004）、非法 backend 取值报错列出合法值、`archive-max-chars` ≤ 0 报错、**mem0 明文 api-key 被拒**（报错文案逐字同 contracts/memory-config.md）、**占位符未解析报错指明变量名**、`backend=mem0` 而 url/api-key 缺失报错

### Implementation for User Story 3

- [X] T024 [US3] `nivroos-boot/src/main/resources/schema.sql` 追加 `memory_entries` 建表语句（幂等 `CREATE TABLE IF NOT EXISTS`，DDL 逐字取 data-model §4；**不使用 `ddl-auto`**）
- [X] T025 [US3] 实现 `SqliteMemoryStore` in `nivroos-memory/src/main/java/com/nivroos/memory/SqliteMemoryStore.java`——`JdbcTemplate`（`spring-jdbc`）+ 注入 `DataSource`；**不建 JPA 实体、不建 Repository**；`load` 的核心区全量 / 归档区按条数倒取后再恢复正序；`recallByKeyword` 的 `LIKE` 通配符在上层转义——依赖 T005、T021、T024
- [X] T026 [US3] 实现 `Mem0MemoryStore` in `nivroos-memory/src/main/java/com/nivroos/memory/Mem0MemoryStore.java`——JDK `HttpClient` **构造注入便于 mock**；`X-API-Key` 鉴权；OSS 自托管**无 `/v1` 前缀**；类注释登记三条语义差异（`POST /memories` 做 LLM 抽取故 `append` 非逐字、`search` 是语义检索、精确路径以部署实例 `/openapi.json` 为准）——依赖 T005、T022
- [X] T027 [US3] `MemoryConfiguration` 增 `sqlite` / `mem0` 装配臂 in `nivroos-memory/src/main/java/com/nivroos/memory/MemoryConfiguration.java`——sqlite 注入 `DataSource`；mem0 注入 `HttpClient` + url + **`environment.resolvePlaceholders(...)` 解析后的** api-key（Boot 3.5 绑定器保持 `${...}` 字面量，必须显式解析）——依赖 T008、T025、T026

**Checkpoint**: 三档后端全部可装配，换一行配置即切换（SC-004）

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁与收尾（module-dev 步骤 7 的 DoD 证据）

- [X] T028 全量门禁：`mvn clean verify` 全绿（含 US-1/US-2 全部测试回归绿——跨模块契约证据）；模块级跑测**必须带 `-am`**；格式问题只许 `mvn spotless:apply` 修复
- [X] T029 依赖方向与全局不变量核验：grep 确认 `nivroos-core` 无 Spring AI import（原则一）、`nivroos-memory` 不依赖 `nivroos-storage`、无 Reactor / `CompletableFuture` / 自建线程池（原则七）、无明文 key（全 `${ENV_VAR}` 占位）、模块数仍为 9
- [X] T030 交付物存在性核对（颗粒度文档 §3 与 plan「模块落位」逐项）+ 输出验收报告与变更总结（Demo 二 + 后端切换人工项清单交给用户）

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup，**阻塞全部 story**
- **User Stories (Phase 3~5)**: 依赖 Foundational；US1 → US2 → US3 顺序推进
- **Polish (Phase 6)**: 依赖全部 story

### User Story Dependencies

- **US1 (P1)**: Foundational 后即可开始；增量 = Markdown 档 + 写入工具 + 注入链路（含两处前序改造点）
- **US2 (P2)**: 依赖 US1 的 `MarkdownMemoryStore` 与 `MemoryTools`；增量 = 归档区截断 + `recallByKeyword` + `recall_memory`
- **US3 (P3)**: 依赖 US1 的装配骨架（`MemoryConfiguration` markdown 臂）；增量 = SQLite / Mem0 两档 + 建表 + 配置校验用例

### Within Each User Story

- 测试任务 MUST 先于实现任务（harness 先行，先失败再实现）
- 抽象（`MemoryScope` / `LongTermMemoryStore` / `MemoryService`）先于一切实现
- 实现完成即跑本模块测试，红了当场修

### Parallel Opportunities

- Setup：T001 ∥ T002 ∥ T003
- Foundational：T004 ∥ T005 ∥ T007；T006 依赖 T004+T005，T008 依赖 T006+T007
- US1：T009~T012 四个测试类并行；T013 依赖 T009、T014 依赖 T011、T015 依赖 T012、T016 依赖 T014
- US2：T017 ∥ T018；T019 依赖 T017，T020 依赖 T018
- US3：T021 ∥ T022 ∥ T023；T025 依赖 T021+T024，T026 依赖 T022，T027 依赖 T025+T026

## Parallel Example: User Story 1

```bash
# 四个测试类先行并行（harness 先行，先失败）：
Task: "T009 MarkdownMemoryStoreTest" / "T010 MemoryServiceTest"
Task: "T011 MemoryToolsTest" / "T012 PromptBuilderTest"

# 实现可并行部分（不同文件、互不依赖）：
Task: "T013 MarkdownMemoryStore" / "T014 MemoryTools"
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1: Setup（依赖与配置）
2. 完成 Phase 2: Foundational（抽象层 + 门面 + 默认装配）
3. 完成 Phase 3: US1（4 测试先行 → 实现 → Demo 二 2.1~2.2 人工验证）
4. **STOP and VALIDATE**: mock 测试全绿 + 「记住 → 跨会话复现」跑通
5. US2（归档区检索）→ US3（后端切换）逐故事推进

### Incremental Delivery

1. Setup + Foundational → 门禁保持绿
2. US1 → 写入 + 注入闭环（MVP，Demo 二 2.1~2.2）
3. US2 → 归档区截断与检索（Demo 二 2.3）
4. US3 → 三档后端 + 配置校验 → 人工项（后端切换验证 / Mem0 联调）

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- 三个关键回归测试（T009 `load_rereadsFileAfterAppend`、T012 `build_includesMemoryContent`、T017 `load_coreSectionNeverTruncated`）断言与颗粒度文档 §4.3 **逐条保真**
- 两处前序改造点（T015 / T016）由颗粒度文档 §3.2 明确授权，属软门禁例外
- 测试方法名英文 + `@DisplayName` 中文（文档原文进 `@DisplayName`）；关键注释中英并列
- `MemoryServiceTest` 落在 `nivroos-core`（门面在 core，见 plan 落位表）
- 全程不自动 commit / push，同步时机由用户决定
