# Quickstart: US-4 Tool 体系 验证指南

> 判定「实现完成」的路径：§1 自动化门禁全绿 → §2 机器可判的依赖方向与不变量自查 →
> §3 剩给用户的人工冒烟。契约细节见 [contracts/](./contracts/)，数据形状见
> [data-model.md](./data-model.md)，决策依据见 [research.md](./research.md)。

## 0. 前置

```bash
# macOS 本机工具链非标准路径（CLAUDE.md「环境」节）
source ~/.zshrc
mvn -v   # Apache Maven 3.9.16 / JDK 21 temurin
```

- 工作区：`.nivroos/`（`nivroos init` 生成）+ `.nivroos/mcp_servers.yaml`（真机冒烟用）。
- 通知渠道：先手工 SQL 直插一行（SQL 见 [contracts/mcp-and-notify.md §3](./contracts/mcp-and-notify.md)）。
- 凭证只走环境变量（`DEEPSEEK_API_KEY` / `KIMI_API_KEY` / MCP 用的 `GITHUB_TOKEN` 等），
  **不写进任何文件、不进命令行**。

## 1. 自动化验证（判定「实现完成」的机器门禁）

```bash
# 全量门禁：Spotless + Checkstyle + SpotBugs(findsecbugs) + 测试 + JaCoCo
mvn clean verify

# 只跑本模块（-am 必须带：不带会解析本地仓库旧版本模块 jar，测试静默失败）
mvn -pl nivroos-tool -am test
mvn -pl nivroos-core -am test
mvn -pl nivroos-storage -am test
mvn -pl nivroos-memory -am test      # MemoryTools 改造点回归

# 关键回归单测逐个点名（颗粒度文档 §4.3 原样落地的五条）
mvn -pl nivroos-tool -am test -Dtest='WhitelistSandboxTest#enforce_pathTraversalOutsideWhitelist_rejected'
mvn -pl nivroos-tool -am test -Dtest='WebhookNotifyAdapterTest#send_enforcesWhitelistBeforeDispatch'
mvn -pl nivroos-tool -am test -Dtest='ShellToolsTest#shell_concurrentCalls_outputsDoNotMix'
mvn -pl nivroos-tool -am test -Dtest='McpToolAdapterTest#execute_mcpToolReturnsError_mappedToFailure'
mvn -pl nivroos-tool -am test -Dtest='McpClientServiceTest#start_oneServerUnreachable_othersStillRegistered'

# 前序模块回归（跨模块契约证据：US-1/US-2/US-3 全绿）
mvn -pl nivroos-cli -am test
mvn -pl nivroos-boot -am test

# 依赖安全（新增坐标后复核抑制清单；MCP SDK 的 Jackson 3 传递依赖是否触发新告警）
mvn -Psecurity verify
```

## 2. 机器可判的依赖方向与全局不变量自查

```bash
# ① tool 模块只允许 Spring AI 的注解与 schema 生成，不得出现执行路径
grep -rn "org.springframework.ai" nivroos-tool/src/main | grep -v "org.springframework.ai.tool"   # 期望：无输出
grep -rn "ChatClient\|ChatModel" nivroos-tool/src/main                                            # 期望：无输出

# ② MCP 只在 tool 模块，core 不出现
grep -rn "io.modelcontextprotocol\|McpSchema" nivroos-core/src/main                               # 期望：无输出

# ③ tool 模块不反向依赖 cli
grep -rn "nivroos-cli" nivroos-tool/pom.xml                                                       # 期望：无输出

# ④ 同步模型：源码无 Reactor / CompletableFuture / 自建线程池
grep -rn "reactor\.\|Mono<\|Flux<\|CompletableFuture\|new Thread\|Executors.newFixed" \
  nivroos-tool/src/main nivroos-core/src/main nivroos-memory/src/main nivroos-storage/src/main    # 期望：无输出
  # 注：Executors.newVirtualThreadPerTaskExecutor 只允许出现在测试里（并发隔离用例）

# ⑤ 无明文凭证：配置与源码只有 ${ENV_VAR} 占位
grep -rn "sk-\|api-key: [a-zA-Z0-9]\{10\}" nivroos-*/src/main/resources nivroos-*/src/main/java   # 期望：无输出

# ⑥ 模块数仍为 9
grep -c "<module>" pom.xml                                                                        # 期望：9

# ⑦ 审计双写不变量（原则五）：工具执行路径仍只有 ToolExecutor 落审计
grep -rn "tool_invocations\|ToolInvocationStore" nivroos-tool/src/main                            # 期望：无输出

# ⑧ 无 Spring AI 自动执行：ToolCallingManager / internalToolExecutionEnabled 保持禁用
grep -rn "internalToolExecutionEnabled" nivroos-provider/src/main                                 # 期望：仅 false 一处
```

