---
name: module-dev
description: >-
  按「模块执行颗粒度文档」（模块级设计文档）驱动开发一个模块：输入模块编号/US 编号，自动完成
  备料 → speckit-specify → clarify → plan → tasks（停等确认）→ analyze → implement →
  模块级验收报告，全程施加硬/软两类门禁与测试纪律，保证产出严格贴合项目技术方案。跨项目
  通用：项目相关事实全部在文末「项目配置区」。当用户说「开发模块 N / 实现 US-N /
  颗粒度文档驱动开发」时使用。
argument-hint: "模块编号或 US 编号（合法值见项目配置区模块类型分流表），如：1"
user-invocable: true
---

# module-dev — 模块执行颗粒度文档驱动的 Spec-Kit 开发编排

## User Input

```text
$ARGUMENTS
```

## 适用范围

本 skill 是跨项目通用的**编排层**：流程、门禁、DoD 对所有项目相同；与项目绑定的
事实（文档目录、映射表、模块落位、技术栈句、门禁命令、全局不变量）全部收敛到
文末「项目配置区」。**复制本 skill 到新项目时，只改写项目配置区，其余章节不动。**

开工前必读项目根 CLAUDE.md——宪法、质量门禁、陷阱表以它为准；本 skill 的配置区
若与 CLAUDE.md 冲突，以 CLAUDE.md 为准并停下报告。

## 总则

1. 可机器判定的检查项必须由门禁强制执行，不得以人工检查替代；机器无法判定的
   分歧必须停下等用户裁决，不得自行决定。
2. 软门禁：遇到下列任一情况，**立即停下、向用户报告、等确认后继续**：
   1. 需要创建"交付物清单"之外的任何对外概念（public 类型、配置键、数据表、
      REST 路径、Profile 字段）；
   2. 需要修改任何已定字面量（类名、方法签名、配置键、表列名、端点路径）；
   3. 模块执行颗粒度文档与技术方案冲突（裁决：以项目技术方案为准，见配置区）；
   4. 需要修改前序模块交付的公共接口（当模块文档明确列为"改造点"的除外）；
   5. 第三方 API 在本地依赖中核实不到；
   6. 需要新增 plan 未列明的第三方依赖。
3. 测试纪律：不得删断言、不得 `@Disabled`、不得放宽阈值让测试变绿。实现错误
   修实现；认为测试错误则停下报告。未全绿不得宣称完成。
4. 硬门禁：测试、配置区「门禁命令」的全量构建、交付物存在性核对——不通过不
   放行，不得绕过。
5. 全程**不自动 commit / push / 部署**，同步时机由用户决定。

## 步骤 0：输入校验与模块类型分流

解析输入编号，按项目配置区「模块类型分流表」分流；表外输入直接报错退出。

- 开发类模块 → 走完整流程（步骤 1~7）
- 评审类模块 → 拒绝并说明：本模块不产码，是下一模块的 specify 素材
- 串联类 / Demo 类模块等特殊模式 → 按配置区该行的处理方式执行

**续跑检查（分流后执行）**：检查 specs 目录下是否已存在该模块的 feature 目录
（如 `specs/{NNN}-<slug>/`，以 `.specify/feature.json` 为准）且 spec/plan 已
锁定。已存在 → 跳过步骤 2~4（specify/clarify/plan 已完成），从步骤 5
（/speckit-tasks）继续；只存在 spec 无 plan → 从步骤 4（/speckit-plan）继续。

## 步骤 1：上下文加载与依赖检查

1. **读取以下三类输入**（完整读取，不得凭记忆）：
   - 当前模块执行颗粒度文档（配置区的文档 glob 匹配；缺失 → 软门禁停下，
     先用 `/module-doc-gen` 生成）；
   - 技术方案对应章节（配置区章节映射表）；
   - 前序各模块颗粒度文档的"交付物清单"节。
2. **依赖存在性检查**：对前序每个模块交付物清单里的核心类，在代码库里
   grep/Glob 确认存在。任何缺失 → 停下报告"先做第 X 个模块"，不得跳模块自造。
3. **分支**：确认当前在该模块的 feature 分支上（speckit-specify 的
   before_specify hook 会建分支；若 hook 未配置，手动
   `git checkout -b {NNN}-<slug>`），不在主干上直接开发。

## 步骤 2：组装并执行 /speckit-specify

用 Skill 工具调用 `speckit-specify`，参数按此骨架从**当前颗粒度文档的第 1、2
节**提炼（只写 WHAT/WHY，不带类名和技术栈）：

