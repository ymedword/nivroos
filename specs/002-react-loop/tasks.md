---
description: "Task list for US-2 ReAct 循环（Agent 大脑）"
---

# Tasks: US-2 ReAct 循环（Agent 大脑）

**Input**: Design documents from `/specs/002-react-loop/`

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/、quickstart.md、模块执行颗粒度文档 `docs/us/us2-react.md`

**Tests**: 显式要求——颗粒度文档 §4 定义 10 个测试类与验收点映射，harness 先行
（测试任务先于实现任务，先写、先失败）。

**Organization**: 任务按 user story 分组（US1/US2/US3）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 任务归属的 user story（US1/US2/US3）
- 描述含精确文件路径

## Phase 1: Setup（前序改造点落地，颗粒度文档 §3.5 已授权）

**Purpose**: US-1 契约的两处扩展，所有 story 的前置

- [X] T001 改造点 1：`LlmCallStore.record` 增加 `sessionId` 参数 in `nivroos-core/src/main/java/com/nivroos/core/provider/LlmCallStore.java` + `nivroos-storage/src/main/java/com/nivroos/storage/llm/JpaLlmCallStore.java`（实体 setSessionId）
- [X] T002 改造点 1 接线：`SpringAiProviderService` 从 `ChatRequest.sessionId()` 传入审计 in `nivroos-provider/src/main/java/com/nivroos/provider/SpringAiProviderService.java`；同步更新 US-1 的 `SpringAiProviderServiceTest` / `ProviderSmokeIT` verify 断言（参数列表加一）
- [X] T003 [P] 改造点 2：`Profile` 增补 `tools`（`List<String>`）与 `settings`（maxIterations=10 / maxHistoryTurns=20，带默认）in `nivroos-core/src/main/java/com/nivroos/core/profile/Profile.java`

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 会话与审计契约，阻塞全部 story

**⛔ CRITICAL**: 本阶段完成前不得开始任何 user story 实现

- [X] T004 [P] `Session` + `SessionManager` 接口 + `InMemorySessionManager` in `nivroos-core/src/main/java/com/nivroos/core/session/`（session_id 公式 channel:userId:profileName **仅在此处拼接**，data-model §1）
- [X] T005 [P] `ProfileContext`（`ThreadLocal<Profile>`，get/set/clear）in `nivroos-core/src/main/java/com/nivroos/core/profile/ProfileContext.java`
- [X] T006 [P] `ToolInvocationStore` 接口 in `nivroos-core/src/main/java/com/nivroos/core/provider/ToolInvocationStore.java`（record 参数：sessionId/toolName/inputJson/resultJson/success/errorMessage/durationMs，依赖倒置同 LlmCallStore 先例）

**Checkpoint**: 会话与审计契约就绪

## Phase 3: User Story 1 - CLI 多轮对话完成工具任务 (Priority: P1) 🎯 MVP

**Goal**: ReAct 循环主链路 + CLI 可见入口；Agent 自主调用工具完成任务

**Independent Test**: 9 个测试类全绿（mock，无网络）；Demo 一人工验收（SC-001）

### Tests for User Story 1（先写，先失败）

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] T007 [P] [US1] 编写 `ReActLoopTest` in `nivroos-core/src/test/java/com/nivroos/core/react/ReActLoopTest.java`——颗粒度文档 §4.3 关键回归**原样落地**：`run_withToolCall_convergesAfterToolResult`（循环收敛）、`run_neverConverging_stopsAtMaxIterations`（迭代上限精确轮数，返回固定文案 per spec Clarifications）；另含无工具单轮返回、每轮消息累积、**一次响应多个工具调用时按顺序执行（FR-009，可用执行顺序记录断言）**
- [X] T008 [P] [US1] 编写 `PromptBuilderTest` in `nivroos-core/src/test/java/com/nivroos/core/react/PromptBuilderTest.java`——四部分齐全（system prompt 含正文+Bootstrap+末尾日期时间）、历史按 max_history_turns 截断、工具列表经翻译传入
- [X] T009 [P] [US1] 编写 `ToolExecutorTest` in `nivroos-core/src/test/java/com/nivroos/core/react/ToolExecutorTest.java`——颗粒度文档 §4.3 关键回归**原样落地**：`execute_sandboxRejected_recordsFailureAudit`（Sandbox 拒绝 → success=false + error_message 落库）；另含工具存在执行成功、工具不存在清晰错误、执行异常失败留痕
- [X] T010 [P] [US1] 编写 `AgentServiceTest` in `nivroos-core/src/test/java/com/nivroos/core/react/AgentServiceTest.java`——颗粒度文档 §4.3 关键回归**原样落地**：`process_whenLoopThrows_clearsProfileContext`（异常路径 finally 清理）；另含正常路径设置/清理、ProfileRegistry 取 Profile
- [X] T011 [P] [US1] 编写 `SessionManagerTest` in `nivroos-core/src/test/java/com/nivroos/core/session/SessionManagerTest.java`——session_id 公式唯一、消息追加、同一身份复用会话
- [X] T012 [P] [US1] 编写 `AgentLoaderTest` in `nivroos-core/src/test/java/com/nivroos/core/loader/AgentLoaderTest.java`——frontmatter 解析（provider/tools/settings 默认值）、provider 缺失报错清晰
- [X] T013 [P] [US1] 编写 `ContextLoaderTest` in `nivroos-core/src/test/java/com/nivroos/core/context/ContextLoaderTest.java`——正文+Bootstrap 拼接、末尾日期时间、**无缓存回归**（改文件后下一次 build 立即读到）、Bootstrap 缺失 WARN 不阻断
- [X] T014 [P] [US1] 编写 `HttpToolsTest` in `nivroos-tool/src/test/java/com/nivroos/tool/HttpToolsTest.java`——白名单通过/拒绝、请求 URL/方法正确（HTTP 客户端可注入 mock）
- [X] T015 [P] [US1] 编写 `WhitelistSandboxTest` in `nivroos-tool/src/test/java/com/nivroos/tool/sandbox/WhitelistSandboxTest.java`——域名精确匹配/通配符匹配/拒绝；FILE/SHELL case 行为

