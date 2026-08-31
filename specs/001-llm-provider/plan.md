# Implementation Plan: US-1 对接 LLM（核心能力一）

**Branch**: `001-llm-provider` | **Date**: 2026-08-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-llm-provider/spec.md`

**Note**: 本 plan 由 `/speckit-plan` 生成。生成后须按 AiProgrammingGuide §3.4 做人工
review（清单见文末），review 通过后锁定；`tasks.md` 由 `/speckit-tasks` 生成。

## Summary

实现 NivroOS 核心能力一——Provider 抽象：系统同时接入多家厂商 LLM（验收口径
DeepSeek + Kimi，Kimi 经 OpenAI 兼容通道），Agent 通过供应商名称引用供应商、
不感知厂商协议差异；每次 LLM 调用 day one
写入 `llm_calls` 审计表；凭证经环境变量注入，缺失或非法配置清晰报错。技术底座沿用骨架
锁定矩阵：JDK 21 + Spring Boot 3.5.16 单体、Spring AI 1.1.2（官方 deepseek
starter）、Spring AI Alibaba 1.1.2.3（dashscope starter）、SQLite。Spring AI 仅做
协议转换（宪法原则二），ProviderService 显式映射（原则三），全程同步阻塞（原则七）。

## Technical Context

**Language/Version**: Java 21（宪法约束，禁用非 JDK 21 特性）

**Primary Dependencies**: Spring Boot 3.5.16；Spring AI 1.1.2
（`spring-ai-starter-model-deepseek` + `spring-ai-starter-model-openai`，均本次实测
存在于 Maven Central，后者承载 Kimi 的 OpenAI 兼容通道）；Spring AI Alibaba 1.1.2.3
（`spring-ai-alibaba-starter-dashscope`，骨架已引入）；SQLite（sqlite-jdbc 3.53.2.1）
与 Spring Data JPA

**Storage**: SQLite（`.nivroos/nivroos.db`）。US-1 提前引入 nivroos-storage 并只建
`llm_calls` 表——宪法原则五（审计 day one 写入不可省）驱动的节奏调整（原四周节奏
SQLite 在第四周）；`sessions` 表仍随 US-5。

**Testing**: JUnit 5 + spring-boot-starter-test（骨架已配）；LLM 集成测试带环境守卫
（无 `DEEPSEEK_API_KEY` 自动跳过）；验收走 quickstart.md。

**Target Platform**: Linux / Windows，JDK 21，单二进制 fat JAR，企业内网部署。

**Project Type**: Maven 多模块（9 个固定）+ Spring Boot 单体应用。

**Performance Goals**: NivroOS 自身转发开销 ≤50ms（需求文档 §8.1）；LLM 调用延迟
取决于厂商，不在 NivroOS 控制范围。

**Constraints**: 同步阻塞（原则七）；禁用 Spring AI 自动 tool 执行（原则二）；凭证
不得明文（FR-003）；版本矩阵锁定（根 pom.xml 为唯一事实源）。

**Scale/Scope**: 单节点 ≥10 Agent；供应商数量按配置无硬上限（核心阶段预期 ≤5 家）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查点 | 结论 |
| --- | --- | --- |
| I 自实现 ReAct Loop | 本 feature 不实现 ReActLoop（US-2 范围），不引入 Spring AI Agent 抽象 | PASS |
| II Spring AI 只用两件事 | 只用协议转换；ProviderService 不注册自动 tool 执行，tool call 原样返回调用方（FR-008） | PASS |
| III Provider 显式映射 | provider name → ChatModel 显式 Map，禁用类型扫描 | PASS |
| V 审计 Day One | `llm_calls` 本 feature 即落库（SQLite 提前引入） | PASS |
| VII 同步执行 | ProviderService.call 同步阻塞；不引入异步模型 | PASS |
| IX 核心能力优先 | fallback / hedge racing / 动态路由 / 成本看板不实现 | PASS |
| 模块结构 9 个固定 | 涉及 core / provider / storage / cli / boot 五个既有模块，不增不拆 | PASS |
| 依赖版本约束 | 不用伞式 starter；deepseek 官方 starter 1.1.2（已实测）；springdoc 不涉及 | PASS |

## Project Structure

### Documentation (this feature)

```text
specs/001-llm-provider/
├── plan.md              # 本文件（/speckit-plan 产出）
├── research.md          # Phase 0 产出：选型与配套实测结论
├── data-model.md        # Phase 1 产出：Provider / Profile.provider / LlmCall 数据模型
├── quickstart.md        # Phase 1 产出：验证指南
├── contracts/           # Phase 1 产出：ProviderService 与 Provider 配置契约
└── tasks.md             # Phase 2 产出（/speckit-tasks 命令，本命令不生成）
```

### Source Code (repository root)

Maven 9 模块固定（技术方案第 10 章）。本 feature 涉及的模块与新增内容：

```text
nivroos-core/
└── src/main/java/com/nivroos/core/
    ├── model/           # ChatRequest / ChatResponse / ToolCallRequest 内部调用类型
    ├── provider/
    │   ├── ProviderService.java        # 接口（依赖倒置：core 持接口，provider 持实现）
    │   ├── LlmCallStore.java           # 审计写入接口（同 ScheduledTaskStore 先例）
    │   └── ProviderException.java      # 及三个子类（未找到 / 配置错误 / 调用失败）
    └── profile/Profile.java            # 补充 provider 字段（name / model / temperature）

