# Quick Start

NivroOS uses an agent directory as the boundary between business instructions and the runtime. The commands below show the intended first-run flow.

## 1. Initialize a workspace

```bash
nivroos init
```

This creates the `.nivroos/` workspace structure for agents, shared skills, memory, sessions, logs, and outputs.

## 2. Create an agent

```bash
nivroos profile create ops-agent
```

Edit `.nivroos/agents/ops-agent/AGENT.md` and define the provider, tools, channels, and task instructions for the agent.

```yaml
name: ops-agent
description: Operations assistant

provider:
  name: deepseek
  model: deepseek-chat

tools:
  - read_file
  - shell
```

## 3. Start a conversation

```bash
nivroos chat --profile ops-agent
```

The runtime loads the agent profile, bootstrap files, session context, and available tools before entering the ReAct loop.

## 4. Expose the runtime

```bash
nivroos serve
```

Use the REST service to connect existing applications, internal portals, or automation workflows to the same agent runtime.

## Configuration and secrets

Keep provider credentials outside agent documents. Configure secrets with environment variables or your deployment secret manager, for example:

```bash
set DEEPSEEK_API_KEY=your-key
```

The commands describe the intended product workflow; detailed installation and deployment instructions will continue to evolve with the runtime modules.
