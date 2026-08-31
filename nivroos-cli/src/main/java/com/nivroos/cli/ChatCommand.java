package com.nivroos.cli;

import com.nivroos.channel.cli.CliChannel;
import com.nivroos.core.context.ContextLoader;
import com.nivroos.core.loader.AgentLoader;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileRegistry;
import com.nivroos.core.provider.ProviderService;
import com.nivroos.core.provider.ToolInvocationStore;
import com.nivroos.core.react.AgentService;
import com.nivroos.core.react.PromptBuilder;
import com.nivroos.core.react.ReActLoop;
import com.nivroos.core.react.ToolExecutor;
import com.nivroos.core.session.InMemorySessionManager;
import com.nivroos.core.session.Session;
import com.nivroos.core.session.SessionManager;
import com.nivroos.tool.HttpTools;
import com.nivroos.tool.sandbox.Sandbox;
import com.nivroos.tool.sandbox.WhitelistSandbox;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code nivroos chat} —— 交互式多轮对话（技术方案 §8.6）。
 *
 * <p>Spring-path command: runs inside the boot context because it needs the ProviderService and
 * audit store beans (US-1/US-3). Assembles the whole ReAct chain (granularity doc §2.3) from
 * US-1/US-2 deliverables; the tool pool is a plain map (US-4 replaces it with ToolRegistry).
 * Contracts: contracts/cli-commands.md.
 */
@Component
@Command(name = "chat", description = "交互式多轮对话（--message 发单条后退出）", mixinStandardHelpOptions = true)
public class ChatCommand implements Runnable {

  @Option(
      names = "--profile",
      required = true,
      description = "Agent 名（.nivroos/agents/<name>/AGENT.md）")
  private String profile;

  @Option(names = "--message", description = "发单条消息后退出")
  private String message;

  private final ProviderService providerService;
  private final ToolInvocationStore toolInvocationStore;
  private final Environment environment;

  public ChatCommand(
      ProviderService providerService,
      ToolInvocationStore toolInvocationStore,
      Environment environment) {
    this.providerService = providerService;
    this.toolInvocationStore = toolInvocationStore;
    this.environment = environment;
  }

  /** Picocli 执行入口（boot 路径经 NivroOsApplication 分发调用）。 */
  public int execute(String[] args) {
    // 分发时 argv[0] 是 "chat"，Picocli 只应看到其后的参数
    String[] commandArgs =
        args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];
    // 异常统一转清晰错误 + 退出码 1，不打印堆栈（契约 cli-commands.md）
    return new CommandLine(this)
        .setExecutionExceptionHandler(
            (ex, commandLine, parseResult) -> {
              commandLine.getErr().println("错误: " + ex.getMessage());
              return 1;
            })
        .execute(commandArgs);
  }

  @Override
  public void run() {
    Path workspace = Path.of(".nivroos");
    Path agentsRoot = workspace.resolve("agents");

    AgentLoader agentLoader = new AgentLoader(agentsRoot);
    Profile agentProfile = agentLoader.loadProfile(profile);
    ProfileRegistry registry = new ProfileRegistry();
    registry.register(agentProfile);

    ContextLoader contextLoader = new ContextLoader(agentsRoot.resolve(profile), workspace);
    PromptBuilder promptBuilder = new PromptBuilder(contextLoader);

    Sandbox sandbox = new WhitelistSandbox(allowedDomains());
    HttpTools httpTools = new HttpTools(sandbox, HttpClient.newHttpClient());
    Map<String, NivroTool> toolPool = Map.of("http_get", httpTools.httpGet());
    ToolExecutor toolExecutor = new ToolExecutor(toolPool, toolInvocationStore);

    ReActLoop loop = new ReActLoop(providerService, promptBuilder, toolExecutor, toolPool);
    AgentService agentService = new AgentService(loop, registry);

    SessionManager sessionManager = new InMemorySessionManager();
    Session session =
        sessionManager.getOrCreate("cli", System.getProperty("user.name", "local"), profile);

    try {
      if (message != null) {
        // 单条模式：失败异常由 execute() 的统一处理器转清晰错误（不堆栈）
        System.out.println(agentService.process(session, message));
        return;
      }
      new CliChannel().start(session, agentService);
    } catch (IOException e) {
      throw new IllegalStateException("CLI channel failed", e);
    }
  }

  private List<String> allowedDomains() {
    // YAML 列表必须经 Binder 读取：Environment.getProperty 返回 null
    // （列表以索引键存储，2026-08-31 Demo 实测踩坑）
    return org.springframework.boot.context.properties.bind.Binder.get(environment)
        .bind(
            "http.allowed-domains",
            org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
        .orElse(List.of());
  }
}
