package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** McpClientService 验收点：颗粒度文档 §4.2（配置解析 / 翻页注册 / 失联容错 / 超时值）。 */
class McpClientServiceTest {

  @TempDir Path tempDir;

  // ------------------------------------------------ mcp_servers.yaml 解析（契约 §1）

  @Test
  @DisplayName("配置文件不存在 → 视为无 MCP，正常启动（不报错）")
  void load_missingFile_treatedAsNoServers() {
    assertThat(McpServerConfig.load(tempDir.resolve("mcp_servers.yaml"))).isEmpty();
  }

  @Test
  @DisplayName("init 生成的模板（只有注释）→ 视为无 MCP（配 server 之前不是错误）")
  void load_commentOnlyTemplate_treatedAsNoServers() throws IOException {
    Path config = tempDir.resolve("mcp_servers.yaml");
    Files.writeString(config, "# MCP server 配置（name/transport/command/env）\n");

    assertThat(McpServerConfig.load(config)).isEmpty();
  }

  @Test
  @DisplayName("解析 name / transport / command：首 token 为可执行文件、其余为 args，env 占位解析成真实值")
  void load_parsesCommandSplitAndResolvedEnv() throws IOException {
    Path config = tempDir.resolve("mcp_servers.yaml");
    Files.writeString(
        config,
        """
        servers:
          - name: github-mcp
            transport: stdio
            command: "npx -y @modelcontextprotocol/server-github"
            env:
              PATH: ${PATH}
        """);

    List<McpServerConfig> servers = McpServerConfig.load(config);

    assertThat(servers).hasSize(1);
    McpServerConfig server = servers.get(0);
    assertThat(server.name()).isEqualTo("github-mcp");
    assertThat(server.transport()).isEqualTo("stdio");
    assertThat(server.executable()).isEqualTo("npx");
    assertThat(server.args()).containsExactly("-y", "@modelcontextprotocol/server-github");
    assertThat(server.env()).containsKey("PATH");
    assertThat(server.env().get("PATH")).isNotBlank().doesNotContain("${");
  }

