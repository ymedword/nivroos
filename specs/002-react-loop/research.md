# Research: US-2 ReAct 循环（Phase 0）

所有结论以项目权威文档（技术方案为最权威）为准；参考文档（OryxOS 第 17 节）
只借结构与检查角度，事实不照抄。

## 1. Profile 传递机制

- **Decision**: ReActLoop / ToolExecutor 的方法签名不带 Profile 参数，需要时从
  `ProfileContext.current()` 取；AgentService 在 process 入口 set、finally clear。
- **Rationale**: 技术方案 §4.2——"把当前 Profile 放进 ProfileContext（ThreadLocal，
  虚拟线程下每个请求天然独立）"；US-1 的 `NivroTool.execute` 已采用同款机制
  （签名不带 Profile）。参考文档的 `run(Session, String, Profile)` 显式传参方案
  不采用（差异裁决注已入颗粒度文档 §2.2 约束 6）。
- **Alternatives considered**: 显式参数传递（与既有 NivroTool 机制不一致，且
  循环内层层透传噪音大）。

## 2. 审计 session 关联改造（前序改造点，已授权）

- **Decision**: `LlmCallStore.record` 增加首参 `sessionId`；`SpringAiProviderService`
  从 `ChatRequest.sessionId()`（US-1 已定义字段）传入；`tool_invocations` 同样
  经 `ToolExecutor.execute(sessionId, call)` 关联。US-1 的
  SpringAiProviderServiceTest / ProviderSmokeIT 断言同步加参。
- **Rationale**: 需求文档 §10 两张审计表都有 session_id 列；US-1 阶段可空
  （"US-2 起填充"的遗留人工项在此闭环）。颗粒度文档 §3.5 已明确列为改造点，
  软门禁例外条件成立。
- **Alternatives considered**: 不改接口、审计无 session 关联（宪法五语义受损，
  审计追溯断裂）；新建重载方法（接口出现两套签名，混乱）。

## 3. 工具池简化形态

- **Decision**: US-2 用一个共享的 `Map<String, NivroTool>` 工具池（由装配层
  注入 ReActLoop，PromptBuilder 按 Profile.tools 名称解析出 List<NivroTool>，
  ToolExecutor 按名查找执行）；US-4 引入 ToolRegistry 后替换，调用方只换
  注入类型。
- **Rationale**: AiProgrammingGuide §4.2 明确 US-2 只做"一个基础内置 Tool"，
  ToolRegistry 是 US-4 交付物；提前实现注册表违反模块边界。
- **Alternatives considered**: 本模块直接实现 ToolRegistry（越界 US-4）。

## 4. HTTP 客户端选型

- **Decision**: JDK `java.net.http.HttpClient`（Java 21 内置），同步 send；
  连接与读取超时默认各 10 秒，经 application.yml 可配置。
- **Rationale**: 零新增第三方依赖（本模块宪法级承诺）；同步阻塞契合原则七；
  虚拟线程下 JDK HttpClient 的阻塞 IO 自动让出载体线程。
- **Alternatives considered**: Spring RestClient（引入 web 依赖到 tool 模块，
  不必要的耦合）；OkHttp（新增依赖 + 安全扫描面扩大）。

## 5. 强制结束返回语义（已决）

- **Decision**: 达 max_iterations 返回固定文案"任务执行时间较长，达到最大轮数，
  已停止继续尝试，当前结果可能不完整。"并记 WARN 日志。
- **Rationale**: 用户 2026-08-31 在 clarify 中确认（spec Clarifications）。
- **Alternatives considered**: 返回最后一轮模型文本（内容可能不完整且携带
  未执行的工具请求，被否决）。

## 6. Session 内存模型与 session_id 公式

- **Decision**: `Session` 为 core 内存类；session_id = channel + ":" + userId +
  ":" + profileName 拼接，**只在 InMemorySessionManager 一处实现**（技术方案
  §9.2）；US-5 落库时复用该公式。
- **Rationale**: 技术方案 §9.2 "channel 加 user 加 profile 联合生成"；单一
  拼接点便于 US-5 迁移时行为一致（ScheduledTaskStore 同款"契约在 core"思路）。
- **Alternatives considered**: 各调用方自行拼接（公式漂移风险）。

## 7. 迭代与历史默认值

- **Decision**: max_iterations 默认 10、max_history_turns 默认 20，存放于
  Profile.settings，AgentLoader 解析 frontmatter 时缺省填默认值。
- **Rationale**: 技术方案 §4.3（默认 10 可覆盖）+ 需求文档 §5.5（默认 20 轮）。
- **Alternatives considered**: 硬编码常量（失去 Profile 覆盖能力，FR-004 不满足）。

## 8. ContextLoader 无缓存语义

- **Decision**: 每次组装 prompt 重新读取 AGENT.md 正文与 Bootstrap 文件；
  Bootstrap 缺失 WARN 不阻断；不引入任何缓存层。
- **Rationale**: 技术方案 §8.3"全部无缓存，修改或重新绑定后下一轮立即生效"。
- **Alternatives considered**: 启动时一次性加载（修改文件需重启，违背 FR-010）。

## 9. 测试策略

- **Decision**: 全部单测不碰网络（mock ProviderService / ChatModel / 工具池 /
  Sandbox / 审计）；Demo 一为真模型人工验收，不进 CI。
- **Rationale**: 颗粒度文档 §4.1 分层标准；CI 无密钥环境。
- **Alternatives considered**: 环境守卫集成测试（US-2 无真实网络依赖的独立
  链路可测，冒烟由 Demo 一承担）。
