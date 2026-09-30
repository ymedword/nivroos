package com.nivroos.cli;

import com.nivroos.channel.cli.CliChannel;
import com.nivroos.core.context.ContextLoader;
import com.nivroos.core.loader.AgentLoader;
import com.nivroos.core.memory.MemoryService;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileRegistry;
import com.nivroos.core.provider.ProviderService;
import com.nivroos.core.provider.ToolInvocationStore;
import com.nivroos.core.react.AgentService;
import com.nivroos.core.react.PromptBuilder;
import com.nivroos.core.react.ReActLoop;
import com.nivroos.core.react.ToolExecutor;
import com.nivroos.core.session.Session;
import com.nivroos.core.session.SessionManager;
import com.nivroos.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code nivroos chat} —— 交互式多轮对话（技术方案 §8.6）。
 *
 * <p>Spring-path command: runs inside the boot context because it needs the ProviderService and
 * audit store beans (US-1/US-3). Assembles the whole ReAct chain (granularity doc §2.3) from
 * US-1/US-2 deliverables; the tool pool comes from the injected {@link ToolRegistry} (US-4), so
 * built-in tools and {@code @Tool} beans reach the loop through one registration path. Contracts:
 * contracts/cli-commands.md.
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
  private final ToolRegistry toolRegistry;
  private final MemoryService memoryService;
  private final SessionManager sessionManager;

  public ChatCommand(
      ProviderService providerService,
      ToolInvocationStore toolInvocationStore,
      ToolRegistry toolRegistry,
      MemoryService memoryService,
      SessionManager sessionManager) {
    this.providerService = providerService;
    this.toolInvocationStore = toolInvocationStore;
    this.toolRegistry = toolRegistry;
    this.memoryService = memoryService;
    this.sessionManager = sessionManager;
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

    // Profile 进构造器：Bootstrap 列表与技能绑定都按 Agent 自己的声明生效（US-4 前序改造点 4）
    ContextLoader contextLoader =
        new ContextLoader(agentProfile, agentsRoot.resolve(profile), workspace);
    PromptBuilder promptBuilder = new PromptBuilder(contextLoader, memoryService);

    // 工具池取自容器注册表（US-4 前序改造点 3）：沙箱、白名单与各工具 Bean 由 ToolConfiguration 装配，
    // 本命令不再自己 new 工具——否则注解 Bean 与内置工具会走两条注册路径
    Map<String, NivroTool> toolPool = toolRegistry.all();
    ToolExecutor toolExecutor = new ToolExecutor(toolPool, toolInvocationStore);

    ReActLoop loop = new ReActLoop(providerService, promptBuilder, toolExecutor, toolPool);
    AgentService agentService = new AgentService(loop, registry);

    // 会话管理器取自容器（与 MemoryService 共用同一实例），不再各自 new——否则门面读不到本轮历史
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
}