  @Test
  @DisplayName("transport 非 stdio → WARN 跳过该 server，其余照常（核心阶段只做 stdio）")
  void load_nonStdioTransport_warnsAndSkips() throws IOException {
    Path config = tempDir.resolve("mcp_servers.yaml");
    Files.writeString(
        config,
        """
        servers:
          - name: remote-mcp
            transport: sse
            command: "npx some-remote-server"
          - name: local-mcp
            transport: stdio
            command: "npx local-server"
        """);

    ListAppender<ILoggingEvent> appender = attachAppender(McpServerConfig.class);
    List<McpServerConfig> servers;
    try {
      servers = McpServerConfig.load(config);
    } finally {
      detachAppender(McpServerConfig.class, appender);
    }

    assertThat(servers).extracting(McpServerConfig::name).containsExactly("local-mcp");
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("remote-mcp").contains("sse");
            });
  }

  @Test
  @DisplayName("env 明文（非 ${ENV_VAR} 占位）→ 大声报错拒绝，不放行")
  void load_plaintextEnvValue_rejected() throws IOException {
    Path config = tempDir.resolve("mcp_servers.yaml");
    Files.writeString(
        config,
        """
        servers:
          - name: github-mcp
            transport: stdio
            command: "npx server-github"
            env:
              GITHUB_TOKEN: ghp_plaintext_leak
        """);

    assertThatThrownBy(() -> McpServerConfig.load(config))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GITHUB_TOKEN")
        .hasMessageContaining("placeholder");
  }

  @Test
  @DisplayName("env 占位符指向的环境变量缺失 → 大声报错（指明缺失项）")
  void load_unresolvedPlaceholder_rejected() throws IOException {
    Path config = tempDir.resolve("mcp_servers.yaml");
    Files.writeString(
        config,
        """
        servers:
          - name: github-mcp
            transport: stdio
            command: "npx server-github"
            env:
              GITHUB_TOKEN: ${NIVROOS_TEST_ABSENT_VAR}
        """);

    assertThatThrownBy(() -> McpServerConfig.load(config))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NIVROOS_TEST_ABSENT_VAR");
  }

  // ------------------------------------------------ start / close（契约 §2）

  @Test
  @DisplayName("tools/list 翻页取尽：两页工具全部注册，游标续取")
  void start_registersEveryToolFromAllPages() {
    ToolRegistry registry = new ToolRegistry();
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.listTools())
        .thenReturn(new McpSchema.ListToolsResult(List.of(tool("search_prs")), "cursor-2"));
    when(client.listTools("cursor-2"))
        .thenReturn(new McpSchema.ListToolsResult(List.of(tool("get_issue")), null));

    McpClientService service =
        McpClientService.withClientFactory(
            List.of(configOf("github-mcp")), registry, clients(Map.of("github-mcp", client)));

    service.start();

    assertThat(registry.get("search_prs")).isNotNull();
    assertThat(registry.get("get_issue")).isNotNull();
    verify(client).listTools("cursor-2");
  }

  @Test
  @DisplayName("单个 MCP server 连接失败：WARN 跳过，不阻断启动，其它 server 工具正常注册")
  void start_oneServerUnreachable_othersStillRegistered() {
    ToolRegistry registry = new ToolRegistry();
    McpSyncClient broken = mock(McpSyncClient.class);
    when(broken.initialize()).thenThrow(new IllegalStateException("subprocess exited"));
    McpSyncClient working = mock(McpSyncClient.class);
    when(working.listTools())
        .thenReturn(new McpSchema.ListToolsResult(List.of(tool("ok-tool")), null));

    McpClientService service =
        McpClientService.withClientFactory(
            List.of(configOf("broken"), configOf("ok")),
            registry,
            clients(Map.of("broken", broken, "ok", working)));

    ListAppender<ILoggingEvent> appender = attachAppender(McpClientService.class);
    try {
      assertThatCode(service::start).doesNotThrowAnyException();
    } finally {
      detachAppender(McpClientService.class, appender);
    }

    assertThat(registry.get("ok-tool")).isNotNull();
    assertThat(registry.get("broken-tool")).isNull();
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("broken")
                  .contains("subprocess exited");
            });
  }

  @Test
  @DisplayName("生产构造：SDK 客户端经 transport 工厂装配，握手失败只 WARN 跳过不抛出")
  void start_productionSeam_handshakeFailureSkipped() {
    ToolRegistry registry = new ToolRegistry();
    McpClientService service =
        new McpClientService(
            List.of(configOf("unreachable")),
            registry,
            config -> mock(io.modelcontextprotocol.spec.McpClientTransport.class));

    ListAppender<ILoggingEvent> appender = attachAppender(McpClientService.class);
    try {
      assertThatCode(service::start).doesNotThrowAnyException();
    } finally {
      detachAppender(McpClientService.class, appender);
    }

    assertThat(registry.all()).isEmpty();
    assertThat(appender.list)
        .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
  }

  @Test
  @DisplayName("调用超时取待决事项建议值 30 秒（SDK 无取值接口，此处钉住常量）")
  void requestTimeout_isThirtySeconds() {
    assertThat(McpClientService.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("close：优雅关停失败降级强关，两者都不吞（WARN 留痕）")
  void close_gracefulShutdownFails_fallsBackToForcedClose() {
    ToolRegistry registry = new ToolRegistry();
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.listTools())
        .thenReturn(new McpSchema.ListToolsResult(List.of(tool("ok-tool")), null));
    when(client.closeGracefully()).thenReturn(false);
    McpClientService service =
        McpClientService.withClientFactory(
            List.of(configOf("ok")), registry, clients(Map.of("ok", client)));

    ListAppender<ILoggingEvent> appender = attachAppender(McpClientService.class);
    try {
      service.start();
      service.close();
    } finally {
      detachAppender(McpClientService.class, appender);
    }

    verify(client).close();
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("forcing close");
            });
  }

  // ------------------------------------------------ 测试内助手

  private static McpServerConfig configOf(String name) {
    return new McpServerConfig(name, "stdio", "npx server-" + name, Map.of());
  }

  private static McpClientService.McpClientFactory clients(Map<String, McpSyncClient> byName) {
    return config -> byName.get(config.name());
  }

  private static McpSchema.Tool tool(String name) {
    return McpSchema.Tool.builder()
        .name(name)
        .description("工具 " + name)
        .inputSchema(
            new McpSchema.JsonSchema("object", Map.of(), List.of(), Boolean.FALSE, null, null))
        .build();
  }

  private static ListAppender<ILoggingEvent> attachAppender(Class<?> loggerClass) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(loggerClass)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(loggerClass)).detachAppender(appender);
  }
}
