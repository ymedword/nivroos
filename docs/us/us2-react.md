# US-2 模块执行颗粒度文档：ReAct 循环（Agent 大脑）

> 裁决声明：本文档是 `/module-dev` 的输入。内容从《NivroOS 技术方案》提炼，与
> 最新技术方案冲突时以技术方案为准（冲突必须停下报告，不得自行裁决）。
> 前序交付物（US-1：ProviderService / LlmCallStore / model 包 / 异常体系 /
> 显式映射装配）已就位，本文档直接引用。

## 1. 模块概述

- **定位**：Agent 的核心工作机制——输入一条用户消息，输出最终响应，中间按
  Reason + Act 自主循环：调 LLM 思考、按需执行工具、看结果再推理，直到给出
  最终答案或达到迭代上限。
- **价值**：Agent 能自主决定何时调用哪个工具、多步骤任务一次对话内连续完成
  （需求文档 §1.2 能力二）。本模块把 US-1 的单次调用升级为完整闭环，并用
  `nivroos chat` 打通第一个可见入口（Demo 一：查天气穿衣）。

## 2. 设计要点与约束

### 2.1 职责边界

| 本模块职责 | 非本模块职责 |
| --- | --- |
| 循环控制（Reason→Act→再推理，迭代上限） | LLM 调用 → ProviderService（US-1） |
| 消息累积与会话管理（内存版） | 记忆注入 → MemoryService（US-3） |
| 工具调度执行 + Sandbox 检查 + 审计 | 工具注册体系（ToolRegistry）→ US-4 |
| 统一入口（AgentService + ProfileContext） | 定时触发（AgentScheduler）→ US-5 |

### 2.2 关键约束

1. **ReAct 循环必须自实现**（宪法原则一）：核心循环约数十行 Java，不得使用
   Spring AI 的 Agent 抽象；core 模块不得引入 Spring AI 依赖（依赖方向可 grep
   验证）。
2. **tool call 自己检查、自己执行**（宪法原则二）：循环检查响应的
   `ToolCallRequest` 列表 → `ToolExecutor` 执行 → 结果作为 tool 消息回填对话
   历史 → 继续下一轮。US-1 已在调用侧关闭自动执行，本模块不得绕过。
3. **同步阻塞**（宪法原则七）：全程同步，配合虚拟线程；不引入 Reactor /
   CompletableFuture / 自建线程池。
4. **tool_invocations 审计 day one**（宪法原则五）：ToolExecutor 每次执行
   （含 Sandbox 拒绝与执行失败）写入 `tool_invocations` 表——US-2 提前引入
   storage 第二张表（与 US-1 的 llm_calls 同款节奏调整）。
5. **ProfileContext 必须 finally 清理**（ThreadLocal）：虚拟线程由载体线程
   复用，泄漏会串 Agent；`AgentService.process` 里 set 后 finally clear。
6. **Profile 经 ProfileContext 传递**（技术方案 §4.2）：ReActLoop / ToolExecutor
   的方法签名不带 Profile 参数，需要时从 `ProfileContext.current()` 取。
   > 差异裁决注：OryxOS 第 17 节参考文档的 `run(Session, String, Profile)`
   > 显式传 Profile；NivroOS 以技术方案为准走 ThreadLocal 传递（US-1 的
   > `NivroTool.execute` 签名不带 Profile 的同款机制）。
7. **ContextLoader 无缓存**（技术方案 §8.3）：每次组装 prompt 重新读
   AGENT.md 正文与 Bootstrap 文件，修改后下一轮立即生效；Bootstrap 文件
   缺失 WARN 不阻断。
8. **Sandbox 接口先行**（宪法原则六）：`Sandbox.enforce(SandboxAction)` 接口
   签名不携带实现细节；本模块交付 HTTP 域名白名单实现，文件/Shell 白名单在
   US-4 补全（同一实现类扩展，接口不变）。
9. **迭代与上下文上限**：`max_iterations` 默认 10（Profile 可覆盖）、
   `max_history_turns` 默认 20；超长对话简单截断早期消息，总结压缩放扩展。
10. **多 tool call 顺序执行**：一次响应含多个工具调用时逐个执行，不并行
    （技术方案 §4.3）。
