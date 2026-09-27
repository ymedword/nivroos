# US-3 模块执行颗粒度文档：Memory 三层记忆（让 Agent 记得住）

> 裁决声明：本文档是 `/module-dev` 的输入。内容从《NivroOS 技术方案》提炼，与
> 最新技术方案冲突时以技术方案为准（冲突必须停下报告，不得自行裁决）。
> 前序交付物（US-1：ProviderService/审计接口；US-2：ReAct 循环/Session/
> PromptBuilder 占位点/工具池）已就位，本文档直接引用并单列改造点。

## 1. 模块概述

- **定位**：Agent 跨对话保留状态的三层记忆统一门面——会话记忆（复用 US-2 的
  SessionManager）、长期记忆（MEMORY.md 文件，核心阶段极简版）、情景记忆
  （扩展阶段）。ReAct 循环只面对一个 `MemoryService` 接口。
- **价值**：用一段时间后 Agent 自然记住用户偏好、项目信息、关键决策，下次
  对话不需要重新解释——Agent OS 区别于 chatbot 的核心体验（需求文档 §5.5）。
  验收锚点 Demo 二：跨对话记偏好。

## 2. 设计要点与约束

### 2.1 职责边界

| 本模块职责 | 非本模块职责 |
| --- | --- |
| 统一门面（会话 + 长期，ReAct 只见 MemoryService） | 会话存储实现 → SessionManager（US-2 已交付） |
| 长期记忆读写（append/load/recallByKeyword） | 情景记忆 → 扩展阶段 |
| 两个内置 Tool（save_memory / recall_memory） | 工具注册体系 → ToolRegistry（US-4） |
| 三档后端（markdown 默认 / sqlite / mem0 自托管） | 向量检索 → 扩展阶段（或 Mem0 后端承担） |

### 2.2 关键约束

1. **四条行为契约**（技术方案 §5.1，所有后端实现共同遵守）：
   ① 不缓存——每次重新读文件/查库/调 API（Agent save 后下一轮 load 立即可见）；
   ② 核心记忆区永不被截断，截断只作用在归档区；
   ③ 写核心还是归档由 Agent 经 `scope` 显式指定，系统不猜；
   ④ recall 是关键词检索，不做复杂化。
2. **核心阶段不做自动抽取**：分区完全由 Agent 通过 save_memory 的调用时机与
   scope 参数手动决定（信号驱动升级原则；自动提炼放扩展阶段）。
3. **三档后端一次交付，靠配置切换**（`memory.backend`）：换后端只改一行配置，
   MemoryService 以上（PromptBuilder/MemoryTools/ReActLoop）一个字不动——
   接口墙的价值兑现。Mem0 档必须**自托管**（数据不出域），凭证走环境变量。
4. **MemoryTools 不新建机制**：作为 NivroTool 实现进 US-2 的简化工具池
   （US-4 引入 @Tool 注解扫描后改标注）；经 ToolExecutor 执行自动落
   tool_invocations 审计（US-2 已有机制，无需新增）。
5. **MEMORY.md 不走 Sandbox**：文件路径系统固定（`.nivroos/memory/MEMORY.md`），
   非用户任意路径；宪法六的 Sandbox 清单不含 MemoryTools（技术方案 §6.2）。
6. **缓存语义**（易漏维度）：Markdown 档每次 load 重读文件、SQLite 档每次查库、
   Mem0 档每次调 API——修改后下一轮立即生效；扩展阶段才在门面后加缓存。
7. **配置占位符语义**（易漏维度，US-1 实测教训）：`mem0.api-key` 只允许
   `${ENV_VAR}` 占位（双通道：环境变量或 nivroos-secrets.yml）；明文检查按
   原始配置值、缺失检查按绑定值；Boot 绑定器不解析占位符，使用前必须
   `resolvePlaceholders`。
8. **可观测性双轨**（易漏维度）：存储 IO 失败必须记 WARN 日志（审计由
   ToolExecutor 机制覆盖，日志是本模块新增职责）；catch 不吞。

