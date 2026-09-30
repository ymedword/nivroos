# Contract: MCP 接入与通知出站（部署方 / 业务方面）

> 面向**部署方**（配置）与**业务方**（轻代码接入 / 出站推送）：配置形态、失败语义、
> 凭证纪律。键名与字段名是已定字面量。

## 1. MCP server 配置（`.nivroos/mcp_servers.yaml`）

```yaml
servers:
  - name: github-mcp
    transport: stdio
    command: "npx -y @modelcontextprotocol/server-github"
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}
```

| 规则 | 行为 |
| --- | --- |
| 文件不存在 | 视为无 MCP，正常启动（不报错） |
| `transport` 非 `stdio` | WARN 跳过该 server（核心阶段只做 stdio） |
| `command` | 按空白拆：首 token = 可执行文件，其余 = `args` |
| `env` 值为明文（不含 `${...}`） | **抛清晰异常**（大声拒绝，不静默放行） |
| `env` 占位符指向的环境变量缺失 | **抛清晰异常**（同上） |
| 单个 server 连接 / 初始化 / 拉列表失败 | WARN + 跳过该 server，**不阻断启动**，其它 server 工具照常注册 |
| 工具调用失败（`isError`） | 失败结果 + 错误文本回填对话上下文 |

**凭证纪律**：`env` 只允许 `${ENV_VAR}` 占位；解析在 `nivroos-tool` 内本地实现
（规则与 CLI 侧 `ConfigLoader.resolveEnv` 一致，但**不复用其实现**——避免 tool → cli 反向依赖）。

## 2. `McpClientService`

```java
public McpClientService(List<McpServerConfig> servers, ToolRegistry registry);   // 生产构造
McpClientService(List<McpServerConfig>, ToolRegistry,
                 Function<McpServerConfig, McpClientTransport> transportFactory); // 包内可见，供单测

public void start();   // 连接 + initialize + tools/list 翻页取尽 + register（单 server 失败 WARN 跳过）
public void close();   // closeGracefully，失败降级 close() + WARN
```

- 调用超时：`McpClient.SyncSpec.requestTimeout(Duration.ofSeconds(30))`（常量，**不新增配置键**——
  颗粒度文档「待决事项」明确按 server 可配置须先报软门禁）。
- **同步模型**：一律 `McpSyncClient`；源码不得出现 `Mono` / `Flux` / `McpAsyncClient`
  （不变量 #5 的判定标准是源码 grep）。
- 工具列表在**启动连接时**拉取并常驻内存：`mcp_servers.yaml` 改动需重启（缓存语义 ③）。

## 3. 通知渠道注册表（SQLite `notify_channels`）

```sql
-- 运维方手工 SQL 直插（核心阶段无 CRUD 端点 / 子命令；地址即凭证，不入 git）
INSERT INTO notify_channels (name, type, url, description, created_at)
VALUES ('ops-team', 'webhook', '<真实 webhook URL>', '运维值班群', CURRENT_TIMESTAMP);
```

| 列 | 语义 |
| --- | --- |
| `name` | 渠道名（主键），Agent 在 `AGENT.md` 正文里按名引用 |
| `type` | 渠道类型；核心阶段唯一受支持取值 `webhook` |
| `url` | 投递地址（凭证级信息：占位符注入、不进对话、不入库文档） |
| `description` | 可选说明 |
| `created_at` | 登记时间 |

**不在 `AGENT.md` frontmatter 里**：通知渠道由全局注册表管理（技术方案 §6.8；
讲义把渠道放 Profile 的做法不采纳——差异裁决注 8）。

## 4. 出站契约

```java
public interface NotifyChannelAdapter { void send(NotifyTarget target, String content); }

public final class WebhookNotifyAdapter implements NotifyChannelAdapter {
    WebhookNotifyAdapter(Sandbox sandbox, HttpClient client);
}
```

| 步骤 | 顺序要求 |
| --- | --- |
| 1. 由 `NotifyTools` 按 `channel` 名从 `NotifyChannelStore` 解析出 `NotifyChannel` | 渠道不存在 → 失败结果 + 可用渠道名清单 |
| 2. 构造 `NotifyTarget(channel.type(), Map.of("url", channel.url()))` | 地址不进对话上下文 |
| 3. `sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))` | **必须先于发送**（顺序断言见关键回归） |
| 4. `HttpClient` POST `{"content": "<文本>"}` | 非 2xx → 抛异常（不吞） |

- 渠道类型不受支持（非 `webhook`）→ 按"渠道不可用"处理：失败 + 回填可用渠道名清单。
- 各渠道 payload 差异（企业微信 / 飞书 / 钉钉签名与 AccessToken）归扩展阶段按 `channelType`
  加专用适配器，**接口不变**。
