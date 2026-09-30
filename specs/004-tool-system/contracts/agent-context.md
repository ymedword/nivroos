# Contract: Agent 目录派生与上下文装配（零代码上线面）

> 面向**业务方**（只写目录、不写 Java）与**装配层**：`AGENT.md` 的每个字段落到哪里、
> 技能如何按需披露、启动扫描的失败语义。

## 1. `AGENT.md` frontmatter → `Profile`（完整派生）

| frontmatter 键 | Profile 字段 | 本模块状态 |
| --- | --- | --- |
| `name` | `name` | US-2 已交付 |
| `description` | `description` | **本模块新增** |
| `identity.agent_name` / `identity.prompt` | `identity`（`agentName` / `prompt`） | **本模块新增**（只派生登记，不额外注入 system prompt） |
| `provider.name` / `provider.model` / `provider.temperature` | `providerName` / `model` / `temperature` | US-1 已交付 |
| `tools` | `tools` | US-2 已交付（工具池按名解析） |
| `mcp_servers` | `mcpServers` | **本模块新增** |
| `bootstrap` | `bootstrap` | **本模块新增** |
| `channels` | `channels`（`name` + `config`） | **本模块新增** |
| `settings.max_iterations` / `max_history_turns` | `settings` | US-2 已交付 |
| `schedules` | `schedules`（`cron` + `message`） | **本模块新增**（消费归 US-5 的 `AgentScheduler`） |
| `notify_channels` | — | **不存在此字段**：通知渠道在 SQLite 全局注册表（技术方案 §6.8） |

敏感值（API key、MCP 凭证、webhook 地址）只允许 `${ENV_VAR}` 占位，明文被拒。

## 2. `AgentLoader.scan()` 与启动校验

```java
public List<Profile> scan();   // 扫 .nivroos/agents/*/AGENT.md 逐个派生
```

| 情形 | 行为 |
| --- | --- |
| 目录为空 / 不存在 | 视为没有 Agent，启动正常完成 |
| 单个 Agent frontmatter 非法 | 该 Agent 派生失败（抛清晰异常，含 Agent 名与缺失字段），由装配层 WARN 跳过 |
| 其它 Agent | **不受影响**（一个坏目录不拖垮全部） |
| `loadProfile(String)` | 语义不变（US-2 契约保留） |

**跨模块校验落在 `ProfileConfiguration`（`nivroos-boot`）**：`provider` 存在于
`ProviderService.providerNames()` / `tools` 已在 `ToolRegistry` / `bootstrap` 文件存在 /
`mcp_servers` 已在配置中——因为 `AgentLoader` 在 core、看不到 ToolRegistry（差异裁决注 6）。
校验失败只 WARN，不阻断启动。

## 3. `ContextLoader`（签名不变，每轮重扫）

```java
public String loadSystemPrompt();   // AGENT.md 正文 + bootstrap + 技能 L1 + 当前日期时间
```

| 段 | 规则 |
| --- | --- |
| AGENT.md 正文 | 每轮重读（US-2）；改正文下一轮生效 |
| Bootstrap | **按 `Profile.bootstrap` 声明**逐文件注入；字段缺失 → 回退默认三件（`AGENTS.md` / `SOUL.md` / `USER.md`）+ WARN；声明的文件缺失 → WARN 但继续（不阻断） |
| 技能 L1 | 每轮重扫 `agentDir/skills/` 软连接：命中注入 `name` + `description` + **Agent 本地绝对路径**；正文不注入；软连接断链 / 缺 `name` 或 `description` / 真实目标越出 `.nivroos/skills/` → WARN 跳过，不阻断 |
| 结尾 | 当前日期时间（US-2 已有） |

**渐进披露契约**（宪法原则四）：L1 = 仅元数据；L2 = 模型按 L1 给出的路径经 `read_file`
读 `SKILL.md` 正文；L3 = 附属参考 / 脚本按需再取。技能**不进** `ToolRegistry`，
不新增 `use_skill` 之类工具。

**路径语义**：注入的是 **Agent 本地路径**（软连接路径，位于 `.nivroos/agents/...` 之下，
落在文件白名单内）；软连接解析后的真实目标只用于**绑定合法性校验**，不进入提示词。

## 4. 缓存语义（四档，各不相同）

| 载体 | 生效时机 |
| --- | --- |
| `AGENT.md` 正文 | 每轮重读（改文件下一轮生效） |
| 技能元数据 L1 | 每轮重扫（新增 / 移除技能下一轮生效） |
| frontmatter 派生的 `Profile` | **启动扫描时一次性**（改 frontmatter 需重启 / 重扫） |
| MCP 工具列表 | **启动连接时**拉取并常驻（改 `mcp_servers.yaml` 需重启） |

## 5. 零代码上线验收路径（需求文档 §13 Demo 二 能力四部分）

新增一个 Agent = 新增一个目录（`AGENT.md` + 可选 `skills/` 软连接 + `scripts/`）+ 改一行
渠道登记 SQL，**零 Java 代码、零重新构建**（spec SC-007）。
