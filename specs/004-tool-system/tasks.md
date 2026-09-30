---
description: "Task list for US-4 Tool 体系 implementation"
---

# Tasks: US-4 Tool 体系（内置 Tool 补全 + Plugin Tool 三档 + Sandbox 完整版）

**Input**: Design documents from `/specs/004-tool-system/`

**Prerequisites**: [plan.md](./plan.md)（含 Technical Context / 模块落位 / 前序改造点 7 条 / 软门禁报告项 3 条）、[spec.md](./spec.md)（FR-001~FR-028 / SC-001~SC-009）、[research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/](./contracts/)、[quickstart.md](./quickstart.md)

**Tests**: 本模块**必须**写测试——颗粒度文档 §4「验收测试（Harness）」是硬交付物（16 个测试类 + 5 条写出代码的关键回归），module-dev 的 DoD 逐项对号。测试与对应实现**同任务落地**（module-dev 写后门禁：失败即时修复，不累积）。

**Organization**: 按 spec.md 的 5 个 user story 分相；优先级 P1 → P5。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属 user story（US1~US5）
- 每条任务含确切文件路径

## Path Conventions

Maven 多模块（9 个固定），包根 `com.nivroos`：

- 生产代码 `nivroos-<模块>/src/main/java/com/nivroos/<pkg>/`
- 测试代码 `nivroos-<模块>/src/test/java/com/nivroos/<pkg>/`
- 表结构 `nivroos-boot/src/main/resources/schema.sql`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖声明增补（软门禁报告项 3，两条均为既有 BOM 管理的同坐标再声明，零新增第三方坐标）

- [ ] T001 [P] `nivroos-memory/pom.xml` 增 `org.springframework.ai:spring-ai-model`（`@Tool` 注解所需；同坐标已在 `nivroos-tool` 声明）
- [ ] T002 [P] `nivroos-tool/pom.xml` 增 `org.springframework.boot:spring-boot`（`@Configuration` / `Environment` / `Binder` 用具；非 starter、不带自动装配）

**Checkpoint**: `mvn -pl nivroos-memory -am test` 与 `mvn -pl nivroos-tool -am test` 能解析新依赖并通过既有测试

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 三条主链路（沙箱闸门 / 工具注册表 / Agent 目录派生）的公共底座——**每个 user story 都要用**，故整体前置。

**⚠️ CRITICAL**: 本相完成前不开user story 相

