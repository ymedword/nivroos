package com.nivroos.memory;

import com.nivroos.core.memory.LongTermMemoryStore;
import com.nivroos.core.memory.MemoryService;
import com.nivroos.core.session.InMemorySessionManager;
import com.nivroos.core.session.SessionManager;
import java.net.http.HttpClient;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 记忆能力装配：按 {@code memory.backend} 显式选后端（技术方案 §5.1、契约 contracts/memory-config.md）。
 *
 * <p>Wiring point of the pluggable-backend promise: the switch below is the only place that knows
 * which store exists, so switching {@code memory.backend} touches one line and everything above
 * {@link MemoryService} stays untouched. Configuration is validated in the constructor - a broken
 * backend name or credential must fail startup loudly, never degrade silently.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfiguration {

  /** 长期记忆载体：路径由系统固定，不经通用文件工具的路径白名单（FR-015，颗粒度文档 §2.2-5）。 */
  private static final Path MEMORY_FILE = Path.of(".nivroos", "memory", "MEMORY.md");

  public MemoryConfiguration(MemoryProperties properties, ConfigurableEnvironment environment) {
    properties.validate(environment);
  }

  /**
   * 按配置装配长期记忆后端（三档：markdown 默认 / sqlite / mem0）。
   *
   * <p>Explicit switch rather than a bean-name lookup: the backend name in configuration is the
   * single source of truth for provider routing (constitution III applied to storage). The
   * HttpClient is built inside the mem0 arm rather than injected as a bean - only this backend
   * needs one, and a context-wide bean would invent a concept the design does not have.
   */
  @Bean
  public LongTermMemoryStore longTermMemoryStore(
      MemoryProperties properties, ConfigurableEnvironment environment, DataSource dataSource) {
    return switch (properties.getBackend()) {
      case "markdown" -> new MarkdownMemoryStore(MEMORY_FILE, properties.getArchiveMaxChars());
      case "sqlite" -> new SqliteMemoryStore(dataSource, properties.getArchiveMaxChars());
      case "mem0" ->
          new Mem0MemoryStore(
              HttpClient.newHttpClient(),
              properties.getMem0().getUrl(),
              resolveApiKey(properties, environment),
              properties.getArchiveMaxChars());
      default ->
          throw new IllegalStateException("Unsupported memory.backend: " + properties.getBackend());
    };
  }

  /**
   * 解析 mem0 凭证：Boot 3.5 绑定器对 {@code ${ENV_VAR}} 保持字面量，装配前必须显式解析。
   *
   * <p>The Boot 3.5 binder leaves placeholders literal (measured 2026-08-31, see the CLAUDE.md
   * pitfalls table), so the raw bound value has to be resolved before it can be sent as a header.
   */
  private static String resolveApiKey(
      MemoryProperties properties, ConfigurableEnvironment environment) {
    return environment.resolvePlaceholders(properties.getMem0().getApiKey());
  }

  /**
   * 记忆统一门面：ReAct 链路（PromptBuilder / MemoryTools）只见此对象。
   *
   * <p>The facade needs the session manager to read history; keeping one shared instance means the
   * history a channel writes and the history the facade reads are the same list.
   */
  @Bean
  public MemoryService memoryService(SessionManager sessionManager, LongTermMemoryStore store) {
    return new MemoryService(sessionManager, store);
  }

  /**
   * 记忆工具：改为容器 Bean，供 ToolRegistry 扫描注册（US-4 前序改造点 2）。
   *
   * <p>Registering it as a bean is what lets the registry's container scan pick the two memory
   * tools up without the tool module depending on this one; before US-4 every entry point built the
   * tool objects by hand, which meant memory tools were invisible to the registry.
   */
  @Bean
  public MemoryTools memoryTools(MemoryService memoryService) {
    return new MemoryTools(memoryService);
  }

  /** 会话管理器：US-2 的通道各自 new 一个，多入口会读到不同历史——统一出 Bean 消除该分叉。 */
  @Bean
  public SessionManager sessionManager() {
    return new InMemorySessionManager();
  }
}
