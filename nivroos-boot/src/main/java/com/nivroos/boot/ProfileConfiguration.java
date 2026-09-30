package com.nivroos.boot;

import com.nivroos.core.loader.AgentLoader;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileRegistry;
import com.nivroos.core.provider.ProviderService;
import com.nivroos.tool.McpClientService;
import com.nivroos.tool.McpServerConfig;
import com.nivroos.tool.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 目录启动扫描与注册（技术方案 §8.2；颗粒度文档 §2.3 装配链路）。
 *
 * <p>Startup scan plus cross-module validation. 校验放在装配层而不是 {@link AgentLoader}：只有这一层 同时看得见 Provider
 * 集合（provider 模块）、工具注册表（tool 模块）与文件系统（颗粒度文档 §2.4 差异裁决注 6）。单个 Agent 校验不过只 WARN 跳过，不阻断其它
 * Agent（FR-019）。
 */
@Configuration(proxyBeanMethods = false)
public class ProfileConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ProfileConfiguration.class);

  /** 工作区路径相对进程工作目录（与 CLI 侧同一约定，不新增配置键）。 */
  private static final Path WORKSPACE_ROOT = Path.of(".nivroos");

  private static final Path AGENTS_ROOT = WORKSPACE_ROOT.resolve("agents");

  private static final Path MCP_CONFIG_FILE = WORKSPACE_ROOT.resolve("mcp_servers.yaml");

  /**
   * 启动扫描全部 Agent 目录并注册（FR-019）。
   *
   * <p>{@code mcpClientService} 只用于表达装配顺序：MCP 工具由它的 {@code initMethod} 注册，Profile 校验工具名必须在 MCP
   * 工具进表之后，否则会误报"工具未注册"。
   */
  @Bean
  ProfileRegistry profileRegistry(
      ProviderService providerService,
      ToolRegistry toolRegistry,
      McpClientService mcpClientService) {
    Objects.requireNonNull(
        mcpClientService, "MCP tools must be registered before profile validation");
    return scanAndValidate(
        new AgentLoader(AGENTS_ROOT),
        providerService,
        toolRegistry,
        WORKSPACE_ROOT,
        configuredMcpServerNames());
  }

  /**
   * 扫描 → 跨模块校验 → 注册；校验不过的 Agent 只 WARN 跳过（FR-019）。
   *
   * <p>Package-visible so a test can drive the whole decision without starting a Spring context.
   * 一次把该 Agent 的全部问题收进一条 WARN，避免同一个 Agent 刷多行日志。
   *
   * @param mcpServerNames 已配置的 MCP server 名（空集 = 一个都没配）
   */
  static ProfileRegistry scanAndValidate(
      AgentLoader agentLoader,
      ProviderService providerService,
      ToolRegistry toolRegistry,
      Path workspaceRoot,
      Set<String> mcpServerNames) {
    ProfileRegistry registry = new ProfileRegistry();
    Set<String> providers = providerService.providerNames();
    int registered = 0;
    for (Profile profile : agentLoader.scan()) {
      List<String> problems =
          problemsOf(profile, providers, toolRegistry, workspaceRoot, mcpServerNames);
      if (!problems.isEmpty()) {
        // problems 里每一条都掺了 AGENT.md 里的字面量：整串消毒后再进日志（防止日志行注入）
        log.warn(
            "agent skipped, cross-module validation failed: name={}, problems={}",
            sanitizeForLog(profile.getName()),
            sanitizeForLog(String.join("; ", problems)));
        continue;
      }
      registry.register(profile);
      registered++;
    }
    log.info("agent profiles registered: count={}", registered);
    return registry;
  }

  /** 逐项校验：provider 已注册 / tool 已注册 / bootstrap 文件存在 / mcp server 已配置。 */
  private static List<String> problemsOf(
      Profile profile,
      Set<String> providers,
      ToolRegistry toolRegistry,
      Path workspaceRoot,
      Set<String> mcpServerNames) {
    List<String> problems = new ArrayList<>();
    if (!providers.contains(profile.getProviderName())) {
      problems.add("provider not registered: " + profile.getProviderName());
    }
    for (String tool : profile.getTools()) {
      if (toolRegistry.get(tool) == null) {
        problems.add("tool not registered: " + tool);
      }
    }
    for (String bootstrap : profile.getBootstrap()) {
      if (!Files.isRegularFile(workspaceRoot.resolve(bootstrap))) {
        problems.add("bootstrap file missing: " + bootstrap);
      }
    }
    for (String server : profile.getMcpServers()) {
      if (!mcpServerNames.contains(server)) {
        problems.add("mcp server not configured: " + server);
      }
    }
    return problems;
  }

  /** 已配置的 MCP server 名；配置不可读时返回空集并 WARN（由 MCP 装配路径负责大声报错）。 */
  private static Set<String> configuredMcpServerNames() {
    try {
      return McpServerConfig.load(MCP_CONFIG_FILE).stream()
          .map(McpServerConfig::name)
          .collect(Collectors.toUnmodifiableSet());
    } catch (RuntimeException e) {
      log.warn(
          "mcp server config unreadable, mcp_servers validation falls back to empty: reason={}",
          sanitizeForLog(e.getMessage()));
      return Set.of();
    }
  }

  /** 日志参数 CRLF 消毒：profile 名与路径来自本地配置，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
