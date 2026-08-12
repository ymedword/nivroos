# 整体架构

NivroOS 基于 Java 21 和 Spring Boot 构建。架构将接入、执行、能力和持久化分层，让不同触发源最终走同一条 Agent 链路。

<ArchitectureDiagram :is-zh="true" />

## 运行链路

```text
CLI / REST API / Scheduler
            |
       AgentService
            |
        ReActLoop
       /    |    \
  Provider Memory  ToolExecutor
                    |
              Sandbox + MCP
```

CLI 消息、REST 请求和定时任务最终汇入 `AgentService`。运行时统一构建上下文、调用 Provider、通过 ToolExecutor 执行工具，并持久化结果。

## 核心分层

### 接入层

接入层接收来自 CLI、Web Service 或 Scheduler 的任务。Channel 负责消息进入和响应输出，不负责 Agent 执行算法。

### 引擎层

`ReActLoop`、`PromptBuilder`、`ToolExecutor` 和 `AgentService` 构成运行时核心。循环会判断返回最终结果，还是继续发起下一次工具调用。

### 能力层

Provider、Memory 和 Tool 是可共享的底层服务。多个 Agent 可以复用它们，而不必把 Provider 或业务集成逻辑重复写进每个 `AGENT.md`。

### 基础层

Profile、Session、SQLite 持久化、配置和密钥加载支撑运行时。Provider Key 等凭证应该通过环境变量注入。

## 安全与可观测性

工具执行在继续前会经过 Sandbox 边界校验。LLM 调用和 Tool 调用从首个版本就具备持久化基础，让执行链路可检查、可追溯，而不是黑盒。
