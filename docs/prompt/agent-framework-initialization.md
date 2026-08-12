# Agent 框架初始化 — 核心提示词

> 来源：NivroOS 项目初始化会话（2026-08-12）实测有效的提示词提炼。
> 用途：新项目 / 新 Agent 框架从零初始化时，按阶段直接复用；每条提示词附关键要求和对应产出。

## 一、项目认知

**提示词：**

```text
理解一下 docs 目录下的这 4 个文档，这 4 个文档就是这个项目要做的事情。
```

**关键要求 / 产出：**

- 通读全部项目文档后再行动，不遗漏（需求、技术方案、调研、实施指南）
- 产出：对项目 Why / What / How 的完整理解

## 二、文档职责划分确认（各司其职，必问）

**提示词：**

```text
docs 目录下的这 4 份文档各司其职，请按以下口径理解，不要把它们当成前后演进的版本：
1. 每份文档只管自己的职责——调研（Why）、需求（What）、技术方案（How）、编程指南（How 落地），互不替代；
2. 验收 Demo 的总数量，是这 4 份文档各自列举的 Demo 数量加起来的总和；
3. 文档中建立了对应表格、但还没有对应实现的部分，是给后续扩展开发预留的接口；
4. notify / NotifyTools 这个模块暂时不做，也不会影响后续 spec-kit 开发。
```

**关键要求 / 产出：**

- 多文档并存时先确认职责划分（各司其职还是前后演进），差异是设计使然，**不得擅自"修正"文档**
- 明确裁决顺序（如：细节冲突以最新技术方案为准）
- 未实现但已建表的接口 = 预留扩展位，不得误报为矛盾

## 三、CLAUDE.md 生成（参考卡级别）

**提示词：**

```text
根据这些信息，生成 claude.md（参考 D:\code\oryxos\CLAUDE.md 的参考卡级别）。
要求：
- 覆盖：技术栈表、模块结构、Constitution（不可违背原则）、工作区结构、核心数据模型、
  ReAct 循环机制、Tool 体系、Web API、CLI 命令、配置规则、验收 Demo、实施节奏、常见陷阱、设计原则
- 内容以本项目自己的文档为准，不混入其他项目（兄弟项目）的决策
- 关键约束写成"不得/必须"句式 + 违反症状 + 修复方案（宪法级）
- markdownlint 零警告
```

**关键要求 / 产出：**

- 参考卡级别：不是概述，是可执行的完整规范
- 生成前确认参考对象和权威源（哪个文档最权威、哪些原则来自哪些章节）
- 产出：项目级 CLAUDE.md，作为后续所有开发的一致性基准

## 四、方法论沉淀（宪法级 + 跨项目持久化）

**提示词：**

```text
后面生成项目文档、claude.md、spec-kit、skill 等等，怎样让生成内容完整，且是宪法级别的？
怎么让后面生成新文档和新项目都适用？claude 有相关设置吗？还是说在提示词上优化？
```

**关键要求 / 产出：**

- 5 步框架：完整采集 → 权威裁决 → 宪法级表述 → 双层校验（事实对源 + 格式对 lint）→ 防漂移
- 持久化三层：用户级 `~/.claude/CLAUDE.md`（跨项目方法论）、个人 skill（如 doc-standard）、项目级 CLAUDE.md（本项目的宪法）
- 每条宪法原则自检 5 问：是否"不得/必须"？违反症状？修复方案？边界条件？可验证性？

## 五、README 开源标准

**提示词：**

```text
根据这些信息，生成 readme，按照开源项目标准来写。
要求：
- 顶部 badge 区：Version、MCP、Native(GraalVM)、Apache 2.0 License
- 全文使用英文
```

**关键要求 / 产出：**

- 开源标准：badges、Introduction、Key Features、Why、Architecture、Quick Start、Documentation、
  Project Structure、Roadmap、Contributing、Acknowledgments、License
- 面向开源读者用英文；内部文档用中文
- 无 CI 时 badge 用静态 shields.io；badge 无链接就不带 `(...)`

## 六、Maven 骨架初始化（可编译、可打包、可运行）

**提示词：**

```text
使用 maven 初始化这个项目，按照文档中的模块，并能通过编译打包验证。
```

**关键要求 / 产出：**

- 按文档模块结构建根 POM + 各模块 POM，模块数量固定、不拆不并
- 版本矩阵必须实测：先查 Maven Central（repo1.maven.org 目录 + POM 内容）再写坐标，
  BOM 实际管理范围以 POM 内容为准；配套关系（Boot ↔ Spring AI ↔ 厂商 starter）以依赖方 POM 为准
- 验收：`mvn clean package` 全模块 BUILD SUCCESS，fat JAR `java -jar` 完整启动
  （Tomcat + 数据源 + 方言全部就绪，无 NoClassDefFoundError）
- 敏感配置（API key）只允许 `${ENV_VAR}` 占位，骨架期未配置的自动配置显式禁用（如 `spring.ai.dashscope.enabled: false`）

## 七、防漂移固化

**提示词：**

```text
1. 这些修复点（springdoc 版本陷阱、starter 分发方式）记入 CLAUDE.md 的常见陷阱表；
2. 创建 ~/.claude/CLAUDE.md 全局质量标准和 doc-standard skill。
```

**关键要求 / 产出：**

- 实测踩坑（版本线陷阱、弃用坐标、配套关系）→ 固化进项目 CLAUDE.md"常见陷阱"表，不靠记忆
- 跨项目可复用的方法论 → 用户级 `~/.claude/CLAUDE.md` + `~/.claude/skills/doc-standard/SKILL.md`
- 项目记忆（memory/）记录非显而易见的工作方式结论

## 八、可运行验证（main 函数）

**提示词：**

```text
boot 能 java -jar 运行了吗？是一个 main 函数。
nivroos 的 cli 需要有一个 main 函数，它要能运行，打印 nivroos 的版本信息。
```

**关键要求 / 产出：**

- 可执行 JAR 必须有且仅有一个 Spring Boot 入口 main（Start-Class）
- CLI 模块独立 main（Picocli）：轻命令（version/help）不启动 Spring、启动快；
  版本号不硬编码，从打包 manifest 的 `Implementation-Version` 读（单一来源 = 根 POM）
- 验证矩阵：`version` / `--version` / `--help` / 无参数启动服务，逐项实测