### 2.3 核心逻辑（Memory 注入与读写链路：流程图 + 伪代码）

```text
[每轮 Prompt 组装（US-2 PromptBuilder 的 Memory 占位点接入）]
PromptBuilder.build
  └─ MemoryService.loadContext(session)
       ├─ 会话历史 ← SessionManager（US-2，无变化）
       └─ 长期记忆 ← LongTermMemoryStore.load()
            ├─ 核心区全量（永不被截断）
            └─ 归档区截断后内容（阈值见待决事项）

[Agent 主动读写（经 ToolExecutor，US-2 机制）]
模型调用 save_memory(content, scope)
  └─ MemoryTools.save → store.append(content, scope)   ← scope 缺省 ARCHIVAL
       ├─ markdown 档：APPEND 写 MEMORY.md 对应分区（带日期 header）
       ├─ sqlite 档：INSERT memory_entries
       └─ mem0 档：POST /add
模型调用 recall_memory(query)
  └─ MemoryTools.recall → store.recallByKeyword(query)  ← 只搜归档区
       ├─ markdown 档：String.contains 行匹配
       ├─ sqlite 档：LIKE
       └─ mem0 档：POST /search
```

```text
load():                        # LongTermMemoryStore 契约，所有后端同语义
    core = readCoreSection()   # 核心区全量，永不截断
    archival = readArchivalSection().truncate(maxChars)   # 只截断归档区
    return core + archival

save(content, scope):          # scope ∈ {CORE, ARCHIVAL}，默认 ARCHIVAL
    validate(scope)
    appendToSection(scope, dateHeader + content)   # 不缓存：下一轮 load 立即可见
```

> 伪代码中的方法名/签名与 §3.1 交付物清单逐字一致（实施时按此落地，不得发明签名）。

### 2.4 差异裁决注

需求文档 §5.5 旧表述"启动时 MEMORY.md 整个文件注入 + 超 4000 字截断"被技术
方案 §5 覆盖：**每次组装 prompt 时 load**（契约①不缓存）、**核心/归档双分区**
（核心不截断）、三档后端。截断阈值本身技术方案未规定 → 见文末待决事项。

## 3. 交付物清单

### 3.1 代码

| 模块 | 交付物 |
| --- | --- |
| nivroos-memory | `MemoryService`（统一门面：`loadContext(Session)` 供 PromptBuilder、`save(String, MemoryScope)` / `recall(String)` 供 Tools 委托；会话历史委托 SessionManager）；`MemoryScope` 枚举（CORE / ARCHIVAL）；`LongTermMemoryStore` 接口（`append(String, MemoryScope)` / `load()` / `recallByKeyword(String)`）；`MarkdownMemoryStore`（默认后端：`## 核心记忆` / `## 归档记忆` 分区、日期 header、归档区字符串截断、contains 行匹配、无缓存重读）；`SqliteMemoryStore`（`memory_entries` 表、LIMIT 截断、LIKE 检索、`WHERE scope` 全量取核心区）；`Mem0MemoryStore`（自托管 Mem0 的 REST 集成，JDK HttpClient 零新依赖，add/get/search 映射）；`MemoryTools`（`save_memory` / `recall_memory` 两个 NivroTool；US-4 起标注 @Tool 换注册机制）；`MemoryProperties`（`memory.backend` 绑定 + mem0 配置校验，占位符双通道规则同 US-1）；`MemoryConfiguration`（按 backend 装配 store Bean + MemoryService） |
| nivroos-storage | `schema.sql` 追加 `memory_entries` 建表语句（SqliteMemoryStore 用，手工维护） |
| nivroos-boot | `application.yml` 增加 `memory.backend: markdown` 段与 mem0 配置占位 |
| 前序改造点（软门禁，见 §3.2） | 见下 |

### 3.2 前序改造点（软门禁：实施时停下报告）

