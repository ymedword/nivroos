---
description: "Task list for US-1 对接 LLM（核心能力一）"
---

# Tasks: US-1 对接 LLM（核心能力一）

**Input**: Design documents from `/specs/001-llm-provider/`

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/、quickstart.md、模块执行颗粒度文档 `docs/us/us1-provider.md`

**Tests**: 显式要求——模块执行颗粒度文档 §4「验收测试（Harness）」定义 5 个测试类
与验收点映射，harness 先行（测试任务先于实现任务，先写、先失败）。

**Organization**: 任务按 user story 分组（US1/US2/US3），每个 story 可独立实现与测试。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 任务归属的 user story（US1/US2/US3）
- 描述含精确文件路径

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖与全局配置，US1 起跑前提

- [X] T001 [P] 在 `nivroos-provider/pom.xml` 增加 `spring-ai-starter-model-deepseek` 与 `spring-ai-starter-model-openai` 依赖（版本由根 POM 的 spring-ai-bom 1.1.2 管理，不写显式版本；DeepSeek 直连、Kimi 经 OpenAI 兼容通道）
- [X] T002 [P] 在 `nivroos-boot/src/main/resources/application.yml` 增加 `nivroos.providers.deepseek` 段（`api-key: ${DEEPSEEK_API_KEY}`，禁止明文）

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: core 模块抽象与契约，所有 user story 的阻塞前置

**⛔ CRITICAL**: 本阶段完成前不得开始任何 user story 实现

- [X] T003 [P] 定义 `model` 包类型：`nivroos-core/src/main/java/com/nivroos/core/model/` 下 `Message` / `ChatRequest` / `ChatResponse` / `ToolCallRequest` / `Usage`（字段与语义见 data-model.md §4）
- [X] T004 [P] `Profile` 补充 provider 字段（`providerName` / `model` / `temperature`）in `nivroos-core/src/main/java/com/nivroos/core/profile/Profile.java`（结构定义见 data-model.md §2）
- [X] T005 [P] 异常体系：`nivroos-core/src/main/java/com/nivroos/core/provider/` 下 `ProviderException` + `ProviderNotFoundException` + `ProviderCallException`（语义见 contracts/provider-service.md）
- [X] T006 定义 `ProviderService` 接口 in `nivroos-core/src/main/java/com/nivroos/core/provider/ProviderService.java`（`ChatResponse call(Profile, ChatRequest)` + `Set<String> providerNames()`，正/负向契约见 contracts/provider-service.md）
- [X] T007 [P] 定义 `LlmCallStore` 审计写入接口 in `nivroos-core/src/main/java/com/nivroos/core/provider/LlmCallStore.java`（依赖倒置：core 持接口、storage 持实现）

**Checkpoint**: core 抽象就绪，三个 user story 可开始实现

## Phase 3: User Story 1 - 配置一个 Agent 时声明模型供应商 (Priority: P1) 🎯 MVP

**Goal**: 配置供应商（名称/凭证/模型）后，Agent 能发起一次 LLM 调用并拿到回答；配置校验清晰报错

**Independent Test**: `ProviderPropertiesTest` + `SpringAiProviderServiceTest` + `FunctionCallingAdapterTest` 全绿（mock，无网络依赖）

### Tests for User Story 1（先写，先失败）

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] T008 [P] [US1] 编写 `ProviderPropertiesTest` in `nivroos-provider/src/test/java/com/nivroos/provider/ProviderPropertiesTest.java`——验收点：明文 api-key 拒绝并指明键路径（FR-003）、同名供应商拒绝（FR-009）、`${ENV}` 未设置报错指明缺失项（FR-007）、base-url 可选、引用未注册供应商报错（FR-010）；映射见颗粒度文档 §4.2
- [X] T009 [P] [US1] 编写 `FunctionCallingAdapterTest` in `nivroos-provider/src/test/java/com/nivroos/provider/FunctionCallingAdapterTest.java`——验收点：工具定义翻译后字段一一对应、翻译产物不含执行逻辑
- [X] T010 [P] [US1] 编写 `SpringAiProviderServiceTest` in `nivroos-provider/src/test/java/com/nivroos/provider/SpringAiProviderServiceTest.java`——颗粒度文档 §4.3 三个关键回归测试**原样落地**（断言逐条保真）：`chat_routesToNamedProviderOnly`（按名路由不串台，FR-001/FR-004）、`callFailure_stillRecordsAudit_thenRethrows`（失败审计留痕 token 空 + 异常上抛，FR-005/FR-006）、`callWithToolSchema_disablesAutoExecution`（关闭自动执行，FR-008）；另含未知名供应商抛 `ProviderNotFoundException` 含可用列表（FR-010）