- [x] T003 [P] 补全 `nivroos-tool/src/main/java/com/nivroos/tool/sandbox/WhitelistSandbox.java` 三档白名单：构造签名扩为 `(List<String> allowedPaths, List<String> allowedCommands, List<String> allowedDomains)`，新增私有 `checkFilePath` / `checkShellCommand`，`Sandbox` 接口不变（**前序改造点 1**；算法见 [data-model.md §1](./data-model.md)），并同步 `nivroos-tool/src/test/java/com/nivroos/tool/sandbox/WhitelistSandboxTest.java`（域名回归 + **通配符边界：`*.example.com` 命中 `api.example.com`、不命中 `evil-example.com`**；路径白名单放行 / `../` 穿越必拒 + 目标文件不存在；命令首 token 命中放行 / 未命中拒绝 / 前导空白 trim / 大小写逐字比对；空白名单全拒）
- [x] T004 [P] 新增 `nivroos-tool/src/main/java/com/nivroos/tool/ToolRegistry.java`（`register` 重名保留先注册者 + WARN / `get` 未命中返回 null / `all` 不可变视图 / `scanAnnotated`）+ `nivroos-tool/src/test/java/com/nivroos/tool/ToolRegistryTest.java`（注册表**行为**：扫描路径 / `get` 命中与未命中返回 null / MCP 工具经 `register` 入表 / 重名保留先注册者不覆盖并 WARN；名称·描述·schema 的**对外契约**断言归 T019，本类不重复）
- [x] T005 新增 `nivroos-tool/src/main/java/com/nivroos/tool/AnnotatedToolAdapter.java`（包内可见；`@Tool` → `NivroTool`：名称/描述/schema 取自 `ToolCallback.getToolDefinition()`，`execute` 用 `MethodToolCallback.call(json)` 还原 `ToolResult`，`ToolExecutionException` 解包后抛原异常以保住 `SandboxViolationException` 类型与文案——见 [research.md §2](./research.md)；依赖 T004）
- [x] T006 [P] 补齐 `nivroos-core/src/main/java/com/nivroos/core/profile/Profile.java` 字段（`description` / `identity` / `mcpServers` / `bootstrap` / `channels` / `schedules`，只增不删）+ `nivroos-core/src/main/java/com/nivroos/core/loader/AgentLoader.java` 增 `scan()`（`loadProfile` 语义不变；单 Agent 非法抛含 Agent 名与缺失字段的清晰异常）+ 同步 `nivroos-core/src/test/java/com/nivroos/core/loader/AgentLoaderTest.java`（**前序改造点 5**；字段映射表见 [contracts/agent-context.md §1](./contracts/agent-context.md)）
- [x] T007 [P] `nivroos-memory/src/main/java/com/nivroos/memory/MemoryTools.java` 改标 `@Tool`：工具名 `save_memory` / `recall_memory` 与参数名 `content` / `scope` / `query` **逐字不变**，失败语义不变（业务性失败返回失败结果、异常性失败抛异常），schema 来源由手写 JSON 切到注解生成 + 同步 `nivroos-memory/src/test/java/com/nivroos/memory/MemoryToolsTest.java`（**前序改造点 2**）
- [x] T008 新增 `nivroos-tool/src/main/java/com/nivroos/tool/ToolConfiguration.java`（建立装配骨架，**只装配本相已存在的类**：Binder 读三组白名单与 `shell.timeout-seconds` → `WhitelistSandbox` Bean + `ToolRegistry` Bean（注入 `ApplicationContext` 遍历容器单例 Bean 送 `scanAnnotated`，覆盖 nivroos-memory 的 `MemoryTools`——**tool 模块对 memory 无编译期依赖**；tool 侧各 Tool Bean 与 `McpClientService` Bean 由后续任务逐次加入本类））+ `nivroos-tool/src/test/java/com/nivroos/tool/ToolConfigurationTest.java`（三组白名单 Binder 绑定非 null、空白名单全拒、shell 超时生效、注册表含**本相已交付**的内置工具（`MemoryTools` 的两个）；依赖 T003/T004/T005/T007）
- [x] T009 `nivroos-cli/src/main/java/com/nivroos/cli/ChatCommand.java` 工具池由手工 `Map.of(...)` 换成容器注入的 `ToolRegistry.all()`（`ToolExecutor` / `ReActLoop` 构造签名不变；**前序改造点 3**；依赖 T008）

**Checkpoint**: 沙箱三档、注册表、Agent 派生、CLI 装配四条底座就绪——user story 相可以开始

---

## Phase 3: User Story 1 - Agent 在白名单内读写文件、执行命令、调用接口 (Priority: P1) 🎯 MVP

**Goal**: 主链路最短闭环——模型连续调用文件 / 命令 / HTTP 工具完成任务，每一步动作**在真正发生之前**先过白名单，越界动作被拒且**没有真正发生**

**Independent Test**: 单测层用 `@TempDir` 与无副作用命令跑通四类工具的放行与拒绝两组用例，并断言被拒动作未发生（目标文件不存在、请求未发出）；真机层 `nivroos chat` 跑一次"列目录 → 读文件 → 执行脚本"

### Implementation for User Story 1

