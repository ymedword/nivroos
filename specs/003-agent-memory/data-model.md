# Data Model: US-3 Memory 三层记忆（Phase 1）

> 落位（**用户 2026-09-30 裁决，修正颗粒度文档 §3.1 的模块列**）：
> `MemoryScope` / `LongTermMemoryStore` / `MemoryService` 三类落 **`nivroos-core`**；
> 三个后端实现 + `MemoryTools` + `MemoryProperties` + `MemoryConfiguration` 落
> **`nivroos-memory`**。理由见 [plan.md](./plan.md) 「Structure Decision」——
> `nivroos-memory` 依赖 `nivroos-core`，而 core 的 `PromptBuilder` 必须持有
> `MemoryService`，反向引用会构成 Maven 循环依赖；故按仓库既有依赖倒置先例
> （`LlmCallStore` 接口在 core / `JpaLlmCallStore` 在 storage）把消费方所需的
> 抽象与门面留在 core。
> 签名逐字对齐颗粒度文档 §3.1 交付物清单与 §2.3 伪代码；返回类型凡文档未定的，
> 在本章定稿并在 research.md 记录理由。

## 1. MemoryScope（枚举）

**落位**：`nivroos-core`（包 `com.nivroos.core.memory`）——`LongTermMemoryStore`
签名引用它，而该接口在 core。

| 值 | 语义 |
| --- | --- |
| `CORE` | 核心记忆区：每轮全量注入、**永不截断**、不参与检索 |
| `ARCHIVAL` | 归档记忆区：可截断、可被关键词检索 |

- **默认值**：写入未指定时为 `ARCHIVAL`（技术方案 §5.1、颗粒度文档 §2.3
  「scope 缺省 ARCHIVAL」）。
- **校验**：`append` / `save` 收到非空且不在枚举内的值 → 报错；收到 `null`
  → 按 `ARCHIVAL` 处理（不在 `MemoryTools` 层报错，因模型可能省略该参数）。

## 2. LongTermMemoryStore（接口，可插拔后端契约）

**落位**：`nivroos-core`（包 `com.nivroos.core.memory`）——消费方 `MemoryService`
在 core，实现方在 `nivroos-memory`，接口必须站在消费方一侧。

**这是技术方案 §5.1 那道「接口墙」的落地处**；三个方法在所有后端上同语义。

| 方法 | 返回 | 契约 |
| --- | --- | --- |
| `append(String content, MemoryScope scope)` | `void` | 追加一条记忆到指定分区，带日期 header；失败抛异常（由调用方决定审计/日志） |
| `load()` | `String` | 核心区**全量** + 归档区**截断后**的内容（顺序：核心在前、归档在后） |
| `recallByKeyword(String query)` | `List<String>` | **只在归档区**做关键词匹配；返回命中的内容行；无命中返回**空列表** |

**四个行为契约（所有实现共同遵守，技术方案 §5.1）**：

1. **不缓存**——每次重新读文件 / 查库 / 调 API；`append` 之后下一次 `load` 立即可见。
2. **核心区永不截断**——截断只作用在归档区。
3. **分区由调用方显式给定**——系统不猜、不自动分类。
4. **`recall` 是关键词检索**——不分词、不同义词扩展、不语义改写。

**返回类型说明**：`load()` 返回 `String`，与颗粒度文档 §2.3 伪代码
（`return core + archival`）及 §4.3 回归测试逐字一致（`String loaded = store.load();`）。
`recallByKeyword` 的返回类型文档未定，本 plan 定为 `List<String>`：命中即内容行
列表，无命中即空列表——**「无匹配」的呈现文案归 `MemoryTools` 负责**，store 不
产出面向用户的措辞（否则三个后端要各写一份文案且无法统一）。

## 3. MarkdownMemoryStore（默认后端，`memory.backend: markdown`）

**载体**：`.nivroos/memory/MEMORY.md`（路径由系统固定，**不走通用文件工具的路径
白名单**——颗粒度文档 §2.2-5、spec FR-015）。`nivroos init` 已生成两分区骨架
（`InitCommand.java:36`）。

**文件格式**（颗粒度文档 §3.3，解析宽容——技术方案 §5.2「格式不做更严格的规定」）：

```markdown
# MEMORY.md

## 核心记忆

### 2026-08-31

- 用户项目使用 Spring Boot，部署在 K8s 上

## 归档记忆

### 2026-08-31

- 上次讨论过使用 SQLite 作为本地存储
```

