# Research: US-3 Memory 三层记忆（Phase 0）

> 输入：颗粒度文档 `docs/us/us3-memory.md`、技术方案 §5（最权威）、spec.md。
> 本章消解 plan 的全部未知项；每条给 Decision / Rationale / Alternatives。

## 1. MemoryService.loadContext 的职责边界与截断归属（核心决策）

**Decision**：`loadContext(Session)` 返回 `List<Message>` = **会话历史（截断到
`max_history_turns` 轮之后）在前 + 长期记忆（一条 system 消息）在后**。截断逻辑
从 `PromptBuilder` 迁入 `MemoryService` 的历史委托路径。

`PromptBuilder.build` 简化为：

```text
messages = [ system(contextLoader.loadSystemPrompt()) ] + memoryService.loadContext(session)
```

**Rationale**：

- 颗粒度文档 §3.1 规定 MemoryService「`loadContext(Session)` 供 PromptBuilder」
  且「**会话历史委托 SessionManager**」——历史由门面产出，不由 PromptBuilder 产出。
- 颗粒度文档 §4.2 的 `MemoryServiceTest` 验收点逐字写明「loadContext 拼接顺序
  （**会话历史在前、长期记忆在后**）」，即 loadContext 必须同时含两者。
- spec FR-019 要求「会话历史与长期记忆收口在同一个记忆入口之后，使 ReAct 循环
  无需分别向两个来源索取上下文」。
- harness 的 `build_includesMemoryContent` 以 `new PromptBuilder(contextLoader, memory)`
  + mock `loadContext` 组装，与上述签名一致。
- 截断是「历史怎么给」的一部分，跟历史来源同处一地比留在 PromptBuilder 更内聚；
  `max_history_turns` 仍从 `ProfileContext.current()` 读（US-2 语义不变）。

**Alternatives considered**：

- *loadContext 只返回长期记忆、PromptBuilder 保留历史与截断*：被 §4.2 的拼接顺序
  断言直接否定（历史必须由 loadContext 产出）。
- *loadContext 返回全量历史不截断、PromptBuilder 无法区分段落再裁*：返回类型是
  扁平的 `List<Message>`，调用方无法辨别哪段是历史，`max_history_turns` 语义会破。
- *新增一个只返回长期记忆的方法*：等于在交付物清单外新建对外概念（module-dev
  软门禁 1），且门面没起到收口作用。

**Consequence（须在 plan 的改造点节标明）**：US-2 的 `PromptBuilderTest` 中关于
历史截断的用例迁为 `MemoryServiceTest` 的截断用例，`PromptBuilderTest` 改为断言
「system + 委托 loadContext」；颗粒度文档 §3.2-1 已授权「US-2 的 PromptBuilderTest
同步更新（mock MemoryService）」。

## 2. SqliteMemoryStore 的数据访问方式

**Decision**：用 `JdbcTemplate`（`spring-jdbc`），注入 `DataSource`；表由
`schema.sql` 手工维护（幂等 `CREATE TABLE IF NOT EXISTS`）。

**Rationale**：颗粒度文档 §6 明写「本模块无新增第三方依赖（**spring-jdbc 为 Boot
BOM 管理的标准依赖**，披露于实现报告）」——这句话本身就点定了 JdbcTemplate 路线。
且宪法要求 SQLite 表结构变更一律走手工建表脚本、不用 ddl-auto，JPA 实体在此无收益
（记忆表无关联、无迁移需求）。

**Alternatives considered**：

- *JPA `@Entity` + Repository（nivroos-storage 先例）*：会把记忆实体塞进
  `nivroos-storage`，而颗粒度文档 §3.1 给 storage 的交付物**只有 schema.sql 一行**；
  且 JPA 会拖入 Hibernate 依赖到 memory 模块，违反「零外部依赖」口径。
- *直接在 nivroos-memory 里自建连接*：重复 Spring 的 DataSource 管理，无必要。

## 3. Mem0 自托管 REST 契约（外部依赖核实，软门禁 5 关注项）

**Decision**：按 Mem0 **OSS 自托管** REST 契约对接，JDK `HttpClient` 直连：

