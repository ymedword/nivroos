# Quickstart 验证指南：US-2 ReAct 循环

## 前提

- US-1 已交付并合并（ProviderService / 审计表 / 显式映射）
- JDK 21、Maven 3.9+、DeepSeek 或 Kimi 任一 API key

## 自动验证

```bash
mvn clean verify                 # 全量门禁：US-2 十个测试类 + US-1 回归全绿
mvn -pl nivroos-core -am test    # 只跑 core（循环/组装/执行/会话）
```

## Demo 一人工验收（SC-001）

1. **设凭证**（两种方式任选）：

   ```bash
   export DEEPSEEK_API_KEY=sk-xxx    # 方式一：环境变量
   ```

   或方式二：在运行目录放 `nivroos-secrets.yml`（git 忽略，见 FR-003 双通道）：

   ```yaml
   nivroos:
     providers:
       deepseek:
         api-key: sk-xxx
       kimi:
         api-key: sk-xxx
   ```

2. **构建与初始化**：

   ```bash
   mvn clean package
   cd /tmp/nivroos-demo && java -jar <repo>/nivroos-boot/target/nivroos-boot-0.1.0.jar init
   ```

3. **写天气 Agent**：创建 `.nivroos/agents/weather/AGENT.md`（模板见颗粒度
   文档 §3.3），tools 声明 `http_get`。

4. **对话**：

   ```bash
   java -jar <repo>/nivroos-boot/target/nivroos-boot-0.1.0.jar chat --profile weather
   > 查一下北京天气并告诉我穿什么
   ```

   预期：Agent 自主调用 `http_get` 拉取 wttr.in 北京天气 JSON（`http.allowed_domains`
   需含 `wttr.in`），第二轮给出穿衣建议；对话历史跨消息累积。

5. **审计核对**：直接查库（核心阶段无查询接口）：

   ```text
   llm_calls 表：N 条（每轮一次，session_id 非空）
   tool_invocations 表：1 条 success=true（http_get）
   ```

## 负向验证

| 操作 | 预期 |
| --- | --- |
| 问"访问一下 evil.com" | http_get 被 Sandbox 拒绝；tool_invocations 落 success=false + error_message |
| settings.max_iterations 设为 1 后问天气 | 循环 1 轮强制结束，返回固定文案（spec Clarifications） |
| 删除 Bootstrap 文件后对话 | WARN 日志，对话正常 |
| 对话中修改 AGENT.md 正文 | 下一轮消息即生效（无缓存） |
| `/quit` | 正常退出，会话消息保留 |

## 已知限制

- 会话为内存版（重启丢失；US-5 落 SQLite）
- 工具池为简化 Map（US-4 换 ToolRegistry）；白名单仅 HTTP 域名
- 长期记忆占位（US-3 接入 MemoryService）
