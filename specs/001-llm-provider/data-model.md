# Data Model: US-1 对接 LLM（Phase 1）

## 1. Provider（供应商，配置实体，非持久化）

LLM 服务的接入配置，来源为 `application.yaml` 的 `nivroos.providers` 段
（契约见 contracts/provider-config.md）。

| 属性 | 类型 | 约束 |
| --- | --- | --- |
| `name` | String | 全局唯一、非空；即 YAML 键 |
| `apiKey` | String | 必填；只允许 `${ENV_VAR}` 占位，明文校验拒绝 |
| `baseUrl` | String | 可选；缺失时走所选 starter 默认端点 |

- 生命周期：配置加载 → 校验（唯一性/占位/必填）→ 注册进显式映射 → 可用。
  无运行时状态机。
- 模型**不属于** Provider 属性——由 Profile 侧指定（技术方案 §8.2 权威裁决）。

## 2. Profile.provider（core 结构，US-1 补充字段）

`Profile` 位于 nivroos-core（技术方案 §10）。本 feature 为其补充 provider 字段，
完整 Profile 由 `AgentLoader.deriveProfile` 派生（US-4 实现），本 feature 只定义结构。

| 属性 | 类型 | 约束 |
| --- | --- | --- |
| `providerName` | String | 必填；必须存在于已注册 Provider 集合（启动校验） |
| `model` | String | 必填；厂商模型标识（如 `deepseek-chat`） |
| `temperature` | Float | 可选，0~2；缺失走厂商默认 |

## 3. LlmCall（审计记录，持久化到 SQLite `llm_calls` 表）

字段与需求文档 §10 完全一致，plan 不增减权威 schema。

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `id` | BIGINT | 主键，自增 |
| `session_id` | VARCHAR | 可空；US-1 阶段为空，US-2 起填充（sessions 表随 US-5） |
| `provider` | VARCHAR | 非空；供应商名称 |
| `model` | VARCHAR | 非空；模型名 |
| `prompt_tokens` | INT | 可空；厂商未返回或调用失败时为空 |
| `completion_tokens` | INT | 可空；同上 |
| `total_tokens` | INT | 可空；同上 |
| `duration_ms` | BIGINT | 非空；实际耗时（含失败调用） |
| `created_at` | TIMESTAMP | 非空；调用完成时间 |

- 写入语义（research §8）：每次调用（含失败）写一条；失败时 token 三列空、
  duration 记实际耗时；错误信息经异常与结构化日志表达（schema 无 error 字段）。
- 不可变：审计记录写入后不更新、不删除（扩展阶段才有审计查询）。

### 建表脚本（nivroos-boot/src/main/resources/schema.sql，唯一真相源；骨架已含占位语句 `SELECT 1;`，本表落地后删除）

```sql
CREATE TABLE IF NOT EXISTS llm_calls (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id         VARCHAR(255),
    provider           VARCHAR(64)  NOT NULL,
    model              VARCHAR(128) NOT NULL,
    prompt_tokens      INTEGER,
    completion_tokens  INTEGER,
    total_tokens       INTEGER,
    duration_ms        BIGINT       NOT NULL,
    created_at         TIMESTAMP    NOT NULL
);
```

> 表结构后续演进按 CLAUDE.md 陷阱表：手工维护本脚本或引入 Flyway，不得依赖
> `hibernate.ddl-auto=update` 自动迁移。

## 4. 内部调用类型（nivroos-core/model，非持久化）

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `Message` | `role`、`content` | 对话消息（system/user/assistant/tool） |
| `ChatRequest` | `List<Message>`、会话标识（可空） | 一次 LLM 调用请求 |
| `ChatResponse` | `content`、`List<ToolCallRequest>`、`usage` | 一次调用响应；toolCalls 只描述不执行 |
| `ToolCallRequest` | `name`、`arguments`（JSON 字符串） | 模型请求的工具调用（FR-008 透传产物） |
| `Usage` | `promptTokens`、`completionTokens`、`totalTokens` | token 用量，字段可空 |

## 5. 关系

```text
Profile.providerName ──引用──> Provider.name（启动校验必须存在）
LlmCall.session_id ──关联──> Session（US-2 后；SQLite 轻量，不建外键强约束）
```

- 单次 `ProviderService.call` 恰产生一条 LlmCall（无论成败）。
- 多个 Profile 可引用同一 Provider（多 Agent 共享供应商，宪法原则三的映射表保证
  路由唯一）。