### Implementation for User Story 1

- [X] T011 [US1] 实现 `ProviderProperties` in `nivroos-provider/src/main/java/com/nivroos/provider/ProviderProperties.java`——绑定 `nivroos.providers.*` 并做占位/唯一/必填校验（契约见 contracts/provider-config.md）
- [X] T012 [US1] 实现 `FunctionCallingAdapter` in `nivroos-provider/src/main/java/com/nivroos/provider/FunctionCallingAdapter.java`——NivroTool 翻译为 Spring AI 工具格式，仅翻译不执行
- [X] T013 [US1] 实现 `SpringAiProviderService` in `nivroos-provider/src/main/java/com/nivroos/provider/SpringAiProviderService.java`——显式 `Map<String, ChatModel>` 路由、同步调用、关闭自动执行、成功/失败均经 `LlmCallStore` 写审计（失败 token 空 + duration 实际，research §8）、异常语义按契约（依赖 T006/T007/T011/T012）
- [X] T014 [US1] 实现 `ProviderAutoConfiguration` in `nivroos-provider/src/main/java/com/nivroos/provider/ProviderAutoConfiguration.java`——显式构造 ChatModel Bean + 组装 `Map<String, ChatModel>` 注入 ProviderService；保持 `OpenAiAutoConfiguration` 排除（application.yml 已配，不得回退）
- [X] T015 [US1] 实现 `ConfigLoader` 基础版 in `nivroos-cli/src/main/java/com/nivroos/cli/config/ConfigLoader.java`——`${ENV_VAR}` 占位解析与必填校验，缺失/非法报错指明具体项

**Checkpoint**: US1 可独立验证——mock 测试全绿，配置校验负向场景全部报错清晰

## Phase 4: User Story 2 - 多供应商并存与无锁切换 (Priority: P2)

**Goal**: 两家供应商并存；Agent 换供应商仅改一处配置，任务指令零改动

**Independent Test**: 路由不串台回归（T010 内）+ `ProviderSmokeIT`（有 key 时两家各真实调用一次）

### Tests for User Story 2（先写，先失败）

- [X] T016 [P] [US2] 编写 `ProviderSmokeIT` in `nivroos-provider/src/test/java/com/nivroos/provider/ProviderSmokeIT.java`——环境守卫：`DEEPSEEK_API_KEY` / `KIMI_API_KEY` 缺失即跳过（CI 无密钥不置红）；有 key 时两家各真实调用一次，断言返回非空 + `llm_calls` 落库一条（颗粒度文档 §4.1 分层标准）

### Implementation for User Story 2

- [X] T017 [US2] 在 `nivroos-boot/src/main/resources/application.yml` 补 `nivroos.providers.kimi` 段（`api-key: ${KIMI_API_KEY}`、`base-url: https://api.moonshot.cn/v1`）

**Checkpoint**: 两家供应商并存可用；切换仅改 Profile 的 provider name（本模块无 Profile 派生，以测试内 profileUsing 构造验证）

## Phase 5: User Story 3 - 每次调用可追溯（审计） (Priority: P3)

**Goal**: 每次 LLM 调用（含失败）落 `llm_calls`，字段与需求文档 §10 一致

**Independent Test**: `JpaLlmCallStoreTest` 全绿 + 冒烟落库断言（T016 内含）

### Tests for User Story 3（先写，先失败）

- [X] T018 [P] [US3] 编写 `JpaLlmCallStoreTest` in `nivroos-storage/src/test/java/com/nivroos/storage/llm/JpaLlmCallStoreTest.java`——验收点：schema.sql 建表可写可读、token 三列与 session_id 可空、duration_ms/created_at 非空

### Implementation for User Story 3

