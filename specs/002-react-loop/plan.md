# Implementation Plan: US-2 ReAct 循环（Agent 大脑）

**Branch**: `002-react-loop` | **Date**: 2026-08-31 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-react-loop/spec.md`

**Note**: 本 plan 由 `/speckit-plan` 生成。生成后须按 AiProgrammingGuide §3.4 做人工
review（清单见文末），review 通过后锁定；`tasks.md` 由 `/speckit-tasks` 生成。

## Summary

实现核心能力二——ReAct 循环：输入用户消息、输出最终响应，中间按 Reason+Act
自主循环（组装 Prompt → 调 LLM → 检查工具调用 → ToolExecutor 执行并回填 →
继续推理，迭代上限兜底）。同步交付统一入口 AgentService（ProfileContext
ThreadLocal 管理）、内存会话、CLI 交互通道与工作区初始化命令，打通第一个可见
入口 Demo 一（查天气穿衣）。本模块零新增第三方依赖（HTTP 客户端用 JDK 自带）；
tool_invocations 审计表随本模块落库（宪法原则五）。

## Technical Context

**Language/Version**: Java 21（宪法约束）

**Primary Dependencies**: 沿用 US-1 锁定矩阵；**本模块零新增第三方依赖**——
HTTP 工具用 JDK `java.net.http.HttpClient`（同步阻塞，原则七）

**Storage**: SQLite 增第二张表 `tool_invocations`（含 success/error_message 列，
需求文档 §10）；Session 内存版（US-5 落库）

**Testing**: JUnit 5 + Mockito（骨架已配）；全部单测不碰网络；Demo 一真模型
人工验收

**Target Platform**: Linux / Windows，JDK 21，单二进制 fat JAR

**Project Type**: Maven 多模块（9 个固定）+ Spring Boot 单体

**Performance Goals**: 无新增目标；循环迭代上限 10 兜底

**Constraints**: 同步阻塞（原则七）；core 模块零 Spring AI 依赖（原则一，
可 grep 验证）；ProfileContext finally 清理；审计成败都落

**Scale/Scope**: 单会话消息随迭代累积；历史截断上限 20 轮

## 前序改造点（颗粒度文档 §3.5 已明确授权，软门禁例外条件成立）

1. `LlmCallStore.record` 增加 `sessionId` 参数（US-1 契约扩展）；provider 从
   `ChatRequest.sessionId()` 传入；US-1 测试断言同步更新。
2. `Profile` 增补 `tools`（`List<String>`）与 `settings`（max_iterations=10 /
   max_history_turns=20）字段（非破坏扩展）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查点 | 结论 |
| --- | --- | --- |
| I 自实现 ReAct Loop | ReActLoop 手写循环，core 零 Spring AI 依赖 | PASS |
| II 禁自动执行 | ToolExecutor 自己执行工具；US-1 的 internalToolExecutionEnabled(false) 不回退 | PASS |
| III Provider 显式映射 | 不涉及（US-1 已保证） | PASS |
| V 审计 Day One | tool_invocations 本模块落库，每次执行（含拒绝/失败）都写 | PASS |
| VI Sandbox 接口先行 | 交付 Sandbox 接口 + HTTP 域名白名单实现；FILE/SHELL 留 US-4 | PASS |
| VII 同步执行 | 循环/工具/HTTP 全同步阻塞 | PASS |
| IX 核心能力优先 | 并行/总结压缩/流式/委托不做 | PASS |
| 模块结构 9 个固定 | 涉及 core/tool/channel-cli/cli/storage/boot 六个既有模块 | PASS |
| 依赖版本约束 | 无新增第三方依赖 | PASS |

## Project Structure

### Documentation (this feature)

```text
specs/002-react-loop/
├── plan.md              # 本文件（/speckit-plan 产出）
├── research.md          # Phase 0 产出：传递机制/审计改造/工具池/HTTP 客户端等决策
├── data-model.md        # Phase 1 产出：Session/Profile 增补/ToolInvocation
├── quickstart.md        # Phase 1 产出：Demo 一验证指南
├── contracts/           # Phase 1 产出：CLI 命令契约
└── tasks.md             # Phase 2 产出（/speckit-tasks 命令，本命令不生成）
```

### Source Code (repository root)

```text
nivroos-core/
└── src/main/java/com/nivroos/core/
    ├── session/
    │   ├── Session.java                # 会话容器（内存态）
    │   ├── SessionManager.java         # 接口（session_id 公式唯一拼接处）
    │   └── InMemorySessionManager.java # 内存实现
    ├── react/
    │   ├── ReActLoop.java              # 核心循环（对照颗粒度文档 §2.3 伪代码）
    │   ├── PromptBuilder.java          # 四部分组装（Memory 占位）
    │   ├── ToolExecutor.java           # 执行 + Sandbox 检查 + 审计
    │   └── AgentService.java           # 统一入口 + ProfileContext 管理
    ├── profile/
    │   ├── ProfileContext.java         # ThreadLocal<Profile>
    │   └── ProfileRegistry.java        # 简化版内存索引
    ├── context/
    │   └── ContextLoader.java          # AGENT.md 正文 + Bootstrap + 日期（无缓存）
    ├── loader/
    │   └── AgentLoader.java            # 简化版：单 Agent frontmatter 派生
    └── provider/ToolInvocationStore.java  # 审计写入接口（依赖倒置）