- [x] T010 [P] [US1] 新增 `nivroos-tool/src/main/java/com/nivroos/tool/FileTools.java`（`@Tool` 方法：`read_file` / `write_file`（另带 `content`）/ `list_dir`，入参 `path`；每个方法首步 `sandbox.enforce`）+ **接入 `ToolConfiguration` 并扩充 `ToolConfigurationTest` 的注册表断言** + `nivroos-tool/src/test/java/com/nivroos/tool/FileToolsTest.java`（读写列三类放行；越界路径被拒**且越界 `write_file` 后目标文件确实不存在**；IO 失败**不吞且落 WARN 日志**）
- [x] T011 [P] [US1] 新增 `nivroos-tool/src/main/java/com/nivroos/tool/ShellTools.java`（`ShellTools(Sandbox, Duration timeout)`；`@Tool` 方法 `shell`，入参 `command`；`ProcessBuilder` 经 POSIX `bash -c` 执行、进程启动目录为工作目录、超时强杀并返回失败）+ **接入 `ToolConfiguration` 并扩充 `ToolConfigurationTest` 的注册表断言** + `nivroos-tool/src/test/java/com/nivroos/tool/ShellToolsTest.java`（白名单命令 stdout 进 `content`；非白名单命令拒绝**且命令未启动**；超时终止不挂死且**失败路径落 WARN 日志**；**并发两路调用输出不串**——关键回归）
- [x] T012 [P] [US1] 改造 `nivroos-tool/src/main/java/com/nivroos/tool/HttpTools.java`（`http_get` 改 `@Tool` 标注，工具名与参数名 `url` 逐字不变；新增 `@Tool` 方法 `http_post`，参数 `url` / `body`，JSON 请求体）+ **接入 `ToolConfiguration` 并扩充 `ToolConfigurationTest` 的注册表断言** + 同步 `nivroos-tool/src/test/java/com/nivroos/tool/HttpToolsTest.java`（`http_get` 回归；`http_post` 方法 / URL / body 正确且先过域名白名单；白名单外域名 → mock `HttpClient` 断言请求未发出 `never()`；失败路径**不吞且落 WARN 日志**）
- [x] T013 [US1] `nivroos-boot/src/main/resources/application.yml` 增 `file.allowed_paths` 与 `shell.allowed_commands` 段（`http.allowed_domains` 沿用 US-2 键；三组留空 = 全部拒绝，部署说明写明——键名是已定字面量，见 [docs/us/us4-tool.md §3.3](../docs/us/us4-tool.md)）

**Checkpoint**: US1 可独立验证——CLI 跑通"列目录 → 读文件 → 执行脚本"，越界动作零发生

---

## Phase 4: User Story 2 - Agent 主动把结果推送到企业群 (Priority: P2)

**Goal**: 出站能力——运维方在全局注册表登记渠道，模型只需给内容与渠道名，真实地址不进对话；发送前先过同一份域名白名单

**Independent Test**: mock 发送通道，断言渠道名解析出的目标地址与类型正确、`enforce` 先于发送（`InOrder`）、白名单外地址请求未发出、渠道名缺失或不存在时明确失败并回填可用渠道名清单

### Implementation for User Story 2

