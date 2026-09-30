# NivroOS 发布前遗留清单

**用途**：把各模块交付时"经用户裁决延后"的事项集中登记，**整体开发完成（US-5 交付后）统一执行**。
每项都记了来源决议、执行方式与完成判据，避免散落在 CLAUDE.md 与各 spec 里被漏掉。

**维护纪律**：新模块交付时若有延后项，追加到本文件并在 CLAUDE.md「当前仓库状态」留一行指针；
**不在本清单内的功能诉求属于扩展阶段范围**（多租户 / SSO / 完整审计查询 / Tool Policy / SSE /
容器沙箱 / 通知渠道管理台），不进本表。

---

## A. 依赖与供应链

### A1. 依赖升级专项（Spring AI / Spring Boot 版本线配套重测）

- **来源**：2026-08-31 US-1 交付决议
- **执行**：升级版本线 → `mvn clean verify` 全量 → 重测 Spring AI 协议转换路径与 `@Tool` schema 生成
- **判据**：门禁全绿；升级后新增的依赖坐标逐个跑 `mvn dependency:tree` 核实（文档纪律：写依赖坐标前先核实存在）

### A2. 抑制文件 12 组逐组复核

- **来源**：2026-08-31 + 2026-10-01 US-4 决议
- **现状**：`config/dependency-check-suppressions.xml` = 12 组（89 条 `cve` + 3 条 `vulnerabilityName`）
- **执行**：版本线升级后逐组复核，移除不再适用的抑制
- **⚠ 两条必须遵守的方法要点**：
  1. **以文件的 CVE 列表为准，不要只看报告** —— <7.0 的条目被抑制后不再出现在报告的未抑制清单里，
     只看报告会误判为"已无风险"
  2. **对账必须同时覆盖报告按钮 `data-type-to-suppress` 的 `cve` 与 `vulnerabilityName` 两类** ——
     RetireJS 来源的条目 NVD 无收录，只用 `<cve>` 匹配不上；`cpe` 类与漏洞无关，忽略

### A3. Jackson 3.x 版本收敛

- **来源**：2026-10-01 US-4 交付时实测登记
- **现象**：`tools.jackson.core:jackson-databind` **3.0.1**（经 `logstash-logback-encoder:9.0`，nivroos-boot）
  与 **3.0.3**（经 Spring AI / MCP，nivroos-tool / nivroos-cli）跨模块并存，未收敛
- **执行**：收敛属依赖图调整（软门禁），随 A1 一并处理

### A4. ⚠ 不计入本清单但需提前触发：spring-data-jpa CVE-2026-47834

- **触发点是 US-5，不是发布前**：US-5 接入 REST 层开始接受查询参数时，**立即**重评该 CVE 的适用性
  （当前核心阶段全库无 `Sort` / `Pageable` 用法，不可达；已在抑制文件该组 notes 标注 RE-ASSESS）

### A5. `-Psecurity` 是否纳入 CI

- **现状**：`.github/workflows/quality-gates.yml` 跑的是不带 `-Psecurity` 的 `mvn verify`；
  OWASP dependency-check 只在本地手动跑（避免每次构建下载 NVD 库拖慢节奏）
- **执行**：决定是否加成夜间任务 / 手动触发；若加，配 `NVD_API_KEY` 加速

---

## B. 外部服务真机联调（人工冒烟，harness 已判卷，这几项等真服务）

### B1. Mem0 自托管联调

- **来源**：2026-09-30 US-3 交付决议；详见 [us3 quickstart §4](../specs/003-agent-memory/quickstart.md)
- **执行**：起自托管 Mem0 → `memory.backend: mem0` + `${MEM0_API_KEY}` → 跑 `save_memory` / `recall_memory`
- **⚠ 三条语义差异同步复核**（research §3 登记）：①`/memories` 写入的是 LLM 抽取后的记忆而非原文；
  ②`search` 是语义检索（与 markdown / sqlite 两档的关键词匹配不同）；③**精确路径与字段以部署实例的
  `/openapi.json` 为准**

### B2. 真实 MCP server（stdio）冒烟

- **来源**：US-4 quickstart §3.2
- **执行**：配真实 server（社区 `github-mcp` 或任一本地 server）→ 模型调一次 → 核对 `tool_invocations`
  有该工具名且 `success=1`；再故意写错 `command` 重启 → 核对**启动不中断** + WARN 日志 + 该 server 工具未注册

### B3. `@Tool` Bean（方式三重代码）冒烟

- **来源**：US-4 quickstart §3.3 —— boot 侧示例 `@Tool` Bean 被模型调用一次（同进程，无子进程）

### B4. Skill 渐进披露 L1→L2→L3 人工核对

- **来源**：US-4 quickstart §3.4；宪法原则四
- **执行**：挂真实技能软连接 → 模型从 L1 元数据命中 → `read_file` 读 `SKILL.md`（L2）→ 完成一次任务
- **判据**：系统提示词里**只有 name + description + 路径、无正文**（spec SC-006）

### B5. `shell` 跑捆绑脚本 + `notify` 真 webhook

- **来源**：US-4 quickstart §3.5
- **执行**：`python scripts/xxx.py` 经 `shell.allowed_commands` 放行、脚本产出进上下文；真实群机器人
  webhook 收到推送（`success=1`）；再把该域名移出白名单重试，核对**请求未发出**（失败审计 + 明确原因）

### B6. 方式一零代码上线 Demo

- **来源**：US-4 quickstart §3.1；需求 §13 Demo 二 能力四部分
- **执行**：配一个 Agent 目录（`AGENT.md` 声明工具 + 引用社区 MCP server）→ `nivroos chat --profile <agent>`
  跑一次日报类任务 → 核对 frontmatter 改动需重启（缓存语义 ②/④）

### B7. Demo 一 / Demo 二 真模型验收

- **来源**：US-2 quickstart（Demo 一查天气穿衣）、US-3 quickstart §2（Demo 二跨对话记偏好）
- **执行**：设真凭证（`DEEPSEEK_API_KEY` 或 `nivroos-secrets.yml`）→ 用真模型跑，**不进 CI**

### B8. 三个每日自动运行 Demo（依赖 US-5）

- **来源**：需求 §13 / 技术方案 §12（每日天气、每日科技日报、每日 GitHub 日报）
- **说明**：由 `AgentScheduler` 钟推触发（US-5 交付后才有），覆盖 Skill 渐进披露 L1/L2/L3；
  GitHub 日报 Demo 演示带 `scripts/` 脚本的 Agent 目录

---

## C. 安全与凭证

### C1. API key 轮换

- **来源**：历史决议
- **执行**：US-1 期间使用过的 **DeepSeek / Kimi key 发布前必须轮换**（历史泄露记录）

### C2. 入库物复检

- **执行**：发布前复核 `nivroos-secrets.yml` / `*.db*` / `.nivroos/` / `output/` 均未被 git 跟踪
  （当前状态：已在 `.gitignore` 内，历次提交的变更集自检为空）

---

## D. 执行顺序建议

1. **A4 最先**（US-5 期间就要做，不是发布前）
2. **A1 → A2 → A3**（依赖升级专项串起来做，A2 是 A1 的验收环节）
3. **B7**（真模型，先确认基础链路）
4. **B2 / B3 / B5 / B6 / B4**（US-4 五项冒烟，建议按此顺序，每项留一条 `tool_invocations` 证据行）
5. **B1**（Mem0 服务就绪后）
6. **B8**（US-5 交付后）
7. **C1 / C2 / A5**（发布前最后一步）