11. **LLM 响应字段可空性兜底**（2026-08-31 Demo 实测）：带工具调用的响应
    content 可能为 null；历史/回填消息组装必须兜底空串，否则下一轮请求崩溃。
12. **失败信息回填闭环**（2026-08-31 Demo 实测）：工具执行失败时回填
    errorMessage 而非空内容——模型看不到结果就不会收敛（会空转满迭代上限）。

### 2.3 核心逻辑（ReAct 循环：流程图 + 伪代码）

```text
用户消息
  │
  ▼
Session.append(userMessage)                      ← 先留痕
  │
  ▼
┌───────────── 循环（i < max_iterations，默认 10）──────────────────┐
│                                                                   │
│  1. PromptBuilder 组装（四部分，Memory 占位 US-3 接入）             │
│     system prompt（AGENT.md 正文 + Bootstrap + 日期，无缓存）       │
│     + 对话历史（截断 max_history_turns 轮）+ 工具列表               │
│  2. ProviderService.call ──► llm_calls 审计（成败都落，含 sessionId）│
│  3. 响应 append 进 Session（先留痕再判断）                          │
│  4. 有 tool call？                                                 │
│     ├─ 无 ──► 返回最终响应（循环结束）                              │
│     └─ 有 ──► 逐个顺序执行（不并行）：                              │
│                ToolExecutor.execute(sessionId, call)               │
│                ├─ Sandbox 检查（HTTP 域名白名单，拒绝即失败审计）    │
│                └─ 执行 ──► tool_invocations 审计（成败都落）        │
│                结果回填 Session ──► 回到 1（下一轮）                │
└───────────────────────────────────────────────────────────────────┘
  │
  ▼
达上限 → WARN 日志 + 返回固定提示（返回语义见「待决事项」）
```

```text
run(session, userMessage):
    session.append(userMessage)                     // 用户消息先入会话
    profile = ProfileContext.current()              // Profile 经 ThreadLocal 传递（约束 6）

    for i in 0 ..< maxIterations:                   // 默认 10，防死循环（坑一）
        tools   = resolveTools(profile.tools)       // 按 Profile.tools 名称从工具池解析
        request = promptBuilder.build(session, tools)   // 四部分组装，含 sessionId
        response = providerService.call(profile, request)
                                                    // 写 llm_calls（成败都落，session 关联）
        session.append(response)                    // 先留痕：每轮都留痕迹，事后可审计

        if response.toolCalls.isEmpty():
            return response.content                 // 无工具调用，收尾

        for call in response.toolCalls:             // 多调用顺序执行，不并行（约束 10）
            result = toolExecutor.execute(session.id, call)
                                                    // Sandbox 检查 + 写 tool_invocations
            session.appendToolResult(result)        // 结果回填，进入下一轮

    log.warn("max_iterations reached")
    return "任务执行时间较长，达到最大轮数，已停止继续尝试，当前结果可能不完整。"
                                                    // 死循环兜底（坑一），文案经用户确认（spec Clarifications）
```

> 伪代码中的方法名/签名与 §3.1 交付物清单逐字一致（实施时按此落地，不得发明签名）。

## 3. 交付物清单

### 3.1 代码

