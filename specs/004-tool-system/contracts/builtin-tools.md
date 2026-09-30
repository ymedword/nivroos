# Contract: 九个内置工具（模型可见面）

> 面向**模型**的接口：工具名、参数名、描述与结果语义是契约，改名即破坏 `AGENT.md`
> 的 `tools:` 引用与既有测试。schema 由 `@Tool` / `@ToolParam` 注解生成
> （参数名依赖 `-parameters`，已由 Boot parent 开启——research §3）。

## 通用规则

1. 每个工具方法**首步** `sandbox.enforce(...)`（`notify` 在适配器内、发送前），
   拒绝抛 `SandboxViolationException` → 由 `ToolExecutor` 落失败审计 + WARN。
2. 业务性失败（参数缺失 / 非法）**返回** `ToolResult(false, null, 文案, false)`；
   异常性失败（沙箱 / IO / 超时）**抛异常**，由 `ToolExecutor` 统一落审计并回填对话上下文。
3. 结果文本进 `ToolResult.content`；失败原因进 `errorMessage`（二者必有其一对模型可见）。
4. 任一档白名单为空 = 该档动作全拒（不是"不校验"）。

## 1. 文件工具 `FileTools(Sandbox)`

| 工具名 | 参数 | schema 描述要求 | 结果 |
| --- | --- | --- | --- |
| `read_file` | `path`（String，必填） | 「要读取的文件路径」 | `content` = 文件全文 |
| `write_file` | `path`（String，必填）、`content`（String，必填） | 「要写入的文件路径」/「写入内容」 | `content` = 成功文案 |
| `list_dir` | `path`（String，必填） | 「要列出的目录路径」 | `content` = 条目逐行连接；空目录给明确文案 |

- 白名单档：`FILE_READ`（读 / 列）/ `FILE_WRITE`（写）；路径规范化后比对（`../` 越界必拒）。
- 越界时**动作不得发生**：目标文件不创建、不修改。

## 2. 命令工具 `ShellTools(Sandbox, Duration timeout)`

| 工具名 | 参数 | 结果 |
| --- | --- | --- |
| `shell` | `command`（String，必填） | `content` = 标准输出；非零退出 → `success=false` + stderr 进 `errorMessage`；超时 → 强杀进程 + `success=false`（含超时文案） |

- 白名单档：`SHELL_COMMAND`（只校验首个 token）。
- 执行方式：POSIX `bash -c <command>`；工作目录 = 进程启动目录（工作区根）。
- **信任边界（必须如实告知部署方）**：脚本自身可发起网络请求绕过 `http_get` 的域名
  白名单；装一个带脚本的 Agent = 信任该 Agent 的作者（技术方案 §12.3 注）。

## 3. HTTP 工具 `HttpTools(Sandbox, HttpClient)`

| 工具名 | 参数 | 结果 |
| --- | --- | --- |
| `http_get` | `url`（String，必填）——**US-2 已交付，改标注不改名** | `content` = 响应体 |
| `http_post` | `url`（String，必填）、`body`（String，必填） | `content` = 响应体；默认 `Content-Type: application/json` |

- 白名单档：`HTTP_REQUEST`（域名通配符，`*.example.com` 命中裸域名与子域名）。
- 超时沿用 US-2 的 10s 常量；白名单外域名 → 请求**不得发出**。

## 4. 记忆工具 `MemoryTools(MemoryService)`（US-3 交付，本模块改标注）

| 工具名 | 参数（**逐字不变**） | 结果（**语义不变**） |
| --- | --- | --- |
| `save_memory` | `content`（必填）、`scope`（可选，取值 `CORE` / `ARCHIVAL`） | 写入成功文案；`content` 缺失/为空、`scope` 非法 → 失败结果 + 合法取值提示；存储失败 → 异常上抛 |
| `recall_memory` | `query`（必填） | 命中行换行连接；无命中 → 「无匹配」文案；存储失败 → 异常上抛 |

- 不走文件白名单（`MEMORY.md` 路径由系统固定，US-3 FR-015）。

## 5. 通知工具 `NotifyTools(Sandbox, NotifyChannelStore, NotifyChannelAdapter)`

| 工具名 | 参数 | 结果 |
| --- | --- | --- |
| `notify` | `content`（String，必填）、`channel`（String，可选——见下） | 推送成功文案；渠道缺失 / 不存在 / 类型不支持 → `success=false` + 可用渠道名清单 |

- `channel` **缺省即失败**并回填可用渠道名（不猜、不静默，颗粒度文档「待决事项」默认建议）。
- 发送前必须过 HTTP 域名白名单（与 `http_get` 同一份 `http.allowed_domains`），
  且**校验必须先于发送**（顺序反了即绕过漏洞）。
- 请求体：`{"content": "<文本>"}`（通用 webhook 档；渠道专用格式归扩展阶段）。
