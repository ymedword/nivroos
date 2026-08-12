# NivroOS 是什么？

NivroOS 是面向企业团队的开源 Agent OS，为业务 Agent 提供统一的运行时底座：模型接入、推理循环、记忆、工具、渠道和 REST 集成。

它面向企业自己的 Kubernetes 集群或服务器设计。企业数据留在团队控制的基础设施内，多个 Agent 共享一套一致的运行与治理方式。

## 五大核心能力

### 1. LLM Provider 抽象

通过统一抽象接入主流大模型。Agent 定义不需要感知当前使用哪个模型，团队可以切换 Provider，而不用重写业务逻辑。

### 2. 自实现 ReAct 循环

NivroOS 自己控制 Reason–Act–Observe 循环：模型分析任务，在需要时调用工具，观察结果后继续推理，直到输出最终结果。

### 3. 分层记忆

会话历史与长期 `MEMORY.md` 协同工作，让 Agent 在多轮对话和不同项目之间保留有用上下文。后续可以继续扩展情景记忆。

### 4. 可扩展工具

内置文件、Shell、HTTP、Memory 和通知工具，也可以接入可复用的 MCP Server，或使用 Java `@Tool` Bean 做深度集成。

### 5. REST Service

通过 REST API 对外开放会话、Agent、Memory、Tool 和健康信息，已有业务系统无需耦合运行时内部实现即可接入。

## 一个目录就是一个 Agent

一个 Agent 从目录和 `AGENT.md` 开始。Frontmatter 描述运行时 Profile，正文描述 Agent 指令；需要时可以继续加入 Skills、脚本、参考资料和 MCP Server。

这样可以保持清晰边界：业务团队描述 Agent 应该做什么，NivroOS 提供让它运行起来的底座。

## 面向企业环境设计

- 部署在企业自己的 Kubernetes 或服务器上
- Provider 与 Tool 边界明确
- 对文件路径、Shell 命令、HTTP 域名进行 Sandbox 校验
- Session 以及 LLM/Tool 调用具备持久化和审计基础
- 基于 Java 和 Spring Boot，适配既有企业技术体系