1. **`PromptBuilder`（US-2 已交付）构造签名扩展**：注入 `MemoryService`，
   build 四部分中"Memory 注入"占位点替换为 `memoryService.loadContext(session)`
   实际内容；US-2 的 PromptBuilderTest 同步更新（mock MemoryService）。
2. **`ChatCommand`（US-2 已交付）工具池扩展**：Map 增加
   `save_memory` / `recall_memory` 两个工具；ChatCommand 构造注入
   `MemoryService`（供 MemoryTools 委托）。

### 3.3 配置

```yaml
# application.yml —— Memory 后端选择（markdown 默认）
memory:
  backend: markdown     # markdown | sqlite | mem0
  archive-max-chars: 4000   # 归档区截断阈值（待决事项默认建议，可配置）
  # mem0:                # 仅 backend=mem0 时需要
  #   url: http://localhost:8080
  #   api-key: ${MEM0_API_KEY}
```

```markdown
<!-- .nivroos/memory/MEMORY.md 结构（MarkdownMemoryStore 默认后端） -->
## 核心记忆

### 2026-08-31

- 用户项目使用 Spring Boot，部署在 K8s 上

## 归档记忆

### 2026-08-31

- 上次讨论过使用 SQLite 作为本地存储
```

### 3.4 数据表

`memory_entries`（SqliteMemoryStore 档；手工建表脚本，随 schema.sql 维护）：

```sql
CREATE TABLE IF NOT EXISTS memory_entries (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    scope      VARCHAR(16) NOT NULL,   -- CORE | ARCHIVAL
    content    TEXT        NOT NULL,
    created_at TIMESTAMP   NOT NULL
);
```

## 4. 验收测试（Harness）

### 4.1 测试分层

- **单测**（默认全量执行）：不碰真实网络。Markdown 档用 `@TempDir` 临时文件；
  SQLite 档用内存库；Mem0 档 mock HTTP 客户端。
- **Demo 二冒烟**：真模型人工验收（第 6 节），不进 CI。

### 4.2 测试类与验收点映射

| 测试类 | 验收点 |
| --- | --- |
| `MarkdownMemoryStoreTest` | append 按 scope 进对应分区（缺省 ARCHIVAL）；load 核心全量 + 归档截断；**核心区永不被截断**（关键回归）；recallByKeyword 只搜归档区；**无缓存重读**（改文件后下次 load 立即生效，关键回归） |
| `SqliteMemoryStoreTest` | 建表可写可读；scope 过滤正确；LIKE 检索；归档 LIMIT 截断 |
| `Mem0MemoryStoreTest` | add/get/search 映射正确（mock HTTP）；凭证缺失报错清晰（占位符规则） |
| `MemoryServiceTest` | 门面委托正确（会话历史来自 SessionManager、长期记忆来自 Store）；loadContext 拼接顺序（会话历史在前、长期记忆在后） |
| `MemoryToolsTest` | save_memory 委托 store.append（scope 参数传递）；recall_memory 委托 recallByKeyword；输入 schema 正确 |
| `PromptBuilderTest`（US-2 改造点同步） | 四部分含记忆注入内容（mock MemoryService 返回的内容出现在请求消息中） |
| `MemoryPropertiesTest` | backend 枚举校验；mem0.api-key 明文拒绝（占位符双通道规则） |

### 4.3 关键回归测试

```java
// 验收点：核心记忆区永不被截断——归档区塞爆后核心区依然完整
@Test
@DisplayName("核心记忆区永不被截断：归档区超限后核心区完整返回")
void load_coreSectionNeverTruncated() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());
    store.append("核心偏好：使用 Spring Boot", MemoryScope.CORE);
    for (int i = 0; i < 200; i++) {
        store.append("归档条目 " + i, MemoryScope.ARCHIVAL);   // 塞爆归档区
    }

    String loaded = store.load();

    assertThat(loaded).contains("核心偏好：使用 Spring Boot");   // 核心区完整
    assertThat(loaded.split("归档条目").length - 1).isLessThan(200);  // 归档区被截断
}
```

