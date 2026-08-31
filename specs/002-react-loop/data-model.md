# Data Model: US-2 ReAct 循环（Phase 1）

## 1. Session（会话，内存态）

US-2 内存版，字段与需求文档 §10 sessions 表一致（US-5 落库时直接映射）。

| 属性 | 类型 | 约束 |
| --- | --- | --- |
| `sessionId` | String | 唯一；= channel + ":" + userId + ":" + profileName，**只在 SessionManager 一处拼接** |
| `profileName` | String | 非空；关联 Profile |
| `channel` | String | 非空；核心阶段取值 cli |
| `userId` | String | 非空；CLI 场景固定为本地用户标识 |
| `messages` | List\<Message\> | 有序累积（复用 US-1 的 Message 类型；含 user/assistant/tool 角色） |
| `createdAt` / `lastActiveAt` | LocalDateTime | 创建/活跃时间 |

- 状态：active（归档语义留 US-5）。
- 截断语义：PromptBuilder 组装时按 max_history_turns 截断**视图**，Session 内
  全量累积不清除（可审计）。

## 2. Profile 增补（前序改造点 2）

US-1 已交付 `name` / `providerName` / `model` / `temperature`，本模块增补：

| 属性 | 类型 | 默认 |
| --- | --- | --- |
| `tools` | `List<String>` | 空列表 |
| `settings.maxIterations` | int | 10 |
| `settings.maxHistoryTurns` | int | 20 |

校验（AgentLoader 简化版）：providerName 必须已注册（复用 US-1 校验路径）；
工具名列表不做注册校验（工具池解析时按名容错）。

## 3. ToolInvocation（审计，持久化到 SQLite tool_invocations 表）

字段与需求文档 §10 完全一致（**含 success/error_message 两列**，与 llm_calls
不同，以需求文档 schema 为准）。

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `id` | BIGINT | 主键，自增 |
| `session_id` | VARCHAR | 非空（本模块起填充，来自 ToolExecutor 参数） |
| `tool_name` | VARCHAR | 非空 |
| `input_json` | TEXT | 可空 |
| `result_json` | TEXT | 可空（失败/Sandbox 拒绝时为 null） |
| `success` | BOOLEAN | 非空 |
| `error_message` | TEXT | 可空（失败时含原因，Sandbox 拒绝含拒绝域名） |
| `duration_ms` | BIGINT | 非空 |
| `created_at` | TIMESTAMP | 非空 |

- 写入语义：成功/失败/Sandbox 拒绝三种路径都写（宪法原则五）。
- 写入路径：`ToolExecutor` → `ToolInvocationStore.record(...)`（接口在 core，
  JPA 实现在 storage，依赖倒置）。

### 建表脚本（nivroos-boot/src/main/resources/schema.sql 追加，幂等）

```sql
CREATE TABLE IF NOT EXISTS tool_invocations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    VARCHAR(255),
    tool_name     VARCHAR(64)  NOT NULL,
    input_json    TEXT,
    result_json   TEXT,
    success       BOOLEAN      NOT NULL,
    error_message TEXT,
    duration_ms   BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL
);
```

## 4. 关系

```text
Session 1 ──── N llm_calls.session_id          （US-1 表，本模块起填充）
Session 1 ──── N tool_invocations.session_id   （本模块新增）
Profile.tools ──按名解析──> Map<String, NivroTool> 工具池
ProfileContext (ThreadLocal) ──> 当前 Profile（循环/工具执行读取，finally 清理）
```