- [x] T014 [P] [US2] 新增 `nivroos-core/src/main/java/com/nivroos/core/notify/NotifyChannel.java`（record：`name` / `type` / `url` / `description`）与 `NotifyChannelStore.java`（接口：`findByName(String) → Optional<NotifyChannel>` + `channelNames()`——依赖倒置，接口在 core、实现在 storage，同 `ToolInvocationStore` 先例；`channelNames()` 是**软门禁报告项 2**）
- [x] T015 [P] [US2] 新增 `nivroos-storage/src/main/java/com/nivroos/storage/notify/` 四件套：**`NotifyChannelEntity.java`**（JPA 实体，表 `notify_channels`；类名裁决 2026-09-30——避开与 core 的 `NotifyChannel` record 同名，**软门禁报告项 4**）/ `NotifyChannelRepository.java` / `JpaNotifyChannelStore.java`（实现 core 的 `NotifyChannelStore`，返回 core 的 `NotifyChannel` record）+ `NotifyChannelStoreConfiguration.java`（同 `ToolInvocationStoreConfiguration` 模式）+ `nivroos-storage/src/test/java/com/nivroos/storage/notify/JpaNotifyChannelStoreTest.java`（建表可写可读；`findByName` 命中/未命中；实体 → record 的 `type`/`url`/`description` 字段映射正确）
- [x] T016 [US2] `nivroos-boot/src/main/resources/schema.sql` 追加 `notify_channels` 幂等建表（DDL 逐字取自 [docs/us/us4-tool.md §3.4](../docs/us/us4-tool.md)；手工建表脚本，不依赖 `ddl-auto`）
- [x] T017 [P] [US2] 新增 `nivroos-tool/src/main/java/com/nivroos/tool/NotifyChannelAdapter.java`（接口 `send(NotifyTarget, String)`）/ `NotifyTarget.java`（record：`channelType` / `config`）/ `WebhookNotifyAdapter.java`（唯一实现；`WebhookNotifyAdapter(Sandbox, HttpClient)`——`HttpClient` 可注入以便单测 mock；**发送前** `sandbox.enforce(HTTP_REQUEST, url)`；非 2xx 抛异常不吞）+ `nivroos-tool/src/test/java/com/nivroos/tool/WebhookNotifyAdapterTest.java`（**`enforce` 先于发送 `InOrder`**——关键回归；body 含 `content`、URL 取自 `NotifyTarget.config` 非硬编码；白名单外域名 → 请求未发出且异常上抛；非 2xx 上抛**不吞且落 WARN 日志**）
- [x] T018 [US2] 新增 `nivroos-tool/src/main/java/com/nivroos/tool/NotifyTools.java`（`NotifyTools(Sandbox, NotifyChannelStore, NotifyChannelAdapter)`；`@Tool` 方法 `notify`，入参 `content` / `channel`）并接入 `ToolConfiguration` + 扩充 `ToolConfigurationTest` 的注册表断言 + `nivroos-tool/src/test/java/com/nivroos/tool/NotifyToolsTest.java`（渠道解析 → 适配器收到正确 `NotifyTarget`；渠道名缺省 / 不存在 / 类型不受支持 → 清晰失败**并回填可用渠道名清单** + **失败路径落 WARN 日志**；审计由既有 `ToolExecutor` 路径覆盖，不新增）（依赖 T014/T017）

**Checkpoint**: US2 可独立验证——mock 通道下顺序断言通过，白名单外地址请求零发出

---

## Phase 5: User Story 3 - 工具统一注册与三类来源统一取用 (Priority: P3)

**Goal**: 内置 / 注解组件 / MCP 三类来源同一份登记、同一套取用；模型侧只看到工具名、描述与 schema；工具池严格等于 Agent 声明

**Independent Test**: 三类来源各注册一个工具，参数化断言名称非空且唯一、描述非空、schema 非空；核对按名查找命中与未命中；核对 Agent 声明两个工具时工具池**恰好**只有这两个

> 注册表与其单元测试已随 Phase 2（T004）落地——它被 US1/US2 共同阻塞，故实现前置；本相交付**跨来源契约验证**与方式三演示 Bean。

### Implementation for User Story 3

- [x] T019 [P] [US3] 新增 `nivroos-tool/src/test/java/com/nivroos/tool/ToolContractTest.java`：参数化遍历 `ToolRegistry.all()`（测试内三类各注册一个），断言名称非空且唯一、描述非空、`getInputSchema()` 非空——**关键回归**（`FunctionCallingAdapter` 直接取 `tool.getInputSchema().value()` 拼 LLM 请求，任一工具 schema 为空会让**全部** LLM 调用失败）。**分工**：注册表行为归 T004，对外契约（含"`@Tool` 注解生成的 schema 非空"）全归本类
- [x] T020 [P] [US3] 补 `nivroos-core/src/test/java/com/nivroos/core/react/ReActLoopTest.java` 用例：**工具池严格按 `Profile.tools` 精确匹配——声明几个就只给几个，未声明的内置工具不进池**、未注册名字 WARN 跳过——**关键回归**（**前序改造点 7**；`resolveTools` 私有方法不改签名，只补测试）
- [x] T021 [US3] 在 `nivroos-boot/src/main/java/com/nivroos/boot/example/` 新增方式三示例 `@Tool` Bean（演示用途、非产品工具；编译进进程，经容器遍历进注册表——落位裁决 2026-09-30）

**Checkpoint**: 三类来源统一性有契约测试钉死；工具池边界有回归测试钉死

---

## Phase 6: User Story 4 - 轻代码接入：业务方自写 MCP server (Priority: P4)

