# Quickstart: US-3 Memory 验证指南

> 可执行的验证路径，证明「记住 → 跨会话复现」端到端成立。
> 契约细节见 [contracts/](./contracts/)，数据形状见 [data-model.md](./data-model.md)。

## 0. 前置

- JDK 21 + Maven 3.9.x（`mvn -v` 应显示 Java 21）
- 仓库根为当前工作目录（`.nivroos/` 与 `nivroos-secrets.yml` 都按**进程工作目录**
  相对查找，见 `application.yml` 第 13 行的 `optional:file:./`）
- LLM 凭证已就位：`nivroos-secrets.yml` 内 `nivroos.providers.deepseek.api-key`
  （或环境变量 `DEEPSEEK_API_KEY`）
- 工作区已初始化：`nivroos init`（幂等；它负责创建
  `.nivroos/memory/MEMORY.md` 及 `## 核心记忆` / `## 归档记忆` 两个分区骨架）

## 1. 自动化验证（判定「实现完成」的机器门禁）

```bash
# 全量门禁：Spotless + Checkstyle + SpotBugs(findsecbugs) + 测试 + JaCoCo
mvn clean verify

# 只跑本模块测试（-am 必须带：不带会解析本地仓库旧版本模块 jar）
mvn -pl nivroos-memory -am test

# 关键回归单测逐个点名
mvn -pl nivroos-memory -am test -Dtest=MarkdownMemoryStoreTest#load_coreSectionNeverTruncated
mvn -pl nivroos-memory -am test -Dtest=MarkdownMemoryStoreTest#load_rereadsFileAfterAppend
mvn -pl nivroos-core -am test -Dtest=PromptBuilderTest#build_includesMemoryContent

# 前序模块回归（跨模块契约证据）
mvn -pl nivroos-core,nivroos-provider,nivroos-tool,nivroos-storage,nivroos-cli -am test
```

**预期**：全部 BUILD SUCCESS；三个点名单测通过（它们的 `@DisplayName` 分别是
「核心记忆区永不被截断：归档区超限后核心区完整返回」「不缓存：save 后下一次
load 立即读到新内容」「Prompt 四部分含 Memory 注入：记忆内容出现在请求消息中」）。

**不碰网络**：以上测试全部离线——Markdown 档用 `@TempDir`，SQLite 档用内存/临时
库，Mem0 档 mock `HttpClient`。

## 2. Demo 二 人工验收（真模型，跨对话记偏好）

> 这是 spec 的 P1 场景，也是 US-3 的验收锚点。**用真模型，不进 CI**。

### 2.1 第一轮：Agent 主动记住

```bash
java -jar nivroos-boot/target/nivroos-boot-0.1.0.jar chat --profile smoke
```

> `--profile` 指向 `.nivroos/agents/<name>/AGENT.md`；该 Agent 的 `tools:` 需含
> `save_memory` 与 `recall_memory`（见 [contracts/memory-tools.md](./contracts/memory-tools.md)）。

输入：

```text
我的项目用 Spring Boot，部署在 K8s 上
```

**预期**：

1. 模型自主调用 `save_memory`（Agent 判断值得长期保留，用户不需要说"请记住"）；
2. `.nivroos/memory/MEMORY.md` 的 `## 核心记忆` 分区下出现该条（带 `### <今日日期>`
   header）——分区由模型经 `scope` 参数显式指定，系统不猜；
3. `tool_invocations` 表中该次调用 `success=1`：

```bash
sqlite3 nivroos.db \
  "SELECT tool_name, success, duration_ms, created_at FROM tool_invocations ORDER BY id DESC LIMIT 3;"
```

### 2.2 第二轮：跨会话复现

**重启进程**（或直接新开一个 `chat` 会话），问：

```text
帮我看看我的项目能用什么数据库
```

**预期**：Agent 的回复**体现此前记下的 Spring Boot / K8s 语境**（例如围绕
Spring 生态与容器化部署给建议），用户全程没有重复解释背景。

### 2.3 归档区检索

```text
上次我们讨论过什么方案？
```

**预期**：模型调用 `recall_memory`，命中归档区内容并复述；核心区内容**不作为
检索结果来源**（它已经在每轮上下文里）。

### 2.4 不缓存对照（可选，验契约①）

在同一个会话里先让它记一条，**紧接着**再问起该内容——无需重启进程即可被引用
（`load` 每轮重读，无缓存）。

## 3. 后端切换验证（spec SC-004）

```bash
# 1) 以默认 markdown 档完成一遍 2.1~2.2，确认记忆落在 MEMORY.md
# 2) 只改这一行：
#      memory: backend: markdown  →  backend: sqlite
# 3) 重启，重复 2.1（写入）与 2.2（复现）

sqlite3 nivroos.db "SELECT id, scope, substr(content,1,40), created_at FROM memory_entries ORDER BY id;"
```

**预期**：系统正常启动；同一套场景仍然通过；`memory_entries` 表出现该条且
`scope` 列承载分区；**`MemoryService` 及以上代码零改动**。

## 4. 待服务就绪的人工项：Mem0 自托管

Mem0 档核心阶段**只交付代码 + mock 单测**，真实联调待自托管服务就绪：

```bash
# 起一个自托管 Mem0（示例，端口随部署方式而变）
docker pull mem0/mem0-api-server
docker run -p 8000:8000 --env-file .env mem0-api-server
```

```yaml
memory:
  backend: mem0
  mem0:
    url: http://localhost:8000
    api-key: ${MEM0_API_KEY}
```

**预期**：`GET /openapi.json` 可访问；`save_memory` → `POST /memories`；
`recall_memory` → `POST /search`（返回带 `score` 的语义匹配）。

**已知语义差异**（不是缺陷，是选型固有代价，详见
[research.md](./research.md) §3）：① `/memories` 会做 LLM 抽取，写入的是抽取后的
记忆而非原文；② `search` 是语义检索，与另两档的关键词匹配不同；③ 精确路径/字段
随 OSS 版本演进，**以部署实例的 `/openapi.json` 为准**。

## 5. 常见问题

| 现象 | 原因 | 处置 |
| --- | --- | --- |
| 启动报 `api-key must be an ${ENV_VAR} placeholder` | mem0 凭证写了明文 | 改成 `${MEM0_API_KEY}` 或放进 `nivroos-secrets.yml` |
| 启动报 `Environment variable not set: MEM0_API_KEY` | 环境变量未设置 | 导出该变量，或走密钥文件双通道 |
| 启动报 backend 取值非法 | `memory.backend` 拼错 | 用 `markdown` / `sqlite` / `mem0` 之一 |
| 核心区内容被截断 | **不应发生** | 这是契约②违规，回去查 `load()` 的截断是否只作用于归档区 |
| `mvn -pl nivroos-memory test` 报 class not found | 没带 `-am`，解析到本地仓库旧 jar | 加上 `-am` |