| 行为 | 规则 |
| --- | --- |
| 分区定位 | 按一级 header `## 核心记忆` / `## 归档记忆` |
| 追加 | 在目标分区下**每条各写一个** `### <yyyy-MM-dd>` header，再写 `- <内容>`（颗粒度文档 §2.3 伪代码 `appendToSection(scope, dateHeader + content)`——同日多条即多个 header，**不做同日合并**；这是 §4.3「核心区永不被截断」回归测试成立的前提：200 条归档 ≈ 5.5K 字符 > 4000 阈值才触发截断，若同日合并则仅 ≈1.9K 字符，断言 `isLessThan(200)` 必挂） |
| 核心区 | 全量返回，**不截断** |
| 归档区 | 按 `memory.archive-max-chars` 做**字符串截断，保留最新、丢弃最旧**（research §5） |
| 检索 | 归档区内 `String.contains` **行匹配**，返回记忆正文（剥离 `- ` 列表符号，与 SQLite 档 `content` 列同形——跨后端语义一致）；核心区不参与；**不做截断**（截断只属 `load` 的注入预算，检索是定向查旧，三档一致：SQLite 档 `LIKE` 亦无 LIMIT） |
| 容错 | 文件不存在 / 空文件 → 视为空记忆（FR-020）；分区 header 缺失 → 追加时按需补建；不引入 Markdown 解析库 |
| 并发 | 写入走同步互斥（进程内锁），保证虚拟线程并发下不丢写、不损坏文件 |

## 4. SqliteMemoryStore（`memory.backend: sqlite`）

**表 `memory_entries`**（颗粒度文档 §3.4 逐字，追加进
`nivroos-boot/src/main/resources/schema.sql`，手工维护、幂等）：

```sql
CREATE TABLE IF NOT EXISTS memory_entries (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    scope      VARCHAR(16) NOT NULL,   -- CORE | ARCHIVAL
    content    TEXT        NOT NULL,
    created_at TIMESTAMP   NOT NULL
);
```

| 属性 | 类型 | 约束 |
| --- | --- | --- |
| `id` | INTEGER | 主键，自增 |
| `scope` | VARCHAR(16) | 非空；取值 CORE / ARCHIVAL |
| `content` | TEXT | 非空；一条记忆的正文 |
| `created_at` | TIMESTAMP | 非空；写入时间 |

**访问方式**：`JdbcTemplate`（`spring-jdbc`），注入 `DataSource`（research §2）。
**不建 JPA 实体、不建 Repository**——表结构由 schema.sql 唯一维护。

| 方法 | 实现 |
| --- | --- |
| `append` | `INSERT INTO memory_entries(scope, content, created_at) VALUES (?,?,?)` |
| `load` | 核心区：`WHERE scope='CORE' ORDER BY id` 全量取；归档区：`WHERE scope='ARCHIVAL' ORDER BY id DESC LIMIT ?` 按条数取（LIMIT 由 `archive-max-chars` 折算），输出时**恢复时间正序** |
| `recallByKeyword` | `WHERE scope='ARCHIVAL' AND content LIKE ? ORDER BY id`；`%` / `_` 通配符在上层转义 |

## 5. Mem0MemoryStore（`memory.backend: mem0`，自托管）

**契约**按 research §3（OSS 自托管，**无 `/v1` 前缀**）；实现用 JDK `HttpClient`
（构造注入，便于 mock——同 `HttpTools` 先例）。

| 方法 | Mem0 调用 |
| --- | --- |
| `append` | `POST /memories`，`metadata.scope` 承载分区 |
| `load` | `GET /memories` 按 scope 过滤；核心区全量、归档区客户端截断 |
| `recallByKeyword` | `POST /search` |

**已登记的语义差异**（research §3 第 1~3 条）：① `POST /memories` 会做 LLM
抽取，`append` 非逐字追加；② `search` 是**语义**检索，与另两档的关键词匹配不同
（技术方案 §5.1 已承认，属预留升级方向）；③ 精确路径/字段随 OSS 版本演进，实际
联调以部署实例的 `/openapi.json` 为准（**人工项**，核心阶段只交付代码 + mock 单测）。

## 6. MemoryService（统一门面）

**落位**：`nivroos-core`（包 `com.nivroos.core.memory`）——**具体类，不是接口**。
可插拔点在 `LongTermMemoryStore`（技术方案 §5.1 称其为「后端接口」），门面本身
没有第二实现，做成接口只会多一个空壳。装配由 `nivroos-memory` 的
`MemoryConfiguration` 出 `@Bean`。

**对 ReAct 循环唯一的记忆入口**（技术方案 §5.1 架构调整说明；宪法 review 清单
「Memory 没有被简化成与 Session 合并」的落点）。

| 方法 | 返回 | 契约 |
| --- | --- | --- |
| `loadContext(Session session)` | `List<Message>` | **会话历史（按 `max_history_turns` 截断）在前 + 长期记忆（一条 system 消息）在后** |
| `save(String content, MemoryScope scope)` | `void` | 委托 `store.append`；失败**上抛** |
| `recall(String query)` | `List<String>` | 委托 `store.recallByKeyword`；无命中返回空列表 |

