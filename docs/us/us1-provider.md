# US-1 模块执行颗粒度文档：对接 LLM（Provider 抽象）

> 裁决声明：本文档是 `/module-dev` 的输入。内容从《NivroOS 技术方案》提炼，与
> 最新技术方案冲突时以技术方案为准（冲突必须停下报告，不得自行裁决）。配套工件
> `specs/001-llm-provider/` 已锁定，本文档引用而不与其冲突。结构参考 OryxOS
> 第 16 节文档，事实一律以本项目文档为准。

## 1. 模块概述

- **定位**：LLM 供应商接入的统一抽象层。上层（ReAct 循环，US-2）只声明供应商
  名称与模型，供应商选择、协议适配、凭证解析全部由本层完成。
- **价值**：Agent 任务指令与厂商解耦，更换供应商仅修改一处配置（需求文档 §1.2
  能力一"运行时切换无 lock-in"）。本模块是五大核心能力的第一个，US-2 的
  ReAct 循环依赖本模块提供 LLM 调用。

## 2. 设计要点与约束

### 2.1 职责边界

| 本模块职责 | 非本模块职责 |
| --- | --- |
| 按名选择模型，组装并发起一次 LLM 调用 | 循环控制 → ReActLoop（US-2） |
| 工具定义翻译为供应商协议格式（仅翻译，不执行） | 工具执行 → ToolExecutor（US-2） |
| 调用审计（成功与失败均落库） | 上下文组装 → PromptBuilder（US-2） |
| 配置校验（供应商、凭证、环境变量占位） | 记忆读写 → MemoryService（US-3） |

### 2.2 关键约束

1. **供应商路由必须显式映射**（宪法原则三）：多个供应商并存时容器内存在多个
   同类型 Bean，类型扫描无法区分归属。路由必须基于显式
   `Map<String, ChatModel>`，启动时按配置构建。
2. **必须禁用自动工具执行**（宪法原则二）：调用时必须关闭自动执行，模型返回的
   tool call 请求原样返回调用方，本模块不注册任何自动执行回调；违反会导致
   tool 被调两次。
3. **依赖版本必须先核实**：伞式 `spring-ai-alibaba-starter` 坐标 404；Kimi 无
   官方 GA starter（`spring-ai-starter-model-kimi` 404、moonshot 仅
   1.0.0-M7、1.1.x 最新 BOM 不管理，均 2026-08-27 实测）。动手前先跑
   `mvn dependency:tree` 确认锁定 BOM 内目标依赖存在。本模块实测结论见
   `specs/001-llm-provider/research.md` §1。

## 3. 交付物清单

### 3.1 代码

| 模块 | 交付物 |
| --- | --- |
| nivroos-core | `ProviderService` 接口（`ChatResponse call(Profile, ChatRequest)`）；`LlmCallStore` 审计写入接口（依赖倒置）；异常体系 `ProviderException` / `ProviderNotFoundException` / `ProviderCallException`；`model` 包 `Message` / `ChatRequest` / `ChatResponse` / `ToolCallRequest` / `Usage`；`Profile` 补充 `provider` 字段（`providerName` / `model` / `temperature`） |
| nivroos-provider | `SpringAiProviderService`（显式 Map 路由 + 审计副作用）；`FunctionCallingAdapter`（工具格式翻译）；`ProviderProperties`（配置绑定与校验）；`ProviderAutoConfiguration`（显式装配） |
| nivroos-storage | `LlmCall` 实体；`LlmCallRepository`；`JpaLlmCallStore` |
| nivroos-cli | `ConfigLoader` 基础版（`${ENV_VAR}` 解析与必填校验） |
| nivroos-boot | `application.yml` 的 `nivroos.providers` 段；`schema.sql` 增加 `llm_calls` 建表语句（骨架实际位置，T022） |
| pom | nivroos-provider 增加依赖 `spring-ai-starter-model-deepseek` 1.1.2、`spring-ai-starter-model-openai` 1.1.2（Kimi 经 OpenAI 兼容通道，均已实测） |

### 3.2 测试

`ProviderPropertiesTest`、`SpringAiProviderServiceTest`、
`FunctionCallingAdapterTest`、`JpaLlmCallStoreTest`（单测，默认执行）、
`ProviderSmokeIT`（集成冒烟，环境守卫）。覆盖关系见 4.2。

### 3.3 配置

```yaml
# application.yaml —— 全局层：声明供应商与凭证来源（禁止明文）
nivroos:
  providers:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
    kimi:
      api-key: ${KIMI_API_KEY}
      base-url: https://api.moonshot.cn/v1   # 无官方 GA starter，经兼容端点
```

```yaml
# AGENT.md frontmatter —— Agent 层：模型归属 Agent 侧（技术方案 §8.2 裁决；
# 完整派生归 US-4，本模块仅定义结构）
provider:
  name: deepseek        # 必须存在于全局层，否则装配失败
  model: deepseek-chat
  temperature: 0.7      # 可选
```

### 3.4 数据表

`llm_calls`（九列，字段与需求文档 §10 一致；手工建表脚本 `schema.sql` 幂等）：

```sql
CREATE TABLE IF NOT EXISTS llm_calls (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id         VARCHAR(255),          -- 可空，US-2 起填充
    provider           VARCHAR(64)  NOT NULL,
    model              VARCHAR(128) NOT NULL,
    prompt_tokens      INTEGER,
    completion_tokens  INTEGER,
    total_tokens       INTEGER,
    duration_ms        BIGINT       NOT NULL,
    created_at         TIMESTAMP    NOT NULL
);
```