```text
第{N}模块需求：<模块名>——<一句话定位>
背景与价值。<从文档"模块概述"提炼>
用户场景。<2~3 个具体场景>
功能需求。FR1~FRn <从文档"设计要点与约束"提炼，每条可测试>
明确不做（边界）。<文档"范围边界"逐项照搬>
验收标准。可自动化部分由文档"验收测试（Harness）"测试套件承载（测试全绿即
  通过），关键回归点：<列出文档中写出代码的那几个测试的守点>；
  人工项见文档"验证与验收"。
依赖与假设。<指向前序模块交付物；外部依赖>
```

## 步骤 3：/speckit-clarify

调用 `speckit-clarify`。有问题答问题（答案只从颗粒度文档和技术方案找，找不到 →
软门禁停下问用户）；无问题继续。

## 步骤 4：组装并执行 /speckit-plan

参数 = 固定技术栈句 + 本模块落位 + 测试策略句 + 语法禁区（均来自项目配置区）：

- **固定技术栈句**（配置区原文照抄）
- **模块落位表**（照抄本模块行，细节以技术方案模块章为准）
- **测试策略句**（从文档"验收测试（Harness）"抄）：`测试策略按文档"验收测试
  （Harness）"执行：<测试类清单>（覆盖 <关键回归点>），单测默认执行、集成冒烟
  环境守卫 CI 跳过；实现完成的定义是门禁全绿。`
- **语法禁区句**（配置区原文；若配置区写"无"，本句省略）

## 步骤 5：/speckit-tasks + 固定软停点

1. 调用 `speckit-tasks`。
2. **自动比对**：任务清单 ↔ 当前颗粒度文档"交付物清单"（代码/测试/配置/表逐
   项），并确认测试任务先于或伴随对应实现任务（harness 先行）。
3. 输出比对结果（齐 / 缺什么 / 多什么），**停下等用户确认**后才进入下一步。
   这是流程中唯一的固定停点，不得跳过。

## 步骤 6：/speckit-analyze（建议执行）+ /speckit-implement

implement 期间逐任务执行，附加门禁：

- **写前（H3）**：涉及第三方 API 的任务，先在本地依赖核实方法存在；核实不到 →
  软门禁。
- **写中（H1/H5）**：只创建交付物点名的对外概念；已定字面量逐字保真；异常不吞
  （catch 必落审计/日志或上抛）；不建文档外抽象层；注释只写"为什么"。**注释
  语言**（遵循项目 CLAUDE.md 约定）：正文中文为主，英文标识符/术语保留原文
  不翻译；**关键注释（类级/方法级/复杂逻辑段）中英并列**，中文在前、英文在
  后。**测试方法名必须是英文**（驼峰或 snake_case），不得用中文方法名；
  文档 harness 里若给出中文方法名，翻译成语义等价的英文名落地，并用
  `@DisplayName` 保留文档原文以便对号。
- **写后**（任务级 DoD）：实现与测试一起落地，跑该模块测试，失败即时修复，不
  累积到任务末尾。
- 文档"验收测试（Harness）"里**写出代码的关键回归测试必须原样落地**（断言逻辑
  逐条保真；方法名按上条规则译成英文，文档原文进 `@DisplayName`）。

## 步骤 7：模块级收尾——七项证据 DoD

全部满足才可宣布本模块完成，逐项把证据写进验收报告：

1. 门禁命令全绿（配置区），贴关键输出；
2. 文档 harness 映射表的每个测试类存在且非空，关键回归测试逐个对号；
3. "交付物清单"逐项 ls/grep 存在性核对；
4. **前序模块全部测试回归绿**（跨模块契约证据）；
5. **全局不变量逐条自查**（配置区清单，机器可判）；
6. 验收报告收尾：以上证据 + 文档"验证与验收"的**剩余人工项清单**（真模型 /
   真 webhook / 冒烟等），明确告知用户"harness 已判卷，这几项等用户人工执行"；
7. **变更总结（给 reviewer 的导读，直接输出在对话里，不另开文件）**——以
   `git status --short` / `git diff --stat` 实测为准，三段固定结构：
   - **改动点**：按模块分组列新增 / 移动（改名）/ 修改 / 删除的文件，每处一句话
     说明动机；前序模块文件被本模块触碰的（哪怕只改 import）单独标出；
   - **重点 review 清单**：按风险排序 3~6 条——架构决策（依赖方向、契约变化）
     优先，其次文档交付物同构性（逐行对照点）、跨模块契约兼容性、静态门禁妥协
     点；每条给出文件行级定位；
   - **如何验证**：可直接复制执行的命令块（全量门禁、只跑本模块测试、关键回归
     单测、依赖方向 grep 等），每条命令注明预期结果；最后重复剩余人工项。

报告完停止——commit / push / 部署由用户决定。

---

## 项目配置区（复制本 skill 到新项目时，只改写本节）