| 模块 | 交付物 |
| --- | --- |
| nivroos-core | `Session`（sessionId / profileName / channel / userId / messages / createdAt / lastActiveAt，内存态）；`SessionManager` 接口 + `InMemorySessionManager`（session_id 公式 = channel+user+profile 联合生成，**只在此处拼接**；appendMessage；归档接口留 US-5）；`ReActLoop`（`run(Session, String userMessage) → String`，循环上限 + 消息累积；Profile 从 ProfileContext 取）；`PromptBuilder`（`build(Session, List<NivroTool>) → ChatRequest`，四部分组装；Memory 部分本模块**占位**，US-3 接入）；`ToolExecutor`（`execute(String sessionId, ToolCallRequest) → ToolResult`，**sessionId 供 tool_invocations 关联**；Profile 从 ProfileContext 取；工具池 Map + Sandbox 检查 + ToolInvocationStore 写入）；`AgentService`（`process(Session, String) → String` 统一入口：按 session.profileName 从 ProfileRegistry 取 Profile → ProfileContext.set → ReActLoop → finally clear）；`ProfileContext`（`ThreadLocal<Profile>`）；`ProfileRegistry` 简化版（register / get(name) 内存索引；目录扫描与运行时注册留 US-4）；`ContextLoader` 简化版（`loadSystemPrompt() → String`：AGENT.md 正文 + Bootstrap 三文件 + 末尾当前日期时间；**无缓存每次重读**，Bootstrap 缺失 WARN 不阻断）；`AgentLoader` 简化版（`loadProfile(String agentName) → Profile`：读 `.nivroos/agents/<name>/AGENT.md` frontmatter 派生 + 校验 + 注册进 ProfileRegistry）；`ToolInvocationStore` 接口（审计写入，依赖倒置） |
| nivroos-tool | `Sandbox` 接口（`enforce(SandboxAction)`，ActionType = FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST）；`WhitelistSandbox`（HTTP 域名通配符白名单实现；文件/Shell 白名单 US-4 补全）；`SandboxViolationException`；`HttpTools`（`http_get` NivroTool，execute 开头 `Sandbox.enforce(HTTP_REQUEST, url)`；HTTP 客户端用 JDK `java.net.http.HttpClient`，零新增第三方依赖） |
| nivroos-channel-cli | `CliChannel`（stdin→AgentService.process→stdout 交互循环；`/quit` 退出；维护当前 Session） |
| nivroos-cli | `InitCommand`（`nivroos init`：创建 `.nivroos/` 工作区，幂等不覆盖已存在文件）；`ChatCommand`（`nivroos chat --profile <name> [--message <text>]`） |
| nivroos-storage | `ToolInvocation` 实体 + `ToolInvocationRepository` + `JpaToolInvocationStore`（实现 core 接口）；`ToolInvocationStoreConfiguration`（装配，同 LlmCallStore 先例） |
| nivroos-boot | `application.yml` 增加 `http.allowed_domains` 段（Sandbox HTTP 白名单）；`schema.sql` 增加 `tool_invocations` 建表语句 |

### 3.2 测试（harness 先行，见第 4 节）

`ReActLoopTest`、`PromptBuilderTest`、`ToolExecutorTest`、`AgentServiceTest`、
`SessionManagerTest`、`AgentLoaderTest`、`ContextLoaderTest`、`HttpToolsTest`、
`WhitelistSandboxTest`、`JpaToolInvocationStoreTest`（单测，默认执行）。

### 3.3 配置

```yaml
# application.yml —— Sandbox HTTP 域名白名单（通配符匹配）
http:
  allowed_domains:
    - wttr.in
    - api.github.com
```

```markdown
# .nivroos/agents/weather/AGENT.md —— Demo 一 Agent（nivroos init 后手写）
---
name: weather
description: 查天气穿衣助手
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - http_get
channels:
  - name: cli
    config: {}
settings:
  max_iterations: 10
  max_history_turns: 20
---

你是天气助手。用户问某地天气时，调用 http_get 获取天气数据，
根据温度给出穿衣建议。
```

### 3.5 前序改造点（软门禁：实施时停下报告）

1. **`LlmCallStore.record` 增加 `sessionId` 参数**（US-1 已交付接口的契约扩展）：
   llm_calls.session_id 自 US-1 起可空，本模块起填充——`SpringAiProviderService`
   从 `ChatRequest.sessionId()`（US-1 已定义字段）传入审计。US-1 相关测试同步
   更新（mock verify 参数列表加一）。
2. **`Profile` 增补字段**（US-1 已交付类的字段扩展，非破坏）：`tools`
   （`List<String>`，可用工具名列表）与 `settings`（max_iterations /
   max_history_turns，带默认值 10/20）。`AgentLoader` 简化版解析 frontmatter
   的 tools/settings 写入 Profile。
3. `SpringAiProviderServiceTest` / `ProviderSmokeIT` 的 audit verify 断言随签名
   变化更新（改动限定在 US-1 的 provider 模块测试文件内）。

### 3.6 数据表

`tool_invocations`（九列，字段与需求文档 §10 一致；**含 success/error_message
两列**——与 llm_calls 不同，以需求文档 schema 为准）：

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

## 4. 验收测试（Harness）

### 4.1 测试分层