| 门面动作 | Mem0 调用 |
| --- | --- |
| `append(content, scope)` | `POST /memories`，body `{"messages":[{"role":"user","content":...}], "user_id":..., "metadata":{"scope":"CORE\|ARCHIVAL"}}` |
| `load()` | `GET /memories`（按 `metadata.scope` 过滤；归档区客户端截断） |
| `recallByKeyword(q)` | `POST /search`，body 带 query 与 `user_id` |

鉴权：`X-API-Key: <key>`（当前 OSS 构建默认开启鉴权）。地址与凭证走
`memory.mem0.url` / `memory.mem0.api-key`（后者只允许 `${ENV_VAR}` 占位）。

**Rationale**：OSS 自托管**不带 `/v1` 前缀**（`/v1/...` 属托管平台
`api.mem0.ai`）；技术方案 §5.1 明确要求「必须**自托管**（数据不出域）」，托管平台
路线直接违反该前提。默认端口 8000（Docker Compose 映射为 8888）。

**Alternatives considered**：*托管平台 API* — 数据出域，违反技术方案 §5.1 前提。

**必须记录的语义差异（写入 plan 与实现报告，不是缺陷而是选型固有代价）**：

1. `POST /memories` 会做 **LLM 抽取**，落库的是抽取后的记忆条目而非原文——
   `append` 在 Mem0 档**不是逐字追加**。技术方案 §5.5 已承认「Mem0 档的自动抽取
   是其自带能力」。
2. `POST /search` 是**语义检索**（返回带 `score` 的匹配），与 md/sqlite 两档的
   关键词匹配语义不同。技术方案 §5.1 已写明「Mem0 档已是语义检索」，是预留的
   升级方向而非偏差。
3. 精确路径与字段随 OSS 版本演进。**实际联调时以部署实例的 `/openapi.json` 为准**，
   列为人工项（spec 已声明 Mem0 档核心阶段只交付代码 + mock 单测）。