nivroos-provider/
└── src/main/java/com/nivroos/provider/
    ├── SpringAiProviderService.java    # 实现：显式 Map<String, ChatModel> 路由
    ├── FunctionCallingAdapter.java     # Spring AI tool call → 内部 ToolCallRequest
    ├── ProviderProperties.java         # nivroos.providers.* 配置绑定 + 校验
    └── ProviderAutoConfiguration.java  # ChatModel Bean + 显式映射装配

nivroos-storage/
└── src/main/
    └── java/com/nivroos/storage/llm/
        ├── LlmCall.java                # JPA 实体（需求文档 §10 字段，不加不减）
        ├── LlmCallRepository.java      # Spring Data 仓储
        └── JpaLlmCallStore.java        # core 的 LlmCallStore 实现

nivroos-cli/
└── src/main/java/com/nivroos/cli/
    └── config/ConfigLoader.java        # 基础版：${ENV_VAR} 解析 + 必填校验

nivroos-boot/
└── src/main/resources/
    ├── application.yml                 # nivroos.providers.deepseek / kimi 配置段（${ENV_VAR} 占位）
    └── schema.sql                      # llm_calls 建表脚本（手工维护，唯一真相源）
```

**Structure Decision**: 沿用骨架 9 模块，零新增模块。两个关键决策的完整论证见
research.md：ProviderService 接口放 core（§2，Maven 循环依赖）、审计写入接口
LlmCallStore 放 core（§5，依赖倒置先例）。

## Complexity Tracking

无宪法违规，本节不适用。

## 人工 Review 清单（AiProgrammingGuide §3.4，review 通过后本 plan 锁定）

+ [ ] Memory 没有被简化成与 Session 合并——本 feature 不涉及 Memory（N/A）
+ [ ] Tool 没有被拆成多个模块——本 feature 不涉及 Tool（N/A）
+ [ ] `AgentLoader` / `AGENT.md` 没有被当成 Tool——本 feature 不涉及（N/A）
+ [ ] 没有启用 Spring AI 的自动 tool 执行——见 Constitution Check 原则二与
      contracts/provider-service.md 负向契约

## Post-Design Constitution Re-check

Phase 1 设计（data-model / contracts / quickstart）完成后复检：

| 原则 | 复检结论 |
| --- | --- |
| II Spring AI 只用两件事 | contracts/provider-service.md 明确「toolCalls 只描述不执行」；无 ToolCallback 注册点 |
| III Provider 显式映射 | ProviderAutoConfiguration 构造显式 Map 注入，不扫描容器 |
| V 审计 Day One | LlmCallStore 写入为 call 的强制副作用，成功与失败调用都写（FR-005「每次」） |
| 模块结构 9 个固定 | 未新增模块；storage 依赖方向保持 core ← storage ← boot |
| 依赖版本约束 | deepseek / openai 官方 starter 1.1.2 均实测存在；Kimi 经 OpenAI 兼容通道接入（research §1），不升级版本锁 |