- **单测**（默认全量执行）：不访问真实网络。mock ProviderService /
  ChatModel / HTTP 客户端，覆盖循环收敛、迭代上限、Prompt 组装、审计落库、
  Sandbox 判定。
- **Demo 一冒烟**：真模型人工验收（第 6 节），不进 CI。

### 4.2 测试类与验收点映射

| 测试类 | 验收点 |
| --- | --- |
| `ReActLoopTest` | 无工具调用单轮返回；有工具调用循环至最终响应（收敛）；达 max_iterations 强制结束且循环次数 = 上限；每轮 LLM/工具结果正确累积进 Session；**工具失败时回填 errorMessage（非空内容）** |
| `PromptBuilderTest` | 四部分齐全：system prompt（AGENT.md 正文 + Bootstrap + 末尾日期时间）、对话历史按 max_history_turns 截断、工具列表经翻译传入 |
| `ToolExecutorTest` | 工具存在 → 执行并落 tool_invocations（success=true）；工具不存在 → 清晰错误；Sandbox 拒绝 → success=false + error_message 落库；执行异常 → 失败留痕不吞 |
| `AgentServiceTest` | process 全链路（ProfileContext 设置）；正常/异常路径 finally 清理 ProfileContext（ThreadLocal 不泄漏） |
| `SessionManagerTest` | session_id 公式唯一生成；消息追加；归档语义 |
| `AgentLoaderTest` | frontmatter 解析正确；provider 缺失/工具未注册报错清晰 |
| `ContextLoaderTest` | 正文 + Bootstrap 拼接；末尾含当前日期时间；**无缓存回归**（改文件后下一次 build 立即读到新内容）；Bootstrap 缺失 WARN 不阻断 |
| `HttpToolsTest` | 白名单通过/拒绝；请求 URL/方法正确 |
| `WhitelistSandboxTest` | 域名精确匹配/通配符匹配/拒绝均正确 |
| `JpaToolInvocationStoreTest` | 建表可写可读；success/error_message 列真实存在且正确落值 |

### 4.3 关键回归测试

```java
// 验收点：循环收敛——第一轮返回工具调用，第二轮返回最终文本
@Test
@DisplayName("有工具调用的循环：执行工具后继续推理并收敛")
void run_withToolCall_convergesAfterToolResult() {
    ProviderService provider = mock(ProviderService.class);
    ToolExecutor executor = mock(ToolExecutor.class);
    Session session = sessionWithHistory();
    // 第一轮：模型要调 http_get；第二轮：给出最终回答
    when(provider.call(any(), any()))
        .thenReturn(responseWithToolCall(new ToolCallRequest("http_get", "{\"url\":\"https://wttr.in/beijing\"}")))
        .thenReturn(responseWithText("北京今天 15 度，建议穿外套"));

    ReActLoop loop = new ReActLoop(provider, new PromptBuilder(...), executor, 10);
    String result = loop.run(session, "查一下北京天气");

    verify(executor, times(1)).execute(any(), any());
    assertThat(result).contains("穿外套");
    assertThat(session.messages()).hasSize(...); // 用户+assistant+tool 消息正确累积
}
```

```java
// 验收点：Sandbox 拒绝 → tool_invocations 落 success=false + error_message
@Test
@DisplayName("Sandbox 拒绝时审计落库 success=false 且含错误信息（宪法原则五/六）")
void execute_sandboxRejected_recordsFailureAudit() {
    Sandbox sandbox = mock(Sandbox.class);
    doThrow(new SandboxViolationException("domain not allowed: evil.com"))
        .when(sandbox).enforce(any());
    ToolInvocationStore audit = mock(ToolInvocationStore.class);
    ToolExecutor executor = new ToolExecutor(Map.of("http_get", mockTool()), sandbox, audit);

    executor.execute(profile(), new ToolCallRequest("http_get", "{\"url\":\"https://evil.com\"}"));

    verify(audit).record(any(), eq("http_get"), any(), isNull(),
        eq(false), contains("evil.com"), anyLong());
}
```

