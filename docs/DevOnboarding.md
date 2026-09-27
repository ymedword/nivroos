# NivroOS 新设备继续开发说明

本文说明在另一台设备上拉取本仓库后，如何恢复到"可以继续开发某个 US/模块"的状态。

核心原则一句话：**可继续开发所需的一切（代码、文档、规格、Skill、Spec-Kit 模板、项目宪法）都在仓库里；不在仓库里的只有三类——Git 本地配置、Claude 侧本地状态、运行时数据与密钥，这三类必须在新设备重建。**

仓库地址：`https://github.com/ymedword/nivroos.git`

---

## 1. 前置工具链

| 组件 | 要求 | 说明 |
| --- | --- | --- |
| JDK | 21（Temurin 21.0.x 实测） | 宪法要求；virtual thread 与 `spring.threads.virtual.enabled=true` 依赖它 |
| Maven | 3.9+（3.9.16 实测） | `mvn clean package` 产出 fat JAR |
| Git | 任意近期版本 | |
| Node.js | 20+，**仅** `website/` 站点需要 | 核心开发不需要 |

**不得**用 JDK 17 或更低版本构建。违反症状：构建或运行报 `UnsupportedClassVersionError`。修复：切到 JDK 21 后重新 `mvn clean package`。

---

## 2. 首次拉取后的必做步骤

```bash
git clone https://github.com/ymedword/nivroos.git
cd nivroos
git config core.hooksPath .githooks      # 必做
git config commit.template .gitmessage   # 必做
mvn clean package                        # 产物 nivroos-boot/target/nivroos-boot-0.1.0.jar
```

前两条 `git config` 写的是 `.git/config`，**不随仓库分发**，每台新设备都要执行一次：

- **不得**跳过 `core.hooksPath` 设置。违反症状：提交时 pre-commit 门禁静默不跑，格式问题（Spotless）推到 CI 才暴露。修复：重设后重新提交。
- **不得**跳过 `commit.template` 设置。违反症状：`git commit` 不再带出 `.gitmessage` 的五段式模板（Problem / Design / Testing / Impact）。修复：重设。

密钥注入（二选一，**均不得写入任何入库文件**）：

```bash
# 通道一：环境变量
export DEEPSEEK_API_KEY=<key>
export KIMI_API_KEY=<key>

# 通道二：本地明文配置文件（已被 .gitignore 排除，不入库）
# 在仓库根目录创建 nivroos-secrets.yml，由 application.yml 的
# optional:file:./nivroos-secrets.yml 导入
```

**不得**把明文 api-key 写进 `application.yml` / `AGENT.md` / Profile YAML。违反症状：`ProviderProperties` 校验直接拒绝并指明键路径（`nivroos.providers.<name>.api-key`），启动失败。修复：改回 `${ENV_VAR}` 占位，或放进 `nivroos-secrets.yml`。

工作区初始化（可选，用到 Agent 目录时才需要）：

```bash
java -jar nivroos-boot/target/nivroos-boot-0.1.0.jar init   # 幂等，已存在的文件不覆盖
```

---

## 3. 继续开发某个 US / 模块

驱动文档是**模块执行颗粒度文档**，路径约定 `docs/us/us{N}-<slug>.md`，已入库的：

| US | 颗粒度文档 | 状态 |
| --- | --- | --- |
| US-1 | [docs/us/us1-provider.md](us/us1-provider.md) | 已完成 |
| US-2 | [docs/us/us2-react.md](us/us2-react.md) | 已完成 |
| US-3 | [docs/us/us3-memory.md](us/us3-memory.md) | 文档已就位，编码未开始 |

续做步骤：

```bash
git pull                                  # 开工前必做
# 在 Claude Code 中执行：
/module-dev 3                             # 编排：备料 → specify → clarify → plan → tasks → analyze → implement → 验收报告
git add -A && git commit && git push      # 收工前必做
```