### Implementation for User Story 1

- [X] T016 [US1] 实现 `ContextLoader` in `nivroos-core/src/main/java/com/nivroos/core/context/ContextLoader.java`——AGENT.md 正文 + Bootstrap 三文件 + 末尾日期时间；**无缓存每次重读**（research §8）
- [X] T017 [US1] 实现 `AgentLoader` 简化版 + `ProfileRegistry` in `nivroos-core/src/main/java/com/nivroos/core/loader/AgentLoader.java` + `nivroos-core/src/main/java/com/nivroos/core/profile/ProfileRegistry.java`——单 Agent frontmatter 派生（SnakeYAML）+ 校验 + 注册
- [X] T018 [US1] 实现 `WhitelistSandbox` + `Sandbox` 接口 + `SandboxAction` + `SandboxViolationException` in `nivroos-tool/src/main/java/com/nivroos/tool/sandbox/`——HTTP 域名通配符白名单（application.yml `http.allowed_domains`）；FILE/SHELL case 留 US-4（注释注明）
- [X] T019 [US1] 实现 `HttpTools` in `nivroos-tool/src/main/java/com/nivroos/tool/HttpTools.java`——`http_get` NivroTool；execute 开头 `Sandbox.enforce(HTTP_REQUEST, url)`；JDK `java.net.http.HttpClient`（同步，超时默认 10s 可配置，research §4）；**构造函数注入 HttpClient，便于测试替换（T014 依赖此可测性）**
- [X] T020 [US1] 实现 `PromptBuilder` in `nivroos-core/src/main/java/com/nivroos/core/react/PromptBuilder.java`——四部分组装，Memory 部分占位（US-3 接入），历史截断视图（data-model §1）
- [X] T021 [US1] 实现 `ToolExecutor` in `nivroos-core/src/main/java/com/nivroos/core/react/ToolExecutor.java`——Map 工具池查找 + Sandbox 检查 + 执行 + ToolInvocationStore 写入（成功/失败/拒绝三路径，依赖 T006/T018/T019）
- [X] T022 [US1] 实现 `ReActLoop` in `nivroos-core/src/main/java/com/nivroos/core/react/ReActLoop.java`——**对照颗粒度文档 §2.3 流程图/伪代码逐块落地**（签名逐字一致）；强制结束返回固定文案（spec Clarifications）+ WARN 日志
- [X] T023 [US1] 实现 `AgentService` in `nivroos-core/src/main/java/com/nivroos/core/react/AgentService.java`——按 session.profileName 从 ProfileRegistry 取 Profile → ProfileContext.set → ReActLoop.run → finally clear
- [X] T024 [US1] 实现 `CliChannel` in `nivroos-channel-cli/src/main/java/com/nivroos/channel/cli/CliChannel.java`——stdin/stdout 交互循环、`/quit` 退出、会话维护（契约 contracts/cli-commands.md）
- [X] T025 [US1] 实现 `InitCommand` + `ChatCommand` in `nivroos-cli/src/main/java/com/nivroos/cli/InitCommand.java` + `nivroos-cli/src/main/java/com/nivroos/cli/ChatCommand.java`——init 幂等创建工作区（技术方案 §8.1 结构）、chat --profile/--message 契约；注册进既有 NivroOsCli

**Checkpoint**: 主链路可跑——mock 测试全绿，Demo 一可人工验证

## Phase 4: User Story 2 - 普通问答单轮直接返回 (Priority: P2)

**Goal**: 无工具调用时单轮收敛；同一会话多轮消息累积复用

**Independent Test**: ReActLoopTest 单轮场景 + SessionManagerTest 会话复用（T011 扩展）

### Tests for User Story 2（先写，先失败）

- [X] T026 [P] [US2] 扩展 `SessionManagerTest` in `nivroos-core/src/test/java/com/nivroos/core/session/SessionManagerTest.java`——同一 channel+user+profile 复用同一 session_id、多轮消息累积不丢失

### Implementation for User Story 2

- [X] T027 [US2] 执行 quickstart 负向验证中的单轮链路核对（`--message "你好"` 单轮返回、会话复用），记录结果进 DoD 报告（US2 实现增量为零——循环快速路径已由 US1 覆盖，本任务为验收核对）

