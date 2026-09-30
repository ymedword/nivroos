package com.nivroos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nivroos.boot.example.ExampleEchoTools;
import com.nivroos.core.loader.AgentLoader;
import com.nivroos.core.profile.ProfileRegistry;
import com.nivroos.core.provider.ProviderService;
import com.nivroos.tool.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** 启动扫描与跨模块校验验收点：颗粒度文档 §4.2（FR-019 单 Agent 失败只 WARN 不阻断）。 */
class ProfileConfigurationTest {

  @TempDir Path workspaceRoot;

  @Test
  @DisplayName("合法 Agent 全部注册：provider 已注册 / tool 已注册 / bootstrap 文件存在")
  void scanAndValidate_validAgent_registered() throws IOException {
    writeAgent("weather", "deepseek", "example_echo", "AGENTS.md", null);
    Files.writeString(workspaceRoot.resolve("AGENTS.md"), "项目级行为说明", StandardCharsets.UTF_8);

    ProfileRegistry registry = scan();

    assertThat(registry.get("weather")).isNotNull();
  }

  @Test
  @DisplayName("非法 Agent 只 WARN 跳过、不阻断其它：provider / tool / bootstrap / mcp 逐项报出")
  void scanAndValidate_invalidAgent_skippedWithWarnings() throws IOException {
    writeAgent("broken", "unregistered", "no_such_tool", "MISSING.md", "no-such-mcp");
    writeAgent("ok", "deepseek", "example_echo", null, null);
    Files.writeString(workspaceRoot.resolve("AGENTS.md"), "项目级行为说明", StandardCharsets.UTF_8);

    ProfileRegistry registry;
    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      registry = scan();
    } finally {
      detachAppender(appender);
    }

    assertThat(registry.get("broken")).isNull();
    assertThat(registry.get("ok")).isNotNull();
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("broken")
                  .contains("provider not registered: unregistered")
                  .contains("tool not registered: no_such_tool")
                  .contains("bootstrap file missing: MISSING.md")
                  .contains("mcp server not configured: no-such-mcp");
            });
  }

  @Test
  @DisplayName("已配置的 MCP server 名通过校验；同名引用不报问题")
  void scanAndValidate_configuredMcpServer_accepted() throws IOException {
    writeAgent("ops", "deepseek", "example_echo", null, "github-mcp");

    ProfileRegistry registry =
        ProfileConfiguration.scanAndValidate(
            new AgentLoader(workspaceRoot.resolve("agents")),
            providerService(),
            toolRegistry(),
            workspaceRoot,
            Set.of("github-mcp"));

    assertThat(registry.get("ops")).isNotNull();
  }

  @Test
  @DisplayName("agents 目录不存在：扫描为空，不抛异常")
  void scanAndValidate_missingAgentsRoot_returnsEmpty() {
    ProfileRegistry registry =
        ProfileConfiguration.scanAndValidate(
            new AgentLoader(workspaceRoot.resolve("agents")),
            providerService(),
            toolRegistry(),
            workspaceRoot,
            Set.of());

    assertThat(registry.get("anything")).isNull();
  }

  // ------------------------------------------------ 测试内助手

  private ProfileRegistry scan() {
    return ProfileConfiguration.scanAndValidate(
        new AgentLoader(workspaceRoot.resolve("agents")),
        providerService(),
        toolRegistry(),
        workspaceRoot,
        Set.of());
  }

  private static ProviderService providerService() {
    ProviderService providerService = mock(ProviderService.class);
    when(providerService.providerNames()).thenReturn(Set.of("deepseek"));
    return providerService;
  }

  private static ToolRegistry toolRegistry() {
    ToolRegistry toolRegistry = new ToolRegistry();
    toolRegistry.scanAnnotated(new ExampleEchoTools());
    return toolRegistry;
  }

  private void writeAgent(
      String name, String provider, String tool, String bootstrap, String mcpServer)
      throws IOException {
    Path dir = workspaceRoot.resolve("agents").resolve(name);
    Files.createDirectories(dir);
    StringBuilder frontmatter = new StringBuilder("---\nname: " + name + "\nprovider:\n  name: ");
    frontmatter.append(provider).append('\n');
    frontmatter.append("tools:\n  - ").append(tool).append('\n');
    if (bootstrap != null) {
      frontmatter.append("bootstrap:\n  - ").append(bootstrap).append('\n');
    }
    if (mcpServer != null) {
      frontmatter.append("mcp_servers:\n  - ").append(mcpServer).append('\n');
    }
    frontmatter.append("---\n你是").append(name).append("助手。\n");
    Files.writeString(dir.resolve("AGENT.md"), frontmatter.toString(), StandardCharsets.UTF_8);
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ProfileConfiguration.class)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(ProfileConfiguration.class)).detachAppender(appender);
  }
}