**Goal**: 配置里声明 MCP server，启动时连上、拉列表、注册；单个失联只告警不阻断；调用失败错误文本回给模型

**Independent Test**: mock 传输层注入一个失败 server 与一个正常 server，断言启动不抛异常、正常 server 工具被注册、失败 server 工具未注册、告警产生；mock 客户端断言入参转发与错误映射

### Implementation for User Story 4

- [x] T022 [P] [US4] 新增 `nivroos-tool/src/main/java/com/nivroos/tool/McpServerConfig.java`（record：`name` / `transport` / `command` / `env` + 从 `.nivroos/mcp_servers.yaml` 解析；`command` 按空白拆首 token 为可执行文件；`env` 值只允许 `${ENV_VAR}` 占位，本地解析规则同 CLI 侧 `ConfigLoader.resolveEnv` 但**不复用其实现**（避免 tool → cli 反向依赖））+ 覆盖于 `McpClientServiceTest` 的解析用例（文件不存在视为无 MCP；`transport` 非 `stdio` WARN 跳过；**明文 env 与占位符未解析大声报错**）
- [x] T023 [P] [US4] 新增 `nivroos-tool/src/main/java/com/nivroos/tool/McpToolAdapter.java`（`McpToolAdapter(String serverName, McpSchema.Tool tool, McpSyncClient client)`；`execute(JsonNode)` → `callTool(new CallToolRequest(name, args))`；`isError == true` → `ToolResult(false, null, 文本, false)`；`TextContent` → `content`；Jackson 2（`JsonNode`）↔ Jackson 3（SDK）桥接**只在此类**；SDK 未给 schema 时补空对象 schema）+ `nivroos-tool/src/test/java/com/nivroos/tool/McpToolAdapterTest.java`（名称/描述/schema 映射；参数转发正确；**`isError=true` → `success=false` 且错误信息回填**——关键回归；**失败路径落 WARN 日志**）
- [x] T024 [US4] 新增 `nivroos-tool/src/main/java/com/nivroos/tool/McpClientService.java`（`start()`：连接 + `initialize` + `tools/list` 翻页取尽 + `register`，单 server 失败 WARN 跳过不阻断；`close()`：`closeGracefully` 失败降级 `close()` + WARN；调用超时常量 `Duration.ofSeconds(30)`，不新增配置键；包内可见测试构造注入 mock transport）**并接入 `ToolConfiguration`（`initMethod = "start"` / `destroyMethod = "close"`；`McpClientService` Bean 在本任务加入——T008 不预置）** + `nivroos-tool/src/test/java/com/nivroos/tool/McpClientServiceTest.java`（列表结果全部注册；**调用超时设定生效**；**单个 server 失联 → `WARN` 日志且该 server 工具不注册、其它 server 不受影响、启动不阻断**——关键回归）
- [x] T025 [US4] 核对 `nivroos-cli` 的 `init` 生成的 `.nivroos/mcp_servers.yaml` 模板与 `McpServerConfig` 解析契约一致（`servers:` 顶层键 + `name` / `transport` / `command` / `env` 字段；不一致则改模板，键名是已定字面量）

**Checkpoint**: US4 可独立验证——mock 传输下失联容错与错误映射两条链路都有断言

---

## Phase 7: User Story 5 - 零代码上线：Agent 目录完整派生与技能按需披露 (Priority: P5)

**Goal**: 业务方只写一个 Agent 目录即可上线；绑定的技能每轮只注入名称、描述与本地路径，正文由模型用文件工具现取

**Independent Test**: 多 Agent 目录（其一非法）启动扫描，合法者全注册、非法者只告警；声明的引导文件列表生效、缺失回退默认三件；挂真实技能软连接，断言注入文本含名称/描述/绝对路径且**不含 `SKILL.md` 正文**，并核对每轮重扫

### Implementation for User Story 5