- 编排流程产出的规格落在 `specs/00{N}-<slug>/`，**必须随进度提交推送**。违反症状：未推送的部分只存在于旧设备，换设备后要么重做、要么两台设备分叉。
- 模块编号的分流规则、门禁口径、技术栈固定句、语法禁区都在 [.claude/skills/module-dev/SKILL.md](../.claude/skills/module-dev/SKILL.md) 的「项目配置区」——该文件已入库，随仓库分发。
- 裁决顺序：细节冲突时以 [docs/TechnicalSolution.md](TechnicalSolution.md) 为准（见 [CLAUDE.md](../CLAUDE.md) 文档体系说明）。

---

## 4. 不随仓库分发的内容与重建方式

| 内容 | 为何不在仓库 | 新设备恢复方式 |
| --- | --- | --- |
| `core.hooksPath`、`commit.template` | 写在 `.git/config`，属设备本地配置 | 见第 2 节两条 `git config` |
| API key、`nivroos-secrets.yml`、`.env` | 安全约束：密钥不得入库 | 重新注入环境变量或重建本地文件 |
| `.nivroos/` 工作区 | 运行时工作区，已被 `.gitignore` 排除 | `nivroos init` 重建；其中手写的 `USER.md` / `AGENTS.md` / `SOUL.md` 内容不会回来，需人工带过去 |
| `nivroos.db` | 运行时数据，已被 `.gitignore` 排除 | 无需恢复。Session 与 `tool_invocations` / `llm_calls` 审计数据不带过去（设计如此） |
| `.specify/feature.json` | 当前 feature 指针，已被 `.gitignore` 排除 | 跑 Spec-Kit 命令时按需重建，无需手工处理 |
| `~/.claude/CLAUDE.md`（全局指令） | 用户级配置，不在项目仓库内 | 手工复制。缺失时 Claude 的文档方法论、markdownlint 规则、版本核实纪律会失效 |
| Claude 自动记忆目录 | 用户级配置，按项目路径存放 | 手工复制。缺失时丢失既有项目记忆（如文档质量工作流） |
| `target/`、`website/node_modules/` | 构建产物 | `mvn clean package`、`cd website && npm install` |

---

## 5. 常见失败与排查

| 症状 | 根因 | 修复 |
| --- | --- | --- |
| 提交时没有任何格式检查输出 | `core.hooksPath` 未设 | `git config core.hooksPath .githooks` |
| `git commit` 不弹五段式模板 | `commit.template` 未设 | `git config commit.template .gitmessage` |
| 启动报 api-key 必须是 `${ENV_VAR}` 占位 | 密钥写成明文 | 改占位符或移入 `nivroos-secrets.yml` |
| 启动报 `${DEEPSEEK_API_KEY}` 未解析 | 环境变量未导出 | 导出该变量，或改用通道二 |
| 构建报 `UnsupportedClassVersionError` | JDK 版本低于 21 | 切 JDK 21 |
| `mvn verify` 的 spotless:check 失败 | 手改了格式 | `mvn spotless:apply` 后重新提交，不手改格式 |
| 非 Windows 设备上 Spec-Kit 脚本不可用 | `.specify/scripts/` 当前只有 PowerShell 版 | 安装 `pwsh`，或在 Windows 设备上执行编排 |

更完整的陷阱清单（Spring AI 自动装配排除、Provider 显式映射、SQLite 迁移、白名单读取等）见 [CLAUDE.md](../CLAUDE.md) 的「常见陷阱」表。

---

## 6. 当前进度锚点

以 [CLAUDE.md](../CLAUDE.md) 的「当前仓库状态」为准。截至本文撰写（2026-09-27）：

- 已交付：US-1（LLM Provider 抽象）、US-2（ReAct 循环 + CLI Channel）
- 已实现的 CLI 子命令：`init`、`chat`、`version`；`serve` / `status` / `profile` / `provider` / `tool` / `session` 尚未实现
- [README.md](../README.md) 的 Quick Start 描述的是目标形态，不代表当前可用命令集