```java
// 验收点：不缓存——save 之后下一次 load 立即可见（契约①）
@Test
@DisplayName("不缓存：save 后下一次 load 立即读到新内容")
void load_rereadsFileAfterAppend() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile());
    store.append("第一版偏好", MemoryScope.CORE);

    assertThat(store.load()).contains("第一版偏好");

    store.append("第二版偏好", MemoryScope.CORE);

    assertThat(store.load()).contains("第二版偏好");   // 无缓存，重读生效
}
```

```java
// 验收点：Memory 注入链路——PromptBuilder 组装包含记忆内容（US-2 改造点闭环）
@Test
@DisplayName("Prompt 四部分含 Memory 注入：记忆内容出现在请求消息中")
void build_includesMemoryContent() {
    MemoryService memory = mock(MemoryService.class);
    when(memory.loadContext(any())).thenReturn(List.of(new Message("system", "核心偏好：Spring Boot")));

    PromptBuilder builder = new PromptBuilder(contextLoader, memory);
    var request = builder.build(sessionWithHistory(), List.of());

    assertThat(request.messages().stream().map(Message::content))
        .anyMatch(content -> content.contains("Spring Boot"));
}
```

> 方法名英文 + `@DisplayName` 保留中文验收点；SQLite 内存库与 @Sql 用法沿用
> US-2 存储测试的既有写法。

## 5. 范围边界

| 核心阶段不做 | 依据 |
| --- | --- |
| 自动抽取（对话结束自动提炼记忆） | 技术方案 §5.1/§5.5 |
| 内置向量库 / 语义检索（关键词匹配即可） | 技术方案 §5.5 |
| 情景记忆（第三层） | 技术方案 §5.5 |
| Memory Wiki（结构化 claim/evidence、矛盾检测） | 技术方案 §5.5 |
| 记忆压缩（超长简单截断，总结压缩放扩展） | 技术方案 §5.5 |
| 知识图谱后端 | 技术方案 §5.5 |
| 门面层缓存（扩展阶段加 cache + 失效机制） | 技术方案 §5.3 |
| 会话落库（SessionManager 内存版保持，US-5 换 SQLite） | US-2 交付边界 |

## 6. 验证与验收

1. **全量门禁**：`mvn clean verify` 全绿（含 US-1/US-2 全部测试回归绿——改造点
   涉及前序测试更新）；本模块无新增第三方依赖（spring-jdbc 为 Boot BOM 管理的
   标准依赖，披露于实现报告；Mem0 档用 JDK HttpClient）。
2. **易漏维度自查**：缓存语义（关键回归 2）、失败回填（继承 US-2 机制）、
   可观测性双轨（存储 IO 失败 WARN 日志）、配置占位符（Mem0 凭证双通道）。
3. **Demo 二人工验收（真模型）**：
   - 第一轮对话："我的项目用 Spring Boot，部署在 K8s 上"→ Agent 主动调
     `save_memory`（核对 MEMORY.md 出现该条 + tool_invocations 审计 success=1）；
   - 重启进程或新开会话 → 第二轮问"帮我看看我的项目能用什么数据库"→ Agent
     回复中引用此前记的偏好（Spring Boot/K8s 语境）；
   - 核对 `recall_memory` 关键词检索命中归档区、核心区不参与检索。
4. **后端切换验证**：`memory.backend: sqlite` 重启后同一偏好仍可读（memory_entries
   落库）；Mem0 档留待自托管服务就绪后验证（人工项）。

## 待决事项

| 事项 | 说明 | 默认建议 |
| --- | --- | --- |
| 归档区截断阈值 | 技术方案只规定"归档区截断"未给数值（需求文档旧表述 4000 字） | `memory.archive-max-chars: 4000`（沿用需求文档数值，配置可调），实施前用户确认 |
| Mem0 自托管地址与部署方式 | 核心阶段交付代码，实际接入需自托管 Mem0 服务 | 未就绪时 mem0 档仅单测覆盖，人工验证放服务就绪后 |
