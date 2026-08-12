# 快速开始

NivroOS 以 Agent 目录作为业务指令与运行时之间的边界。下面展示第一次使用时的基本流程。

## 1. 初始化工作区

```bash
nivroos init
```

这会创建 `.nivroos/` 工作区，用于存放 Agent、共享 Skill、Memory、Session、日志和输出。

## 2. 创建 Agent

```bash
nivroos profile create ops-agent
```

编辑 `.nivroos/agents/ops-agent/AGENT.md`，定义 Provider、Tool、Channel 以及 Agent 的任务指令。

```yaml
name: ops-agent
description: 运维助手

provider:
  name: deepseek
  model: deepseek-chat

tools:
  - read_file
  - shell
```

## 3. 开始对话

```bash
nivroos chat --profile ops-agent
```

运行时会先加载 Agent Profile、Bootstrap 文件、Session 上下文和可用 Tool，然后进入 ReAct 循环。

## 4. 对外提供服务

```bash
nivroos serve
```

业务系统、内部门户或自动化流程可以通过 REST Service 接入同一个 Agent 运行时。

## 配置与密钥

Provider 凭证不要直接写入 Agent 文档，应该通过环境变量或部署平台的 Secret 管理。例如：

```bash
set DEEPSEEK_API_KEY=your-key
```

以上命令描述的是产品的目标使用流程；随着运行时模块发布，具体安装和部署选项会继续补充到文档中。