**Source**：[Mem0 REST API Server 官方文档](https://docs.mem0.ai/open-source/features/rest-api)

## 4. MEMORY.md 的分区解析与追加格式

**Decision**：按 `## 核心记忆` / `## 归档记忆` 两个**一级分区 header** 定位；
追加时在该分区下先写（或复用）`### <yyyy-MM-dd>` 日期 header，再列
`- <内容>`。解析**宽容**：分区 header 缺失时按需补建，文件不存在时视为空记忆
（FR-020）。`nivroos init` 已生成两分区骨架
（`InitCommand.java:36` → `"# MEMORY.md\n\n## 核心记忆\n\n## 归档记忆\n"`）。

**Rationale**：颗粒度文档 §3.3 给了格式样例与 `nivroos init` 的骨架完全一致；
技术方案 §5.2 明确「格式不做更严格的规定，Agent 写什么 LLM 自己理解就行」——
因此解析必须宽容而不是严格校验，且**不得**引入 Markdown 解析库（零新依赖）。

**Alternatives considered**：*严格 Markdown AST 解析* — 需引入解析库，违背零新依赖；
且文档明确格式宽松，严格化会拒掉模型合法写入的内容。

## 5. 归档区截断方向

**Decision**：保留**最新**的归档条目，丢弃最旧的。

**Rationale**：颗粒度文档 §2.3 的伪代码只写 `truncate(maxChars)` 未给方向；按记忆
系统通行取舍，时间上越近的记忆越可能相关，反向截断会静默丢掉最有价值的近期记忆。
spec 的 Assumptions 已固化本条。

**Alternatives considered**：*保留最旧* — 在记忆系统里近乎无意义，且与 recall 的
实用价值相悖。

## 6. 依赖增补与模块依赖方向

**Decision**：

| 模块 | 增补 | 用途 |
| --- | --- | --- |
| `nivroos-memory` | `org.springframework:spring-jdbc` | `JdbcTemplate`（§2） |
| `nivroos-memory` | `org.springframework.boot:spring-boot` | `@ConfigurationProperties` / `@EnableConfigurationProperties` |
| `nivroos-memory` | `org.xerial:sqlite-jdbc`（**test**） | 单测用内存库 |
| `nivroos-cli` | `com.nivroos:nivroos-memory` | `ChatCommand` 构造 `MemoryTools` + 注入 `MemoryService`（§3.2-2 改造点） |

`nivroos-core` **零增补**（三个新类型只用 JDK + 既有 core 类型）；
`nivroos-boot` **已依赖** `nivroos-memory`（`nivroos-boot/pom.xml`），无需改动；
`MemoryConfiguration` 随组件扫描（`com.nivroos` 全包）生效。

### 6.1 模块落位修正（用户 2026-09-30 裁决）

**Decision**：`MemoryScope` / `LongTermMemoryStore` / `MemoryService` 落
`nivroos-core`（包 `com.nivroos.core.memory`）；三个后端实现 + `MemoryTools` +
`MemoryProperties` + `MemoryConfiguration` 落 `nivroos-memory`。
**颗粒度文档 §3.1 把九项交付物全列在 `nivroos-memory` 名下，此处按裁决修正其中三项。**

**Rationale**：

- 颗粒度文档 §3.2-1 要求 `PromptBuilder`（在 core）构造注入 `MemoryService`，
  §4.3 的 harness 逐字给出 `new PromptBuilder(contextLoader, memory)` +
  `mock(MemoryService.class)`——**core 必须能看到该类型**。
- 而 `nivroos-memory/pom.xml` 依赖 `nivroos-core`；core 反向依赖 memory 构成
  **Maven 循环依赖，构建直接失败**。文档内冲突，无第三种解法。
- 仓库既有先例即此形态：`LlmCallStore` / `ToolInvocationStore` 接口在 core，
  `JpaLlmCallStore` / `JpaToolInvocationStore` 在 storage——**消费方在 core 的
  抽象一律留 core**。
- `MemoryService` 取**具体类**而非接口：技术方案 §5.1 指定的可插拔点是
  `LongTermMemoryStore`（「后端接口」），门面没有第二实现，做接口只多一个空壳；
  且不新增交付物清单外的公开类型名（软门禁 1）。

**Alternatives considered**：

- *`MemoryService` 做接口放 core、实现类放 memory*：同样可行，但需新增一个清单外
  公开类名（如 `DefaultMemoryService`），且接口 + 单一实现在此处无收益。
- *把 `PromptBuilder` 迁进 `nivroos-memory`*：违反 US-2 交付物落位与 CLAUDE.md 模块
  结构表（core 明列 `PromptBuilder`），改动面大得多。
- *把 `SqliteMemoryStore` 放 `nivroos-storage`*：违反颗粒度文档 §3.1 落位表与
  module-dev 模块落位表。

**Rationale**：三个坐标**全部已在根 POM 的 Boot BOM 管理之下**（`sqlite-jdbc` 另有
根 POM `<dependencyManagement>` 显式锁 3.53.2.1），**不引入任何新的第三方坐标**——
与颗粒度文档 §6「本模块无新增第三方依赖」口径一致。依赖方向恒为
`memory → core`，不反向、不引 `nivroos-storage`。

**Alternatives considered**：*把 SqliteMemoryStore 放 `nivroos-storage`* — 违反
颗粒度文档 §3.1 落位表（US-3 全部落 `nivroos-memory`）与 module-dev 模块落位表。

## 7. MemoryConfiguration 的装配契约

**Decision**：`@Configuration(proxyBeanMethods = false)` +
`@EnableConfigurationProperties(MemoryProperties.class)`；**构造期**
`properties.validate(environment)` 拒绝非法配置；按 `memory.backend` 的显式 switch
装配 store Bean 与 `MemoryService` Bean；取值不在 `markdown | sqlite | mem0` 之内
→ 启动即报错（列出合法取值）。

**Rationale**：与 `ProviderAutoConfiguration` 完全同构（同一仓库先例：构造期
validate + 显式映射 + 清晰报错）；宪法「必填项与格式校验，缺失或非法必须清晰报错，
不静默失败」。

**Alternatives considered**：*`@ConditionalOnProperty` 多个 Bean* — backend 拼错时
会静默地一个 store 都不装配或装配多个，报错信息远不如显式 switch 清晰。

## 8. MemoryTools 的形态与工具池接入

**Decision**：`MemoryTools` 持 `MemoryService`，暴露 `saveTool()` / `recallTool()`
两个 `NivroTool`（**手写内部类 + 手写 JSON Schema 字符串**，同 `HttpTools`
写法），**不标 `@Tool`**。`ChatCommand` 工具池由
`Map.of("http_get", ...)` 扩为四项。

**Rationale**：**用户 2026-09-30 裁决**——技术方案 §5.1 与 AiProgrammingGuide §4.3
写「用 `@Tool` 注解」，颗粒度文档 §2.2-4 写「US-4 引入 `@Tool` 注解扫描后改标注」，
两者字面矛盾；裁决为**按颗粒度文档执行**。依据：`@Tool` 的全部价值是「自动注册到
`ToolRegistry`」，而 `ToolRegistry` 是 US-4 交付物（AiProgrammingGuide §4.4），
US-3 标注等于给一个不存在的东西标注；且 US-2 已交付的 `HttpTools` 就是手写
`NivroTool`，不引入第二套工具编写机制。

**Alternatives considered**：*US-3 即标 `@Tool`* — 已裁决否决（见上）；且会让
`nivroos-memory` 提前引入 Spring AI 依赖，与 §6 的零新增坐标冲突。

## 9. 测试策略与 mock 手法

**Decision**：沿用仓库既有先例——

- **Markdown 档**：`@TempDir` 临时文件（`memoryFile()` 助手），不碰工作区真实
  `.nivroos/`。
- **SQLite 档**：测试内自建 `DataSource` 指向临时/内存库 + 执行 `schema.sql` 的
  `memory_entries` 建表语句；参照 `nivroos-storage` 的 `StorageTestConfiguration`
  先例。
- **Mem0 档**：构造注入 `HttpClient`，测试 `mock(HttpClient.class)` +
  `mock(HttpResponse.class)` + `when(client.send(any(), any(BodyHandler.class)))`
  ——与 `HttpToolsTest` 逐字同法，**不碰真实网络**。
- **真模型冒烟**：Demo 二人工验收，不进 CI（同 `ProviderSmokeIT` 的环境守卫 IT
  先例：默认 `@Disabled` 或被环境守卫跳过）。

**Rationale**：颗粒度文档 §4.1 规定「单测默认全量执行、不碰真实网络；Mem0 档 mock
HTTP 客户端」；`HttpToolsTest` 已证明 Mockito 可 mock `java.net.http.HttpClient`
抽象类（Boot 默认 mock maker 支持）。

**Alternatives considered**：*为 Mem0 档自建窄 HTTP 接口* — 更易测，但 `HttpTools`
已确立「注入 JDK `HttpClient`」的先例，另立接口属新建机制，且会让生产代码多一层
仅为测试存在的抽象。

## 10. 关键回归测试的落地形态

**Decision**：颗粒度文档 §4.3 三段代码**原样落地**，方法名译英文、文档原文进
`@DisplayName`：

| 文档方法名 | 落地方法名 | 落位测试类 |
| --- | --- | --- |
| `load_coreSectionNeverTruncated` | `load_coreSectionNeverTruncated` | `MarkdownMemoryStoreTest` |
| `load_rereadsFileAfterAppend` | `load_rereadsFileAfterAppend` | `MarkdownMemoryStoreTest` |
| `build_includesMemoryContent` | `build_includesMemoryContent` | `PromptBuilderTest` |

**Rationale**：module-dev 步骤 6 硬性要求「写出代码的关键回归测试必须原样落地
（断言逻辑逐条保真）」。三处方法名本身已是英文，直接沿用即可，`@DisplayName`
保留中文验收点原文。

**注意**：`load_coreSectionNeverTruncated` 的断言
`loaded.split("归档条目").length - 1 < 200` 依赖归档区在 4000 字符阈值下**确实触发
截断**——200 条「归档条目 N」加日期 header 远超 4000 字符，前提成立（用户
2026-09-30 裁决阈值为 4000）。