**会话历史的来源与截断**：委托 `SessionManager`；截断逻辑（US-2 的
`PromptBuilder.truncateHistory`，保留最近 `max_turns * 2` 条）**随本模块迁入**，
`max_history_turns` 仍从 `ProfileContext.current()` 读（research §1）。

**失败语义（用户 2026-09-30 裁决，spec FR-012 / FR-021）**：

- `save` 失败 → **上抛**，由 `ToolExecutor` 落失败审计（`success=false`），
  使模型得知未记住。
- `loadContext` 读取长期记忆失败 → **记 WARN 日志**，按「本轮无长期记忆」继续
  （仅注入会话历史），**不中断整轮对话**；下一轮重试，失败结果不缓存。

## 7. MemoryProperties（配置绑定）

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `memory.backend` | enum | `markdown` | `markdown` \| `sqlite` \| `mem0`；取值不在枚举内 → 启动报错 |
| `memory.archive-max-chars` | int | `4000` | 归档区截断阈值（**用户 2026-09-30 裁决**）；必须为正数 |
| `memory.mem0.url` | String | 空 | 仅 `backend=mem0` 时必填 |
| `memory.mem0.api-key` | String | 空 | 仅 `backend=mem0` 时必填；**只允许 `${ENV_VAR}` 占位** |

**校验规则**（逐条复用 `ProviderProperties` 双通道先例，research §7）：

1. **明文拒绝**——按**原始配置值**判断（绑定值已被 Spring 解析，无法区分来源）；
   例外：值来自本地密钥文件 `nivroos-secrets.yml` 时放行。
2. **占位符未解析**——绑定值仍是 `${...}` 字面量 ⇒ 环境变量未设置，报错**指明
   变量名**。
3. `backend=mem0` 而 `url` / `api-key` 缺失 → 清晰报错。
4. 校验在 `MemoryConfiguration` **构造期**执行，非法配置拒绝启动。

## 8. MemoryTools（两个内置 Tool）

实现 `NivroTool`，**手写内部类 + 手写 JSON Schema 字符串**（同 `HttpTools`），
**不标 `@Tool`**（用户 2026-09-30 裁决，research §8）。构造持 `MemoryService`。

### `save_memory`

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `content` | string | 是 | 要记住的内容 |
| `scope` | string（enum: `CORE` / `ARCHIVAL`） | 否 | 省略时按 `ARCHIVAL` |

### `recall_memory`

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `query` | string | 是 | 关键词 |

- 命中 → 返回命中行（多行以换行连接）；**无命中 → 返回「无匹配」文案**（FR-008
  场景 3；文案由工具层产出，见 §2 返回类型说明）。
- 委托 `memoryService.save` / `memoryService.recall`，因此写入审计由既有
  `ToolExecutor` 机制自动覆盖（FR-011），本模块不新增审计路径。

## 9. MemoryConfiguration（装配）

`@Configuration(proxyBeanMethods = false)` +
`@EnableConfigurationProperties(MemoryProperties.class)`；构造期
`properties.validate(environment)`；按 `memory.backend` 显式 switch：

| backend | 装配的 Bean |
| --- | --- |
| `markdown` | `MarkdownMemoryStore` → `MemoryService` |
| `sqlite` | `SqliteMemoryStore`（注入 `DataSource`）→ `MemoryService` |
| `mem0` | `Mem0MemoryStore`（注入 `HttpClient` + url + 解析后的 api-key）→ `MemoryService` |

`MemoryTools` 由 `ChatCommand` 以注入的 `MemoryService` 构造（颗粒度文档 §3.2-2），
不单独出 Bean。

## 10. 关系

```text
ReActLoop ──uses──> PromptBuilder ──uses──> MemoryService ──┬─ session history ─> SessionManager (US-2)
                                                   │         └─ long-term ─────> LongTermMemoryStore (接口)
                                                   │                                   ├─ MarkdownMemoryStore (默认)
                                                   │                                   ├─ SqliteMemoryStore ─> memory_entries 表
                                                   │                                   └─ Mem0MemoryStore ──> 自托管 Mem0 REST
ChatCommand ──holds──> MemoryTools ──delegates──> MemoryService
ToolExecutor ──executes──> MemoryTools（审计落 tool_invocations，US-2 既有机制）
```

- **依赖方向**：`nivroos-memory → nivroos-core`（单向，不反向、不依赖
  `nivroos-storage`）；`nivroos-cli → nivroos-memory`（`ChatCommand` 注入门面 +
  构造 `MemoryTools`）。`nivroos-boot` 已依赖 `nivroos-memory`，无需改动。
- **接口位置**：`MemoryService` / `MemoryScope` / `LongTermMemoryStore` 在
  `nivroos-core`（消费方 `PromptBuilder` 在 core，Maven 依赖方向不允许反向）；
  三个后端实现与装配在 `nivroos-memory`。同 `LlmCallStore`（core）+
  `JpaLlmCallStore`（storage）先例。
