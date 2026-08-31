# Research: US-1 对接 LLM（Phase 0）

所有结论均以项目权威文档（技术方案为最权威）+ Maven Central 实测为准。
本次 Central 核实均在 2026-08-27 完成（repo1.maven.org maven-metadata.xml / POM 反查）。

## 1. LLM 底座选型与版本配套（实测）

- **Decision**: 沿用根 POM 锁定矩阵：Spring AI 1.1.2 官方
  `spring-ai-starter-model-deepseek`（DeepSeek）+ 骨架已有
  `spring-ai-alibaba-starter-dashscope` 1.1.2.3（通义）；Boot 3.5.16。第二家供应商
  依赖暂不新增。
- **Rationale**: 实测结果——①deepseek starter 1.1.2 存在于 Central（latest 2.0.1，
  不采用：配套关系未实测，版本锁定以根 POM 为唯一事实源）；②`spring-ai-starter-model-kimi`
  不存在（404）；③`spring-ai-starter-model-moonshot` 仅 1.0.0-M7 一个里程碑版本
  （2025-04 后断更，按版本纪律不用 M/RC）；④alibaba BOM 1.1.2.3 只管理 9 个
  artifact（与 CLAUDE.md 陷阱表一致），starter 需显式版本号。
- **第二家供应商 = Kimi（已定，2026-08-27 更新）**：用户已持有 Kimi API key。
  实测：`spring-ai-starter-model-openai` 1.1.2 存在；1.1.x 最新 BOM（1.1.5）
  仍不管理 kimi/moonshot——升级 1.1.x 无济于事，2.x 配套未实测不能动。接入
  方案定为 **OpenAI 兼容通道**：`spring-ai-starter-model-openai` 1.1.2 +
  Moonshot 兼容端点（base-url 配 `https://api.moonshot.cn`——OpenAiApi 自行
  追加 `/v1` 路径，写 `/v1` 结尾会 404 `/v1/v1`，2026-08-31 实测）；模型名以
  Moonshot 官方当前口径在配置时核实（实测账户可用 2026-08-31：kimi-k2.6 /
  kimi-k2.7-code / kimi-k3；moonshot-v1-8k 已退役 404）。
  通义 DashScope 依赖已就绪，作为额外候选保留不接入。
- **Alternatives considered**: 伞式 `spring-ai-alibaba-starter`（已弃更、坐标 404
  风险）；升级 Spring AI 2.x（配套未实测，违反版本锁定纪律）。

## 2. ProviderService 接口位置与依赖方向

- **Decision**: `ProviderService` 接口定义在 `nivroos-core`，Spring AI 实现在
  `nivroos-provider`。
- **Rationale**: US-2 的 ReActLoop（core）调用 ProviderService。若接口放 provider，
  core 依赖 provider（要接口）且 provider 依赖 core（要 Profile/Message 类型），
  Maven 成环不可构建。与项目既有依赖倒置先例一致（ScheduledTaskStore 接口在 core、
  JPA 实现在 storage）。
- **Alternatives considered**: 接口放 provider + core 依赖 provider（循环依赖，
  不可行）；新建共享模块（违反 9 模块固定）。

## 3. 显式映射装配

- **Decision**: `nivroos.providers.<name>` 配置段（ProviderProperties 绑定），每个
  provider 一个命名 ChatModel Bean，`ProviderAutoConfiguration` 构造显式
  `Map<String, ChatModel>` 注入 SpringAiProviderService。
- **Rationale**: 宪法原则三——多 Provider 并存时 Bean 类型相同、Bean name 未必等于
  provider name，类型扫描产生歧义。
- **Alternatives considered**: 容器类型扫描（宪法禁止）；`List<ChatModel>` 注入
  （顺序不可靠、无法按名路由）。

## 4. Function Calling 适配

- **Decision**: `ChatResponse` 携带 `List<ToolCallRequest>`（name + arguments JSON），
  FunctionCallingAdapter 把 Spring AI 响应的 tool call 转成内部类型；不注册
  ToolCallback，不走自动执行链路。
- **Rationale**: 宪法原则二——模型返回的 tool call 原样交给调用方（US-2 的
  ToolExecutor 执行），ProviderService 只描述不执行。
- **Alternatives considered**: 启用 Spring AI 自动 tool 执行（tool 被调两次，
  宪法明令禁止）。

## 5. 审计落库与建表

- **Decision**: US-1 提前引入 nivroos-storage 的 SQLite，只建 `llm_calls` 一张表；
  `schema.sql` 手工维护（`CREATE TABLE IF NOT EXISTS` 幂等），JPA `ddl-auto=none`，
  启动时执行脚本。数据库文件路径由配置指定，启动时自动创建父目录（`.nivroos/` 的
  完整初始化归 US-2 的 `nivroos init`）。
- **Rationale**: 宪法原则五（审计 day one 写入，不得以日志替代）优先于原四周节奏；
  CLAUDE.md SQLite 陷阱（`ddl-auto=update` 的 ALTER TABLE 支持弱）要求手工建表脚本。
- **Alternatives considered**: 先写日志、US-5 再补落库（宪法禁止）；`ddl-auto=update`
  自动建表（后续演进陷阱）；Flyway/Liquibase（核心阶段不引入额外依赖，扩展阶段再议）。

## 6. 配置与密钥加载

- **Decision**: `nivroos.providers.<name>.api-key` 只接受 `${ENV_VAR}` 占位，校验时
  检测明文并拒绝；`base-url` 可选。ConfigLoader（nivroos-cli）基础版做占位解析与
  必填校验。FR-003 的「环境变量注入**或**独立本地配置文件」双通道，核心阶段实现
  环境变量通道即满足（二选一语义），本地配置文件通道预留扩展。
- **Rationale**: FR-003 / FR-007；技术方案 §8.8（敏感配置不明文，缺失或非法清晰
  报错）；Spring 环境原生支持 `${ENV_VAR}` 解析。
- **Alternatives considered**: 明文 API key 落配置（宪法与 FR-003 禁止）。

## 7. 测试策略

- **Decision**: 单元测试用 mock ChatModel（不依赖真实 LLM）；LLM 集成测试带环境
  守卫（无 `DEEPSEEK_API_KEY` 自动跳过）；验收场景走 quickstart.md 人工验证。
- **Rationale**: 无 key 时 CI 保持全绿；验收口径（clarify 已定）先跑通 DeepSeek，
  集成测试是 US-1 唯一真实调用验证点（本 US 无独立 CLI 入口）。
- **Alternatives considered**: 全部集成测试依赖真实 key（本地无 key 即红）。

## 8. 失败调用的审计语义

- **Decision**: `llm_calls` 记录**每一次**调用（含失败）：失败时 token 三列记空、
  `duration_ms` 记实际耗时；失败原因经异常（ProviderCallException）与结构化日志
  表达。
- **Rationale**: FR-005「每次 LLM 调用必须写入一条审计记录」；需求文档 §10 的
  `llm_calls` schema 无 error 字段，plan 不得增减权威 schema 字段，错误信息列在
  扩展阶段补齐。
- **Alternatives considered**: 失败不落库（违反 FR-005「每次」）；擅自加
  error_message 列（违背权威 schema，事实不对源）。