**Checkpoint**: 快速路径验证通过

## Phase 5: User Story 3 - 会话与审计可追溯 (Priority: P3)

**Goal**: tool_invocations 落库（含 success/error_message）；双审计表 session 关联闭环

**Independent Test**: JpaToolInvocationStoreTest 全绿 + T009 审计断言（sessionId 传导）

### Tests for User Story 3（先写，先失败）

- [X] T028 [P] [US3] 编写 `JpaToolInvocationStoreTest` in `nivroos-storage/src/test/java/com/nivroos/storage/tool/JpaToolInvocationStoreTest.java`——建表可写可读、success/error_message 两列真实存在且正确落值（镜像 DDL 与 schema.sql 同步，注释注明）

### Implementation for User Story 3

- [X] T029 [US3] 实现 `ToolInvocation` 实体 + `ToolInvocationRepository` in `nivroos-storage/src/main/java/com/nivroos/storage/tool/`（九列与需求文档 §10 一致，含 success/error_message）
- [X] T030 [US3] 实现 `JpaToolInvocationStore` + `ToolInvocationStoreConfiguration` in `nivroos-storage/src/main/java/com/nivroos/storage/tool/`（实现 core 接口，装配同 LlmCallStore 先例）
- [X] T031 [US3] 在 `nivroos-boot/src/main/resources/schema.sql` 追加 `tool_invocations` 建表语句（幂等，data-model §3 DDL）

**Checkpoint**: 审计落库闭环——双表均有 session 关联

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁与收尾（module-dev 步骤 7 的 DoD 证据）

- [X] T032 全量门禁：`mvn clean verify` 全绿（含 US-1 全部测试回归绿——跨模块契约证据）；格式问题只许 `mvn spotless:apply` 修复
- [X] T033 依赖方向核验：grep 确认 nivroos-core 无 Spring AI import（宪法原则一）；本模块无新增第三方依赖（不新增 -Psecurity 抑制，有变化按既有评审流程登记）
- [X] T034 交付物存在性核对（颗粒度文档 §3 逐项）+ 全局不变量 6 条自查 + 输出验收报告与变更总结（Demo 一人工项清单交给用户）

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup，**阻塞全部 story**
- **User Stories (Phase 3~5)**: 依赖 Foundational；US1 → US2 → US3 顺序推进
- **Polish (Phase 6)**: 依赖全部 story

### User Story Dependencies

- **US1 (P1)**: Foundational 后即可开始；主链路（循环/执行/CLI）
- **US2 (P2)**: 依赖 US1（快速路径由 ReActLoop 覆盖）；增量 = 会话复用回归 + 验收核对
- **US3 (P3)**: 依赖 US1 的 ToolExecutor 接线；增量 = storage 三件套 + 建表

### Within Each User Story

- 测试任务 MUST 先于实现任务（harness 先行，先失败再实现）
- 接口/实体先于实现类；Sandbox/HttpTools 先于 ToolExecutor
- 实现完成即跑本模块测试，红了当场修

### Parallel Opportunities

- Setup：T001 ∥ T003（T002 依赖 T001）
- Foundational：T004 ∥ T005 ∥ T006
- US1：T007~T015 九个测试类全部可并行；实现 T016/T017/T018/T019 可并行（不同文件），T020/T021/T022/T023 顺序依赖，T024/T025 可并行
- US3：T028 先行；T029/T030 可并行

## Parallel Example: User Story 1

```bash
# 九个测试类先行并行（harness 先行，先失败）：
Task: "T007 ReActLoopTest" / "T008 PromptBuilderTest" / "T009 ToolExecutorTest"
Task: "T010 AgentServiceTest" / "T011 SessionManagerTest" / "T012 AgentLoaderTest"
Task: "T013 ContextLoaderTest" / "T014 HttpToolsTest" / "T015 WhitelistSandboxTest"

# 实现可并行部分：
Task: "T016 ContextLoader" / "T017 AgentLoader" / "T018 WhitelistSandbox" / "T019 HttpTools"
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1: Setup（前序改造点）
2. 完成 Phase 2: Foundational（Session/ProfileContext/审计接口）
3. 完成 Phase 3: US1（9 测试先行 → 实现 → Demo 一人工验证）
4. **STOP and VALIDATE**: mock 测试全绿 + Demo 一跑通
5. US2（会话复用）→ US3（tool_invocations 落库）逐故事推进

### Incremental Delivery

1. Setup + Foundational → 门禁保持绿
2. US1 → 循环主链路 + CLI（MVP，Demo 一）
3. US2 → 快速路径与会话复用验证
4. US3 → 审计双表闭环 → 颗粒度文档 §6 人工项验证

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- 三个关键回归测试（T007/T009/T010）断言与颗粒度文档 §4.3 **逐条保真**
- ReActLoop 实现必须对照颗粒度文档 §2.3 伪代码逐块落地（module-dev 写中门禁）
- 测试方法名英文 + `@DisplayName` 中文；关键注释中英并列
- 全程不自动 commit / push，同步时机由用户决定
