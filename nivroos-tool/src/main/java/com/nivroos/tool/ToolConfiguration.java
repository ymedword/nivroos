package com.nivroos.tool;

import com.nivroos.core.notify.NotifyChannelStore;
import com.nivroos.tool.sandbox.Sandbox;
import com.nivroos.tool.sandbox.WhitelistSandbox;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 工具装配（技术方案 §6.2、§6.6；契约 contracts/builtin-tools.md）。
 *
 * <p>Single assembly point for the whole tool module: the whitelist sandbox, the registry and (from
 * later tasks) every tool bean live here, so the boot context picks them up by component scan. 三类
 * 工具来源（内置 / 注解 Bean / MCP）在注册表里同形，注册表扫描容器 Bean——本类对 nivroos-memory 无编译期依赖，memory 侧工具以普通 Bean 参与扫描。
 */
@Configuration(proxyBeanMethods = false)
public class ToolConfiguration {

  /** shell 超时缺省值（技术方案未规定取值；颗粒度文档 §6 已决：30 秒）。 */
  private static final int DEFAULT_SHELL_TIMEOUT_SECONDS = 30;

  /** MCP server 配置路径：相对当前目录，与 CLI 侧的工作区约定一致（不新增配置键）。 */
  private static final Path MCP_CONFIG_FILE = Path.of(".nivroos", "mcp_servers.yaml");

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring Environment 是容器自有对象，装配类持有引用是 Spring 惯用法；非安全边界")
  private final ConfigurableEnvironment environment;

  public ToolConfiguration(ConfigurableEnvironment environment) {
    this.environment = environment;
  }

  /**
   * 白名单沙箱：三组白名单在装配期从配置一次性绑好（宪法原则六）。
   *
   * <p>Whitelists are bound once at assembly time; an empty list means "deny everything" rather
   * than "no check" (FR-005), which {@link WhitelistSandbox} enforces and this method merely feeds.
   */
  @Bean
  Sandbox sandbox() {
    Binder binder = Binder.get(environment);
    return new WhitelistSandbox(
        bindList(binder, "file.allowed-paths"),
        bindList(binder, "shell.allowed-commands"),
        bindList(binder, "http.allowed-domains"));
  }

  /**
   * 文件工具：读 / 写 / 列三件（FR-001）。
   *
   * <p>Built from the shared sandbox so every tier sees the same whitelists, and declared as a bean
   * so the registry picks its {@code @Tool} methods up by the same scan as third-party tools.
   */
  @Bean
  FileTools fileTools(Sandbox sandbox) {
    return new FileTools(sandbox);
  }

  /**
   * 命令工具：超时取自 {@link #shellTimeout()}（配置值在此唯一绑定）。
   *
   * <p>The timeout is read here rather than inside the tool so the tool stays constructible with
   * any Duration in tests, while the configured value is bound exactly once at assembly time.
   */
  @Bean
  ShellTools shellTools(Sandbox sandbox) {
    return new ShellTools(sandbox, shellTimeout());
  }

  /**
   * HTTP 工具：GET / POST 两件（FR-001）。
   *
   * <p>The HttpClient is built here rather than exposed as a bean: only these tools need one, and a
   * context-wide bean would invent a concept the design does not have (same call as US-2's chat
   * command made). Tests construct the class directly with a mock client.
   */
  @Bean
  HttpTools httpTools(Sandbox sandbox) {
    return new HttpTools(sandbox, HttpClient.newHttpClient());
  }

  /**
   * 通知工具：按名解析全局渠道注册表，经 webhook 适配器出站（FR-001 / FR-006）。
   *
   * <p>The store comes from nivroos-storage as a core interface, and the adapter owns the whitelist
   * check before dispatch (contract §4), so this bean only wires the three collaborators together.
   */
  @Bean
  NotifyTools notifyTools(
      Sandbox sandbox, NotifyChannelStore channelStore, NotifyChannelAdapter notifyChannelAdapter) {
    return new NotifyTools(sandbox, channelStore, notifyChannelAdapter);
  }

  /**
   * 通知适配器：核心阶段唯一实现 Webhook（FR-007）。
   *
   * <p>Configured here rather than exposed as an HttpClient bean, same reasoning as {@code
   * httpTools}: only these tools need a client, and tests construct the adapter with a mock one.
   */
  @Bean
  NotifyChannelAdapter notifyChannelAdapter(Sandbox sandbox) {
    return new WebhookNotifyAdapter(sandbox, HttpClient.newHttpClient());
  }

  /**
   * 工具注册表：扫容器单例，{@code @Tool} Bean 与内置工具走同一条注册路径（FR-002 / FR-004）。
   *
   * <p>Container beans are handed to {@link ToolRegistry#scanAnnotated} wholesale - the registry
   * filters for beans that actually carry {@code @Tool} methods, so this class needs no knowledge
   * of which module declared them (that is what keeps the memory module out of this one's compile
   * path).
   */
  @Bean
  ToolRegistry toolRegistry(ApplicationContext applicationContext) {
    ToolRegistry registry = new ToolRegistry();
    registry.scanAnnotated(applicationContext.getBeansOfType(Object.class).values().toArray());
    return registry;
  }

  /**
   * MCP Client：启动时连上配置的 server 并注册其工具（FR-003）。
   *
   * <p>Lifecycle rides on the bean's init/destroy methods rather than {@code @PostConstruct}: the
   * MCP clients own subprocesses, so the container must reap them on shutdown. 单个 server 失联只 WARN
   * 跳过（{@link McpClientService#start()}），装配阶段不因外部依赖失败而失败。
   */
  @Bean(initMethod = "start", destroyMethod = "close")
  McpClientService mcpClientService(ToolRegistry toolRegistry) {
    return new McpClientService(McpServerConfig.load(MCP_CONFIG_FILE), toolRegistry);
  }

  /**
   * Shell 工具超时（{@code shell.timeout-seconds}，缺省 30 秒）。
   *
   * <p>Resolved here, not inside the tool: the tool stays a plain object that unit tests can build
   * with any Duration, while the configured value is bound exactly once at assembly time.
   */
  Duration shellTimeout() {
    return Duration.ofSeconds(
        Binder.get(environment)
            .bind("shell.timeout-seconds", Integer.class)
            .orElse(DEFAULT_SHELL_TIMEOUT_SECONDS));
  }

  /**
   * YAML 列表只能经 Binder 读（{@code Environment.getProperty(key, List.class)} 返回 null）。
   *
   * <p>Measured 2026-08-31: index-keyed YAML lists are invisible to {@code getProperty}, so a
   * whitelist read that way silently becomes empty - i.e. "deny everything".
   */
  private static List<String> bindList(Binder binder, String key) {
    return binder.bind(key, Bindable.listOf(String.class)).orElse(List.of());
  }
}