- [X] T019 [US3] 实现 `LlmCall` 实体 in `nivroos-storage/src/main/java/com/nivroos/storage/llm/LlmCall.java`——九列与需求文档 §10 一致，不加 success/error_message 列（颗粒度文档 §3.4 差异裁决注）
- [X] T020 [US3] 实现 `LlmCallRepository` in `nivroos-storage/src/main/java/com/nivroos/storage/llm/LlmCallRepository.java`（Spring Data JPA）
- [X] T021 [US3] 实现 `JpaLlmCallStore` in `nivroos-storage/src/main/java/com/nivroos/storage/llm/JpaLlmCallStore.java`——实现 core 的 `LlmCallStore` 接口（依赖 T007/T019/T020）
- [X] T022 [US3] 在 `nivroos-boot/src/main/resources/schema.sql` 增加 `llm_calls` 建表语句（`CREATE TABLE IF NOT EXISTS`，九列，见颗粒度文档 §3.4），并删除占位语句 `SELECT 1;`

**Checkpoint**: 审计落库闭环——mock 场景与真实冒烟场景均留痕

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁与收尾（module-dev 步骤 7 的 DoD 证据）

- [X] T023 全量门禁：`mvn clean verify` 全绿（Spotless + Checkstyle + SpotBugs(findsecbugs) + 测试 + JaCoCo）；格式问题只许 `mvn spotless:apply` 修复，禁手改
- [X] T024 安全扫描：`mvn -Psecurity verify`（OWASP dependency-check；新增 openai starter 的传递依赖 CVE 检查，检出则加 suppression 并注明 CVE + 理由）
- [X] T025 执行 quickstart.md 人工验证项并记录结果（真实调用冒烟、5 项负向验证、装配确认 `OpenAiAutoConfiguration` 保持排除、剩余人工项清单交给用户）
- [X] T026 交付物存在性核对（颗粒度文档 §3 逐项）+ 全局不变量 6 条自查（module-dev 配置区）+ 输出验收报告与变更总结

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup 完成，**阻塞全部 user story**
- **User Stories (Phase 3~5)**: 依赖 Foundational 完成；US1 → US2 → US3 顺序推进
- **Polish (Phase 6)**: 依赖全部 user story 完成

### User Story Dependencies

- **US1 (P1)**: Foundational 完成后即可开始；无对其他 story 的依赖
- **US2 (P2)**: 依赖 US1（显式 Map 与 ProviderService 实现）；增量 = 冒烟 + kimi 配置段
- **US3 (P3)**: 依赖 US1 的 `LlmCallStore` 接线（T013）；增量 = storage 三件套 + 建表

### Within Each User Story

- 测试任务 MUST 先于实现任务完成（harness 先行，先失败再实现）
- 接口/实体先于实现类
- 实现完成即跑本模块测试，红了当场修

### Parallel Opportunities

- Setup：T001 ∥ T002
- Foundational：T003 ∥ T004 ∥ T005 ∥ T007（T006 可与 T007 并行）
- US1：T008 ∥ T009 ∥ T010（三个测试类不同文件）；实现 T011/T012/T015 可并行（不同文件），T013 依赖 T011/T012，T014 依赖 T013
- US2：T016 先行
- US3：T018 先行；T019/T020 可并行

## Parallel Example: User Story 1

```bash
# 三个测试类先行并行（harness 先行，先失败）：
Task: "T008 ProviderPropertiesTest"
Task: "T009 FunctionCallingAdapterTest"
Task: "T010 SpringAiProviderServiceTest"

# 实现可并行部分：
Task: "T011 ProviderProperties"
Task: "T012 FunctionCallingAdapter"
Task: "T015 ConfigLoader"
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1: Setup（pom 依赖 + deepseek 配置段）
2. 完成 Phase 2: Foundational（core 抽象）
3. 完成 Phase 3: US1（harness 先行 → 实现）
4. **STOP and VALIDATE**: mock 测试全绿 + 配置负向验证
5. US2（冒烟 + kimi 段）→ US3（审计落库）逐故事推进

### Incremental Delivery

1. Setup + Foundational → 门禁保持绿
2. US1 → 测试独立验证 → 主链路可调 LLM（MVP）
3. US2 → 两家并存 → 真实冒烟（有 key 时）
4. US3 → 审计落库 → 颗粒度文档 §6 人工项验证

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- [Story] 标签映射任务到 user story，便于追溯
- 三个关键回归测试（T010）的断言逻辑与颗粒度文档 §4.3 **逐条保真**
- 测试方法名一律英文（驼峰），`@DisplayName` 保留中文验收点；关键注释中英并列（中文在前）
- 每个任务完成跑该模块测试，失败即时修复（测试纪律，不得 `@Disabled`、不得删断言）
- 全程不自动 commit / push，同步时机由用户决定
