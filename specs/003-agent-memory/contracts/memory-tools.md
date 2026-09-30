# 契约：Memory 工具（US-3 对模型可见的接口）

> 两个内置 Tool 的 Function Calling 契约。工具名与参数名是**已定字面量**，
> 实现必须逐字保真（module-dev 软门禁 2）。`AGENT.md` 的 `tools:` 列表按名引用。

## `save_memory`

**描述**（进模型上下文）：写入长期记忆（核心区每轮全量注入且永不截断；归档区可被关键词检索）。

**输入 Schema**：

```json
{
  "type": "object",
  "properties": {
    "content": {
      "type": "string",
      "description": "要记住的内容"
    },
    "scope": {
      "type": "string",
      "enum": ["CORE", "ARCHIVAL"],
      "description": "写入分区：CORE 核心区（长期有效、每轮注入、永不截断），ARCHIVAL 归档区（可检索、超限截断）。省略时按 ARCHIVAL"
    }
  },
  "required": ["content"]
}
```

**行为契约**：

| 输入 | 结果 |
| --- | --- |
| `content` + `scope: CORE` | 追加到**核心区**；下次 `load` 立即可见（不缓存） |
| `content` + `scope: ARCHIVAL` | 追加到**归档区** |
| `content`，省略 `scope` | 追加到**归档区**（默认，FR-006） |
| `content` 缺失 / 空 | 返回失败结果（`success=false`），落失败审计 |
| `scope` 取值非法 | 返回失败结果；**不静默按默认处理** |
| 存储 IO 失败 | **上抛** → `ToolExecutor` 落失败审计（`success=false` + `error_message`），模型得知未记住（FR-012） |

**审计**：本工具不做任何审计写入——由既有的 `ToolExecutor` 机制覆盖
（成功与失败都落 `tool_invocations`）。**本模块不新增审计路径**。

## `recall_memory`

**描述**（进模型上下文）：按关键词检索归档记忆（核心区每轮已全量注入，不参与检索）。

**输入 Schema**：

```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "检索关键词"
    }
  },
  "required": ["query"]
}
```

**行为契约**：

| 输入 | 结果 |
| --- | --- |
| `query` 命中归档区 | 返回命中行（多行以换行连接） |
| `query` 无命中 | 返回**「无匹配」文案**（不返回编造内容，不返回空串） |
| `query` 只命中核心区内容 | **不返回**（核心区不参与检索，FR-009） |
| `query` 缺失 / 空 | 返回失败结果（`success=false`） |

**检索语义**：**关键词**匹配，不做分词、不做同义词扩展、不做语义改写（技术方案
§5.1 契约④）。`Mem0` 后端例外——其 `/search` 是语义检索，技术方案 §5.1 已承认
该档为预留升级方向。

## `AGENT.md` 中的引用方式

```yaml
tools:
  - read_file
  - save_memory
  - recall_memory
```

> 工具名必须与上表两个标题逐字一致。`AgentLoader` 不做工具名校验（按名容错解析，
> US-2 既有语义），拼错则该工具不进入本轮工具池。