> **差异裁决注**：OryxOS 第 16 节参考文档的 `llm_calls` 含 `success` /
> `error_message` 两列；NivroOS 需求文档 §10 无此两列，以 NivroOS 文档为准，
> 不添加。失败调用留痕方式：token 三列记空、`duration_ms` 记实际耗时、异常
> 上抛；失败原因经异常与结构化日志表达。

## 4. 验收测试（Harness）

### 4.1 测试分层

- **单测**（默认全量执行）：不访问真实网络。mock ChatModel / 适配器 / 审计，
  覆盖路由、审计、翻译、配置校验与持久化。
- **集成冒烟**（环境守卫）：`ProviderSmokeIT` 检测 `DEEPSEEK_API_KEY` /
  `KIMI_API_KEY`，缺失即跳过（CI 无密钥时自动跳过，不置红）；有 key 时对两家
  各执行一次真实调用，断言返回非空且 `llm_calls` 落库一条。

### 4.2 测试类与验收点映射

| 测试类 | 验收点（对应 spec FR） |
| --- | --- |
| `ProviderPropertiesTest` | 明文 api-key 拒绝并指明键路径（FR-003）；同名供应商拒绝（FR-009）；`${ENV}` 未设置报错指明缺失项（FR-007）；base-url 可选；引用未注册供应商报错（FR-010） |
| `SpringAiProviderServiceTest` | 按名路由且多供应商互不影响（FR-001/FR-004）；成功与失败均落审计（FR-005）；携带工具 schema 的调用关闭自动执行（FR-008）；调用失败异常上抛不静默（FR-006） |
| `FunctionCallingAdapterTest` | 工具定义翻译后字段一一对应；翻译产物不含执行逻辑 |
| `JpaLlmCallStoreTest` | schema.sql 建表可写可读；token 三列与 session_id 可空；duration_ms / created_at 非空 |
| `ProviderSmokeIT` | 环境守卫跳过逻辑；有 key 时两家真实调用与审计落库 |

### 4.3 关键回归测试

```java
// 验收点：按名路由，多供应商互不影响
@Test
void chat_routesToNamedProviderOnly() {
    ChatModel deepseek = mock(ChatModel.class);
    ChatModel kimi = mock(ChatModel.class);
    ProviderService service = new SpringAiProviderService(
        Map.of("deepseek", deepseek, "kimi", kimi), adapter, audit);

    service.call(profileUsing("kimi"), request);

    verify(kimi, times(1)).call(any());
    verify(deepseek, never()).call(any());   // deepseek 未被调用——路由互不影响
}
```

```java
// 验收点：调用失败，审计必须留痕（token 空 + 实际耗时），异常继续上抛
@Test
void callFailure_stillRecordsAudit_thenRethrows() {
    when(model.call(any())).thenThrow(new RuntimeException("connect timeout"));

    assertThrows(ProviderCallException.class,
        () -> service.call(profileUsing("deepseek"), request));

    verify(audit).record(eq("deepseek"), eq("deepseek-chat"),
        isNull(), isNull(), isNull(), anyLong());   // token 三列记空
}
```

```java
// 验收点：携带工具 schema 的调用，请求中已关闭 Spring AI 自动执行
@Test
void callWithToolSchema_disablesAutoExecution() {
    service.call(profileUsing("deepseek"), requestWithTools(httpGetTool));

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(captor.capture());
    assertThat(captor.getValue().getOptions().getToolCallbacks()).isEmpty();
}
```

> 测试方法名英文，`@DisplayName` 保留本表中文验收点；Spring AI 的确切断言
> 写法以 1.1.2 实测为准（写前在本地依赖核实，核实不到 → 软门禁）。

## 5. 范围边界

| 核心阶段不做 | 依据 |
| --- | --- |
| fallback / hedge racing / circuit breaker | 需求文档 §5.3；技术方案 §3.3 |
| 动态路由（按任务自动选模型） | 技术方案 §3.3 |
| 成本看板 / token 聚合报表 | 技术方案 §3.3 |
| 流式输出（SSE） | 技术方案 §4.3 |
| 审计查询接口 | 宪法原则五：写入必须，查询不要求 |
| 独立本地配置文件凭证通道 | FR-003 双通道二选一，环境变量通道已满足 |
| Micrometer / Prometheus 指标 | CLAUDE.md：核心阶段监控 = 日志 + 审计 + MetricsRegistry 预留 |
| `AgentLoader.deriveProfile` 完整派生 | US-4 范围 |

## 6. 验证与验收

1. **真实调用冒烟**（`specs/001-llm-provider/quickstart.md`）：配置两个 key →
   `mvn test -pl nivroos-provider` 两家各真实执行一次 → 核对 `llm_calls` 落库。
2. **负向验证**：删除 key 报错指明缺失项；明文 key 拒绝启动；引用未注册供应商
   报错含可用列表（quickstart 负向验证表 5 项）。
3. **安全扫描**：新增 openai starter 依赖后执行 `mvn -Psecurity verify`；检出
   CVE 时添加 suppression 并注明理由。
4. **装配确认**：`OpenAiAutoConfiguration` 保持排除；ChatModel 全部由
   `ProviderAutoConfiguration` 显式构造（否则启动即索要 api-key，违反宪法
   原则三）。
5. **联合验收锚点（SC-005）**：US-2 完成后合跑 Demo 一（`nivroos chat`
   查天气穿衣）；本模块无独立可见入口，真实链路最终判定在 Demo 一。
