# 契约：CLI 命令（US-2 用户可见接口）

## nivroos init

```text
nivroos init    # 在当前目录创建 .nivroos/ 工作区
```

行为契约：

1. 幂等：已存在的目录与文件一律不覆盖，缺失项补齐。
2. 创建结构（技术方案 §8.1）：

   ```text
   .nivroos/
   ├── agents/          # 每子目录一个 Agent（AGENT.md）
   ├── skills/          # 公共 Skill 实体库
   ├── memory/MEMORY.md # 长期记忆
   ├── mcp_servers.yaml # MCP 配置（默认空模板）
   ├── sessions/        # 备用目录
   ├── logs/            # 结构化日志
   ├── AGENTS.md        # Bootstrap
   ├── SOUL.md          # Bootstrap
   ├── USER.md          # Bootstrap
   └── nivroos.db       # SQLite（首次启动自动创建）
   ```

3. 失败（如无写权限）必须报错退出并指明原因，不静默。

## nivroos chat

```text
nivroos chat --profile <name>          # 交互式多轮对话
nivroos chat --profile <name> --message "文本"   # 发单条后退出
```

行为契约：

1. `--profile` 必填；Agent 不存在时报错并列出可用 Agent。
2. 交互模式：每行一条消息 → AgentService.process → 打印最终响应；`/quit`
   退出；EOF 等同退出。
3. 每轮响应打印前须完整（同步阻塞，无流式）。
4. 会话身份：channel=cli、user=本地用户标识、profile=指定名——session_id
   由此三者在 SessionManager 内联合生成，同一 Agent 的历次对话复用同一会话
   （历史按 max_history_turns 截断）。
5. 异常必须打印清晰错误（不堆栈）并保持进程存活（交互模式）。
