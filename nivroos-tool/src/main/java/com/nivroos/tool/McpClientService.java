package com.nivroos.tool;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP Client（技术方案 §6.4；契约 contracts/mcp-and-notify.md §2）。
 *
 * <p>Connects every configured stdio server at startup, pulls its tool list and registers the tools
 * in the {@link ToolRegistry}. 单个 server 失联只 WARN 跳过、不阻断启动（FR-003）：一个配错的 server 不能让整个底座起不来，也不能拖累其它
 * server 的工具。工具列表在启动连接时拉取并常驻内存 ——{@code mcp_servers.yaml} 改动需重启（缓存语义，契约 §2）。
 *
 * <p>同步模型：一律 {@link McpSyncClient}，源码不出现 Reactor 类型（宪法原则七）。
 */
public class McpClientService {

  private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

  /**
   * 调用超时（颗粒度文档「待决事项」默认建议 30s，不新增配置键）。
   *
   * <p>Without it a wedged server would hang a tool call forever; per-server configurability needs
   * a new config key and therefore a stop-and-report first.
   */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final List<McpServerConfig> servers;
  private final ToolRegistry registry;
  private final McpClientFactory clientFactory;
  private final List<McpSyncClient> clients = new ArrayList<>();

  public McpClientService(List<McpServerConfig> servers, ToolRegistry registry) {
    this(servers, registry, transportFactoryForStdio());
  }

  /**
   * 生产路径的可替换传输工厂（契约 §2 的包内可见测试构造）。
   *
   * <p>Tests cannot drive a real {@code initialize()} / {@code tools/list} through a dummy
   * transport (that would require implementing the SDK's reactor-based protocol inside a unit
   * test), so the protocol-level seam used by tests is {@link #withClientFactory} - 本构造仍被测试覆盖：用它装配
   * 真实 SDK 客户端，验证失联容错。
   */
  McpClientService(
      List<McpServerConfig> servers,
      ToolRegistry registry,
      Function<McpServerConfig, McpClientTransport> transportFactory) {
    // 显式转型消歧：lambda 同时适配两个函数式形参类型
    this(
        servers, registry, (McpClientFactory) config -> syncClient(transportFactory.apply(config)));
  }

  private McpClientService(
      List<McpServerConfig> servers, ToolRegistry registry, McpClientFactory clientFactory) {
    this.servers = List.copyOf(servers);
    this.registry = registry;
    this.clientFactory = clientFactory;
  }

  /**
   * 测试接缝：直接注入 {@link McpSyncClient}（协议层 mock）。
   *
   * <p>Named factory instead of an overloaded constructor so the two seams stay unambiguous - a
   * lambda argument would fit both functional parameter types.
   */
  static McpClientService withClientFactory(
      List<McpServerConfig> servers, ToolRegistry registry, McpClientFactory clientFactory) {
    return new McpClientService(servers, registry, clientFactory);
  }

  /** 连上全部 server 并注册其工具；单个失败只 WARN 跳过（FR-003）。 */
  public void start() {
    for (McpServerConfig config : servers) {
      try {
        connect(config);
      } catch (RuntimeException e) {
        log.warn(
            "mcp server skipped, connect/initialize/list failed: name={}, reason={}",
            sanitizeForLog(config.name()),
            sanitizeForLog(e.getMessage()));
      }
    }
  }

  /** 关停全部已连接 server：优雅关停失败降级强关，不吞异常。 */
  public void close() {
    for (McpSyncClient client : clients) {
      shutdown(client);
    }
    clients.clear();
  }

  private void connect(McpServerConfig config) {
    McpSyncClient client = clientFactory.create(config);
    client.initialize();
    int registered = registerTools(config, client);
    clients.add(client);
    log.info("mcp server connected: name={}, tools={}", sanitizeForLog(config.name()), registered);
  }

  /**
   * {@code tools/list} 翻页取尽并逐个注册（游标为空即停）。
   *
   * <p>Every page's tools are registered against the same client, so a tool call later goes back to
   * the server that advertised it.
   */
  private int registerTools(McpServerConfig config, McpSyncClient client) {
    int count = 0;
    String cursor = null;
    do {
      McpSchema.ListToolsResult page =
          cursor == null ? client.listTools() : client.listTools(cursor);
      for (McpSchema.Tool tool : page.tools()) {
        registry.register(new McpToolAdapter(config.name(), tool, client));
        count++;
      }
      cursor = page.nextCursor();
    } while (cursor != null && !cursor.isBlank());
    return count;
  }

  private static void shutdown(McpSyncClient client) {
    try {
      if (client.closeGracefully()) {
        return;
      }
      log.warn("mcp client did not shut down gracefully within the timeout, forcing close");
    } catch (RuntimeException e) {
      log.warn(
          "mcp client graceful shutdown failed, forcing close: reason={}",
          sanitizeForLog(e.getMessage()));
    }
    try {
      client.close();
    } catch (RuntimeException e) {
      log.warn("mcp client shutdown failed: reason={}", sanitizeForLog(e.getMessage()));
    }
  }

  private static Function<McpServerConfig, McpClientTransport> transportFactoryForStdio() {
    return config -> {
      ServerParameters parameters =
          ServerParameters.builder(config.executable())
              .args(config.args())
              .env(config.env())
              .build();
      return new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
    };
  }

  private static McpSyncClient syncClient(McpClientTransport transport) {
    return McpClient.sync(transport).requestTimeout(REQUEST_TIMEOUT).build();
  }

  /** 日志参数 CRLF 消毒（findsecbugs；上游异常文案可能带换行）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }

  /**
   * 单测接缝：由 server 配置造一个已连好的 {@link McpSyncClient}。
   *
   * <p>Test seam for the protocol boundary; production always uses {@link
   * #transportFactoryForStdio}.
   */
  @FunctionalInterface
  interface McpClientFactory {

    /** 为一个 server 造客户端（实现方决定连接与否；真实路径见 {@link #syncClient}）。 */
    McpSyncClient create(McpServerConfig config);
  }
}
