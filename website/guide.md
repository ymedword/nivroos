# What is NivroOS?

NivroOS is an open-source Agent OS for enterprise teams. It provides a shared runtime foundation for business agents: model access, reasoning loops, memory, tools, channels, and REST integration.

The platform is designed for private deployment on your own Kubernetes cluster or servers. Enterprise data stays inside the infrastructure your team controls, while agents share one consistent operational model.

## The five core capabilities

### 1. LLM provider abstraction

Connect leading providers through a unified abstraction. Agent definitions do not need to know which model is active, so teams can change providers without rewriting business logic.

### 2. Self-implemented ReAct loop

NivroOS owns the reason–act–observe cycle: the model reasons about the task, calls a tool when needed, receives the result, and continues until it can provide an outcome.

### 3. Layered memory

Session history and long-term `MEMORY.md` work together to keep conversations useful across turns and projects. Additional contextual memory can be added as the platform grows.

### 4. Extensible tools

Use built-in file, shell, HTTP, memory, and notification tools. Extend agents with reusable MCP servers or Java `@Tool` beans, depending on the integration depth you need.

### 5. REST service

Expose sessions, agents, memory, tools, and health information through a REST API so existing business systems can integrate without coupling to the runtime internals.

## One directory, one agent

An agent starts with a directory and an `AGENT.md` file. Frontmatter describes the runtime profile; the document body describes the agent's instructions. Skills, scripts, references, and MCP servers can be added when the business workflow needs them.

This keeps the boundary clear: business teams describe what an agent should do, while NivroOS provides the runtime that makes it executable.

## Designed for enterprise environments

- Private deployment on Kubernetes or servers
- Explicit provider and tool boundaries
- Sandbox checks for file paths, commands, and HTTP domains
- Persistent sessions and auditable LLM/tool calls
- A Java and Spring Boot foundation that fits existing enterprise systems