- [x] T026 [US5] 改造 `nivroos-core/src/main/java/com/nivroos/core/context/ContextLoader.java`：Bootstrap 列表改按 `Profile.bootstrap`（字段缺失回退 `AGENTS.md` / `SOUL.md` / `USER.md` + WARN；声明文件缺失 WARN 但继续）+ **Skill 元数据（L1）注入**（每轮重扫 `agentDir/skills/` 软连接 → `stripFrontmatter` + SnakeYAML 取 `name` / `description` → 追加「可用技能」段，**只有 name + description + Agent 本地绝对路径**，正文不注入；断链 / 缺必需字段 / **真实目标越出 `.nivroos/skills/`（FR-028）** → WARN 跳过不阻断）+ 同步 `nivroos-core/src/test/java/com/nivroos/core/context/ContextLoaderTest.java`（**Bootstrap 按 Profile 生效**、回退 + WARN、**L1 注入不含 `SKILL.md` 正文**、软连接断链与越界跳过、每轮重扫）（**前序改造点 4**；`FR-028` 是**软门禁报告项 1**）
- [x] T027 [US5] 新增 `nivroos-boot/src/main/java/com/nivroos/boot/ProfileConfiguration.java`：启动调 `AgentLoader.scan()` → 跨模块校验（`provider` 在 `ProviderService.providerNames()` / `tools` 在 `ToolRegistry` / `bootstrap` 文件存在 / `mcp_servers` 在配置中）→ `ProfileRegistry` Bean；**单个 Agent 失败只 WARN 不阻断启动**（差异裁决注 6）

**Checkpoint**: US5 可独立验证——多 Agent 扫描容错 + 技能 L1 注入内容人工核对（SC-006）

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 模块级 DoD 证据链（module-dev 步骤 7 七项）

- [x] T028 [P] 跑全量门禁 `mvn clean verify`（Spotless + Checkstyle + SpotBugs/findsecbugs + 测试 + JaCoCo）并留存关键输出
- [x] T029 [P] 逐条执行 [quickstart.md §2](./quickstart.md) 的 8 条机器可判不变量 grep（依赖方向、无 Reactor、无明文 key、模块数 9、审计路径唯一、无自动执行）+ ⑨ **可观测性双轨抽查**：新增工具/适配器类里每个 `catch` 分支**要么落 WARN 日志、要么上抛**，不出现静默吞（FR-023 / SC-005）
- [x] T030 [P] 跑 `mvn -Psecurity verify`（OWASP dependency-check），复核新增依赖后既有 8 组抑制是否仍成立 —— **已执行，门禁全绿（BUILD SUCCESS，9/9 模块）**。过程三段：①首轮 RED —— NVD 2026-09-29 数据刷新使既有依赖暴露 16 条 ≥7.0 的 CVE（spring-core 12 / spring-ai 3 / opennlp-tools 1，非本模块引入，US-4 未加任何新第三方坐标），用户裁决"扩抑制文件、沿用接受风险"；②抑制有效性复核时查明历史口径实为"只抑制当轮卡门禁的（≥7.0）"，另有 31 条 <7.0 从未被抑制 → 用户二次裁决"真全量收录"；③对账中再发现 3 条 RetireJS 来源（NVD 无收录）的 DOMPurify CVE，DC 归类为 vulnerabilityName，须用 `<vulnerabilityName>` 匹配而非 `<cve>`。最终 `config/dependency-check-suppressions.xml` = **12 组（89 条 cve + 3 条 vulnerabilityName）**，报告未抑制清单归零。遗留待复核点在文件内已标注：spring-data-jpa CVE-2026-47834 在 US-5 接入 REST 查询参数时必须重评
- [x] T031 前序模块回归：`mvn -pl nivroos-cli -am test` + `mvn -pl nivroos-boot -am test`（跨模块契约证据）
- [x] T032 整理收尾报告：harness 映射表逐项对号 + [quickstart.md §3](./quickstart.md) 剩余人工项清单（真 MCP server / 真 webhook / 真模型 / 技能 L1 人工核对）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup——**阻塞全部 user story**
- **User Stories (Phase 3~7)**: 均依赖 Foundational 完成；相内按 P1 → P5 顺序推进（同一批文件的改动必须串行）
- **Polish (Phase 8)**: 依赖全部目标 user story 完成