nivroos-tool/
└── src/main/java/com/nivroos/tool/
    ├── sandbox/
    │   ├── Sandbox.java                # 接口（ActionType 四值）
    │   ├── SandboxAction.java          # type + target
    │   ├── SandboxViolationException.java
    │   └── WhitelistSandbox.java       # HTTP 域名白名单（FILE/SHELL 留 US-4）
    └── HttpTools.java                  # http_get（JDK HttpClient，enforce 先行）

nivroos-storage/
└── src/main/java/com/nivroos/storage/tool/
    ├── ToolInvocation.java             # 实体（9 列含 success/error_message）
    ├── ToolInvocationRepository.java
    ├── JpaToolInvocationStore.java
    └── ToolInvocationStoreConfiguration.java

nivroos-channel-cli/
└── src/main/java/com/nivroos/channel/cli/CliChannel.java   # stdin/stdout 循环

nivroos-cli/
└── src/main/java/com/nivroos/cli/
    ├── InitCommand.java                # .nivroos/ 工作区初始化（幂等）
    └── ChatCommand.java                # chat --profile / --message

nivroos-boot/
└── src/main/resources/
    ├── application.yml                 # http.allowed_domains 段
    └── schema.sql                      # tool_invocations 建表
```

**Structure Decision**: 零新增模块。core 内新增 session/react/context/loader 包，
全部为技术方案 §4/§8.2/§8.3 点名的类型；审计接口沿用 LlmCallStore 依赖倒置先例。

## Complexity Tracking

无宪法违规，本节不适用。

## 人工 Review 清单（AiProgrammingGuide §3.4，review 通过后本 plan 锁定）

- [ ] Memory 没有被简化成与 Session 合并——本模块不涉及 Memory（N/A）
- [ ] Tool 没有被拆成多个模块——Sandbox/HttpTools 归 nivroos-tool 一个模块
- [ ] `AgentLoader` / `AGENT.md` 没有被当成 Tool——归 core 的 loader/context
- [ ] 没有启用 Spring AI 的自动 tool 执行——US-1 开关保持，ReActLoop 不调
      Spring AI（core 零依赖）

## Post-Design Constitution Re-check

| 原则 | 复检结论 |
| --- | --- |
| I | core 依赖清单不含 Spring AI（data-model/contracts 无相关引用） |
| II | ToolExecutor 是唯一执行路径；颗粒度文档 §2.3 伪代码与本 plan 结构一致 |
| V | ToolInvocationStore 写入覆盖执行成功/失败/Sandbox 拒绝三路径 |
| VI | Sandbox 接口签名只表达 enforce(action)，不携带白名单/容器字样 |
| 模块固定 | 未新增模块；前序改造点限于已授权的 LlmCallStore/Profile |