> 本节是唯一允许因项目而异的部分；上文所有章节跨项目不动。
> 本实例按 NivroOS 预填，事实来源：`docs/TechnicalSolution.md`（最权威）、
> 项目根 `CLAUDE.md`、`docs/AiProgrammingGuide.md`。
> 术语：**模块执行颗粒度文档**（OryxOS 项目里称"课件"，本项目统一用前者）。

### 输入与模块类型分流表

| 输入 | 模块类型 | 处理 |
| --- | --- | --- |
| 1~5（US 编号） | 开发类模块 | 走完整流程（步骤 1~7） |
| 其他 | — | 报错：仅支持 US-1~5 |

### 颗粒度文档与技术方案

- 文档目录：`docs/us/us{N}-<slug>.md`，由 `/module-doc-gen` skill 生成
  （US-1 已就位：`docs/us/us1-provider.md`）；缺失 → 软门禁停下
- 技术方案：`docs/TechnicalSolution.md`，项目最权威文档；文档与其冲突时以技术
  方案为准（软门禁报告）

### 章节映射表（技术方案）

| US | 技术方案章节 |
| --- | --- |
| 1 | §3（Provider）、§8.2（Profile 结构）、§9.2（llm_calls） |
| 2 | §4（ReAct/AgentService）、§8.3（ContextLoader）、§8.4（CLI Channel）、§9.2（tool_invocations） |
| 3 | §5（Memory） |
| 4 | §6（内置 Tool/MCP/ToolRegistry/Sandbox/Notify） |
| 5 | §7（Web Service）、§8.5（定时任务）、§8.7（12 命令）、§9.2（sessions 等） |

### 模块落位表（9 模块固定，不拆不并）

| US | 落位 |
| --- | --- |
| 1 | ProviderService/LlmCallStore 接口 + ChatRequest/ChatResponse/ToolCallRequest + Profile 结构 → nivroos-core；Spring AI 实现/显式映射装配 → nivroos-provider；LlmCall+Repository+JpaLlmCallStore+schema.sql → nivroos-storage；ConfigLoader 基础版 → nivroos-cli；application.yaml → nivroos-boot |
| 2 | ReActLoop/PromptBuilder/ToolExecutor/AgentService/ContextLoader → nivroos-core；HTTP Tool+Sandbox 简化版 → nivroos-tool；CliChannel → nivroos-channel-cli；init+chat 命令 → nivroos-cli；ToolInvocation+Repository → nivroos-storage |
| 3 | 全部 → nivroos-memory |
| 4 | 内置 Tool 补齐/MCP Client/ToolRegistry/Sandbox+WhitelistSandbox/NotifyTools → nivroos-tool；AGENT.md 加载归 ContextLoader（core），不进 Tool 模块 |
| 5 | WebServer/6 Controller/GlobalExceptionHandler/OpenAPI → nivroos-web；Session/ScheduledTask 实体+Repository → nivroos-storage；12 命令+ConfigLoader 完整版 → nivroos-cli；AgentScheduler → nivroos-core |

### 固定技术栈句

`JDK 21 + Spring Boot 3.5.16 + Spring AI 1.1.2（动手前先跑 mvn dependency:tree 确认锁定 BOM 里目标依赖存在）+ SQLite + Spring Data JPA。凭证走环境变量占位，不落明文。SQLite 用手工建表脚本（schema.sql 幂等），不依赖 hibernate.ddl-auto=update。Spring AI eager 装配保持排除（autoconfigure.exclude）。`

### 门禁命令

- 全量门禁：`mvn clean verify`（Spotless + Checkstyle + SpotBugs(findsecbugs) +
  测试 + JaCoCo）
- 新增第三方依赖或交付收尾时：`mvn -Psecurity verify`（OWASP dependency-check）
- 格式修复只许 `mvn spotless:apply`，禁手改（pre-commit 会拦）

### 全局不变量（收尾逐条自查，机器可判）

1. 无 Spring AI 自动 tool 执行路径（tool call 一律由 ToolExecutor 调度）
2. LLM 调用成败都落 `llm_calls`、工具执行成败都落 `tool_invocations`
3. grep 无明文 key（全部 `${ENV_VAR}` 占位）
4. Provider 路由走显式 `Map<String, ChatModel>`，不扫描容器
5. 无 Reactor / CompletableFuture / 自建线程池（同步阻塞 + 虚拟线程）
6. 不新增 / 拆分 Maven 模块（固定 9 个）

### 语法禁区

无（NivroOS 未装 P3C；SpotBugs + Checkstyle 支持 Java 21 全部语法）。
复制到其他项目时按该项目静态检查填写（如解析器不支持新语法形态则在此列明）。