```java
// 验收点：达到迭代上限强制结束（防死循环）
@Test
@DisplayName("持续请求工具的循环在 max_iterations 处强制结束")
void run_neverConverging_stopsAtMaxIterations() {
    ProviderService provider = mock(ProviderService.class);
    when(provider.call(any(), any()))
        .thenReturn(responseWithToolCall(new ToolCallRequest("http_get", "{}")));

    ReActLoop loop = new ReActLoop(provider, new PromptBuilder(...),
        mock(ToolExecutor.class), 3);

    String result = loop.run(session(), "查天气");

    verify(provider, times(3)).call(any(), any());   // 恰好 3 轮，不多不少
    assertThat(result).isNotNull();
}
```

```java
// 验收点：处理中抛异常也必须清掉 ProfileContext——ThreadLocal 泄漏在
// 单请求测试里永远不报错，只在并发复用线程时串号，必须显式钉死
@Test
@DisplayName("process 抛异常时 finally 清理 ProfileContext（虚拟线程复用防串号）")
void process_whenLoopThrows_clearsProfileContext() {
    ReActLoop loop = mock(ReActLoop.class);
    when(loop.run(any(), any())).thenThrow(new RuntimeException("boom"));
    AgentService service = new AgentService(loop, sessionManager(), profileRegistry(), ...);

    assertThrows(RuntimeException.class, () -> service.process(session(), "hi"));

    assertNull(ProfileContext.current());   // finally 没清，下一个复用线程的请求会拿到别人的 Profile
}
```

> 方法名英文 + `@DisplayName` 保留中文验收点；mock 响应构造复用 US-1 测试的
> 既有写法。

## 5. 范围边界

| 核心阶段不做 | 依据 |
| --- | --- |
| 工具调用并行（一次响应多个按顺序执行） | 技术方案 §4.3 |
| 上下文总结压缩（超长简单截断） | 技术方案 §4.3 |
| Agent 间任务委托 | 技术方案 §4.3 |
| 流式响应（SSE） | 技术方案 §4.3 |
| Session SQLite 持久化（本模块内存版，US-5 落库） | AiProgrammingGuide §4.2 |
| ToolRegistry 完整注册体系（本模块 Map 工具池，US-4 替换） | AiProgrammingGuide §4.2 |
| 文件/Shell 白名单（US-4 补全 WhitelistSandbox） | AiProgrammingGuide §4.2 |
| AgentLoader 目录扫描/运行时注册（本模块单 Agent 加载） | 技术方案 §8.2/§11.2 |
| ContextLoader 的 Skill 软连接扫描（本模块仅正文+Bootstrap） | 技术方案 §8.3 |
| Memory 注入（PromptBuilder 本模块占位，US-3 接入 MemoryService） | 技术方案 §4.2/§5 |

## 6. 验证与验收

1. **全量门禁**：`mvn clean verify` 全绿（含 US-1 全部测试回归绿——跨模块契约
   证据）；本模块无新增第三方依赖（HttpTools 用 JDK HttpClient），无需新增
   `-Psecurity` 抑制（有变化则按既有评审流程登记）。
2. **依赖方向核验**：grep 确认 nivroos-core 无 Spring AI import（宪法原则一）；
   ReActLoop 只依赖 core 接口（ProviderService / NivroTool / ToolInvocationStore）。
3. **Demo 一人工验收（SC-005 本模块锚点）**：`nivroos init` → 手写
   `.nivroos/agents/weather/AGENT.md`（§3.3 模板）→ 设 key →
   `nivroos chat --profile weather` 问"查一下北京天气并告诉我穿什么"→
   Agent 经 ReAct 调 `http_get` 拉天气 JSON → 输出穿衣建议；对话日志正确累积；
   `tool_invocations` 与 `llm_calls` 均有审计记录（DeepSeek 或 Kimi 任选一家）。
4. **负向验证**：未知工具报错清晰；Sandbox 拒绝域名（把 `evil.com` 加入对话）
   走失败审计；max_iterations 设为 1 时循环强制结束。

## 待决事项

| 事项 | 说明 | 决议 |
| --- | --- | --- |
| 强制结束的返回语义 | 达 max_iterations 时返回什么（技术方案未规定） | ✅ 已决（2026-08-31 用户确认）：固定提示文案"任务执行时间较长，达到最大轮数，已停止继续尝试，当前结果可能不完整。" + WARN 日志 |