## 3. 人工冒烟项（等用户执行，harness 已判卷）

> 自动化部分（§1）全绿即"实现完成"；以下五项需要真模型 / 真外部服务，由用户执行。
> 建议按此顺序，每项留一条 `tool_invocations` 证据行。

1. **方式一零代码上线**（需求 §13 Demo 二 能力四部分）
   配一个 Agent 目录（`AGENT.md` 声明 `tools: [read_file, list_dir, shell, notify]` +
   引用一个社区 MCP server）→ `nivroos chat --profile <agent>` 跑一次日报类任务 →
   核对 `AGENT.md` 有改动时下一轮生效、frontmatter 改动需重启（缓存语义 ②/④）。
2. **方式二真机（MCP stdio）**
   在 `.nivroos/mcp_servers.yaml` 配真实 server（社区 `github-mcp` 或任一本地 server）→
   让模型调一次 MCP 工具 → 核对 `tool_invocations` 有该工具名且 `success=1`；
   再故意写错 `command` 重启，核对**启动不中断** + WARN 日志 + 该 server 工具未注册。
3. **方式三重代码（`@Tool` Bean）**
   boot 侧示例 `@Tool` Bean 被模型调用一次（同一进程内，无子进程）。
4. **Skill 渐进披露（宪法原则四）**
   Agent 目录挂一个真实技能软连接（指向 `.nivroos/skills/<name>/`）→ 模型从 L1 元数据命中 →
   `read_file` 读 `SKILL.md` 正文（L2）→ 完成一次任务；同时核对系统提示词里**只有
   name + description + 路径、无正文**（spec SC-006）。
5. **`shell` 跑捆绑脚本 + `notify` 真 webhook**（技术方案 §12.3）
   `python scripts/xxx.py` 经 `shell.allowed_commands` 放行、脚本产出进上下文；
   一个真实群机器人 webhook 收到推送（`success=1`），并把该域名移出白名单再试一次，
   核对**请求未发出**（失败审计 + 明确原因）。

## 4. 常见问题

| 现象 | 原因 / 处置 |
| --- | --- |
| 所有 LLM 调用突然失败 | 某个工具入参 schema 为空（`FunctionCallingAdapter` 直接拼 schema）→ 跑 `ToolContractTest` |
| 工具被调两次 | 打开了 Spring AI 自动执行 → 检查 `internalToolExecutionEnabled(false)` 与 `nivroos-tool` 无 `ChatClient` 引用 |
| 模型说"我调用了工具"但什么都没发生 | `AGENT.md` 未声明 `tools:` ⇒ 工具池为空（CLAUDE.md 陷阱表，2026-09-30 实测） |
| 白名单明明配了却全拒 | YAML 列表经 `Environment.getProperty(key, List.class)` 读到 null → 必须用 `Binder`（2026-08-31 实测踩坑） |
| 路径工具在 Windows 开发机行为不同 | 部署目标为 Linux（大小写敏感、`/` 分隔）；命令经 POSIX `bash -c`，Windows 需 Git Bash 在 PATH |
| 技能元数据读到了正文 | 违反渐进披露 → 核对 `ContextLoaderTest` 的"注入文本不含 SKILL.md 正文"用例 |
| MCP server 改了但没生效 | 工具列表在启动连接时拉取并常驻 → 重启进程（缓存语义 ③） |