### 相内关键依赖

```text
T003 WhitelistSandbox ─┬─→ T010 FileTools / T011 ShellTools / T017 WebhookNotifyAdapter
                       └─→ T008 ToolConfiguration
T004 ToolRegistry ─────┬─→ T005 AnnotatedToolAdapter → T008 ToolConfiguration
                       ├─→ T019 ToolContractTest
                       └─→ T024 McpClientService（register 入口）
T005 ────────────────────→ T008 ToolConfiguration → T009 ChatCommand
T008 ────────────────────→ 由 T010 / T011 / T012 / T018 / T024 **逐次扩充**（各工具 Bean 在自己任务里加入，并同步扩充 ToolConfigurationTest 的注册表断言）
T006 Profile/scan ───────→ T027 ProfileConfiguration
T007 MemoryTools 改标注 ─→ T009 ChatCommand（先改标注再切换工具池，否则 CLI 短暂丢记忆工具）
T014 core 渠道接口 ──────→ T015 storage 实现 / T018 NotifyTools
T016 schema.sql ─────────→ T015 JpaNotifyChannelStoreTest（建表先于读写）
T011 ShellTools ─────────→ T022 McpServerConfig 无关；T024 McpClientService 起子进程走 stdio
```

### Parallel Opportunities

- **Phase 1**: T001 与 T002 并行
- **Phase 2**: T003 / T004 / T006 / T007 并行（不同模块不同文件）；T005 待 T004；T008 待 T003+T005+T007；T009 待 T008
- **Phase 3 (US1)**: T010 / T011 / T012 三文件互不重叠，可并行；T013 独立
- **Phase 4 (US2)**: T014 / T015 / T017 可并行；T016 独立；T018 待 T014+T017
- **Phase 5 (US3)**: T019 与 T020 并行；T021 独立
- **Phase 6 (US4)**: T022 / T023 并行；T024 待 T022+T023；T025 独立
- **Phase 8**: T028 / T029 / T030 可并行

### Parallel Example: Phase 3 (US1)

```bash
# 三个工具类文件互不重叠，并行落地（各自带上自己的测试）
Task: "新增 FileTools + FileToolsTest in nivroos-tool"
Task: "新增 ShellTools + ShellToolsTest in nivroos-tool"
Task: "改造 HttpTools + HttpToolsTest in nivroos-tool"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 Setup（两条依赖声明）
2. Phase 2 Foundational（沙箱三档 + 注册表 + Agent 派生 + 装配改造）——**关键，阻塞全部相**
3. Phase 3 US1（文件 / 命令 / HTTP 三组工具 + 配置）
4. **STOP & VALIDATE**：`nivroos chat` 跑通"列目录 → 读文件 → 执行脚本"，越界动作零发生
5. 这是本模块最短可演示闭环（需求文档 §13 Demo 二 能力四部分的前半）

### Incremental Delivery

1. Setup + Foundational → 底座就绪
2. US1 → 主链路闭环（MVP，可演示）
3. US2 → 出站能力（日报类 Agent 的出口）
4. US3 → 三类来源统一性契约化
5. US4 → 方式一/方式二接入就绪
6. US5 → 零代码上线闭环
7. Polish → DoD 七项证据

### 前序改造点的落地顺序（软门禁，实施时逐条报告）

T003（沙箱签名）→ T007（记忆工具改标注）→ T009（CLI 装配）→ T026（上下文装配）→ T006（Profile 派生）→ T020（ReActLoop 补测）——每条都**只动清单内点名的部分**，不改已定字面量。

---

## Notes

- [P] = 不同文件、无未完成依赖
- **[Story] 标签**用于追溯；Setup / Foundational / Polish 不带
- 测试与实现**同任务落地**（本模块测试是硬交付物，非可选）
- 关键注释中英并列（中文在前）；测试方法名必须英文，文档原文进 `@DisplayName`
- 5 条关键回归测试断言逐条保真（颗粒度文档 §4.3 原样落地）
- 格式修复只许 `mvn spotless:apply`，禁手改
- **不自动 commit / push / 部署**——同步时机由用户决定
