# Architecture

NivroOS is a Java 21 and Spring Boot based runtime. Its design separates access, execution, capabilities, and persistence so every trigger follows the same agent path.

<ArchitectureDiagram :is-zh="false" />

## Runtime flow

```text
CLI / REST API / Scheduler
            |
       AgentService
            |
        ReActLoop
       /    |    \
  Provider Memory  ToolExecutor
                    |
              Sandbox + MCP
```

CLI messages, REST calls, and scheduled tasks converge on `AgentService`. The same runtime then builds context, calls the configured provider, executes tools through the tool executor, and persists the result.

## Core layers

### Access layer

The access layer receives work from the CLI, Web Service, or scheduler. Channels are responsible for getting a request in and a response out; they do not own the agent execution algorithm.

### Engine layer

`ReActLoop`, `PromptBuilder`, `ToolExecutor`, and `AgentService` form the runtime core. The loop decides whether to return a final response or continue with another tool invocation.

### Capability layer

Provider, Memory, and Tool are shared services. They can be reused by multiple agents without putting provider-specific or integration-specific logic into every `AGENT.md`.

### Foundation layer

Profiles, sessions, SQLite persistence, configuration, and secret loading support the runtime. Provider keys and other credentials are expected to be injected through environment variables.

## Security and observability

Tool execution is checked against configured sandbox boundaries before the tool is allowed to continue. LLM calls and tool invocations are designed to be persisted from the first release, making the execution path inspectable instead of opaque.
