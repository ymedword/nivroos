package com.nivroos.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * MCP server 配置（技术方案 §6.4；契约 contracts/mcp-and-notify.md §1）。
 *
 * <p>One entry of {@code .nivroos/mcp_servers.yaml}: the transport kind, the command line to spawn
 * and the (already resolved) environment of the subprocess. {@link #load} is the single parse point
 * - a missing file simply means "no MCP configured", while a plaintext env value or an unset {@code
 * ${ENV_VAR}} fails loudly instead of silently shipping an empty credential.
 *
 * <p>{@code env} 解析在本地实现而不是复用 CLI 的 {@code ConfigLoader.resolveEnv}：规则一致但 不得让 {@code nivroos-tool}
 * 反向依赖 {@code nivroos-cli}（契约 §1 凭证纪律）。与 CLI 的差异是 这里把明文也当错误——MCP 凭证只允许占位，CLI 侧放行明文是因为它的值可能本就来自
 * secrets 文件。
 *
 * <p>缓存语义：本类只在启动连接时读一次，{@code mcp_servers.yaml} 改动需重启（契约 §2）。
 *
 * @param name server 名（日志与 {@code McpToolAdapter} 的归属标识）
 * @param transport 传输类型；核心阶段只支持 {@code stdio}
 * @param command 完整命令行，按空白拆出可执行文件与 args
 * @param env 子进程环境变量（占位已解析为真实值）
 */
public record McpServerConfig(
    String name, String transport, String command, Map<String, String> env) {

  private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

  private static final Yaml YAML = new Yaml();

  /** 核心阶段唯一支持的传输（技术方案 §6.4：SSE / Streamable HTTP 放扩展阶段）。 */
  private static final String TRANSPORT_STDIO = "stdio";

  public McpServerConfig {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("MCP server config: missing 'name'");
    }
    if (command == null || command.isBlank()) {
      // 没有可执行文件就没有子进程，早报比让 SDK 抛空异常清楚
      throw new IllegalArgumentException("MCP server '" + name + "': missing 'command'");
    }
    env = env == null ? Map.of() : Map.copyOf(env);
  }

  /**
   * 解析 {@code .nivroos/mcp_servers.yaml}（契约 §1 规则表）。
   *
   * <p>Failure semantics are deliberately asymmetric: "no file / no servers key" is normal (an
   * empty {@code nivroos init} template is exactly that shape), a non-stdio entry is skipped with a
   * WARN, and everything that would silently weaken credentials or the config shape throws.
   *
   * @param configFile 配置文件路径
   * @return 可用的 stdio server 配置（按文件顺序）；未配置时为空列表
   */
  public static List<McpServerConfig> load(Path configFile) {
    if (!Files.isRegularFile(configFile)) {
      return List.of();
    }
    Object loaded;
    try {
      loaded = YAML.load(Files.readString(configFile, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read MCP config: " + configFile, e);
    } catch (YAMLException e) {
      throw new IllegalStateException("Invalid YAML in MCP config: " + configFile, e);
    }
    if (loaded == null) {
      // 只有注释的文件（init 生成的模板在配 server 之前）不是错误
      return List.of();
    }
    if (!(loaded instanceof Map<?, ?> root)) {
      throw new IllegalStateException("MCP config must be a YAML map: " + configFile);
    }
    if (!(root.get("servers") instanceof List<?> servers)) {
      return List.of();
    }

    List<McpServerConfig> configs = new ArrayList<>();
    for (Object entry : servers) {
      if (!(entry instanceof Map<?, ?> server)) {
        throw new IllegalStateException(
            "MCP config: every entry under 'servers' must be a map: " + configFile);
      }
      McpServerConfig config = fromMap(server);
      if (!TRANSPORT_STDIO.equals(config.transport())) {
        log.warn(
            "mcp server skipped, only stdio transport is supported in the core phase: name={}, transport={}",
            sanitizeForLog(config.name()),
            sanitizeForLog(config.transport()));
        continue;
      }
      configs.add(config);
    }
    return configs;
  }

  /** 子进程可执行文件：命令行首个 token。 */
  public String executable() {
    return tokens()[0];
  }

  /** 子进程参数：命令行首 token 之后的全部（无则为空列表）。 */
  public List<String> args() {
    String[] tokens = tokens();
    return tokens.length > 1 ? List.of(Arrays.copyOfRange(tokens, 1, tokens.length)) : List.of();
  }

  private String[] tokens() {
    return command.strip().split("\\s+");
  }

  private static McpServerConfig fromMap(Map<?, ?> server) {
    String name = text(server.get("name"));
    return new McpServerConfig(
        name,
        text(server.get("transport")),
        text(server.get("command")),
        resolveEnv(server.get("env"), name));
  }

  private static Map<String, String> resolveEnv(Object raw, String serverName) {
    if (!(raw instanceof Map<?, ?> envMap)) {
      return Map.of();
    }
    Map<String, String> resolved = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : envMap.entrySet()) {
      String key = String.valueOf(entry.getKey());
      resolved.put(key, resolveEnvValue(text(entry.getValue()), serverName + ".env." + key));
    }
    return resolved;
  }

  /**
   * 单个 env 值必须是可解析的 {@code ${ENV_VAR}} 占位（FR-011）。
   *
   * <p>Plaintext is rejected rather than passed through: the file lives in the workspace and may be
   * committed, so a literal token there is a leak waiting to happen（契约 §1 凭证纪律）。
   */
  private static String resolveEnvValue(String value, String keyPath) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required config: " + keyPath);
    }
    if (!value.startsWith("${")) {
      throw new IllegalStateException(
          "MCP env value must be a ${ENV_VAR} placeholder, plaintext is rejected: " + keyPath);
    }
    if (!value.endsWith("}")) {
      throw new IllegalStateException("Invalid ${ENV_VAR} placeholder: " + keyPath);
    }
    String envName = value.substring(2, value.length() - 1);
    String resolved = System.getenv(envName);
    if (resolved == null || resolved.isBlank()) {
      throw new IllegalStateException(
          "Environment variable not set: " + envName + " (required by " + keyPath + ")");
    }
    return resolved;
  }

  private static String text(Object raw) {
    return raw == null ? null : String.valueOf(raw);
  }

  /** 日志参数 CRLF 消毒：字段来自 mcp_servers.yaml，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
