package com.nivroos.memory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 记忆后端配置（契约 contracts/memory-config.md）。
 *
 * <p>Binds the {@code memory.*} section. {@code backend} picks one of three storage forms and
 * {@code archive-max-chars} caps the ARCHIVAL section only - the CORE section is never truncated
 * (FR-003 / FR-004). The Mem0 credential follows the same double-channel rule as US-1: an {@code
 * ${ENV_VAR}} placeholder or the local secrets file, plaintext rejected.
 */
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

  /** 合法后端取值（contracts/memory-config.md）。 */
  public static final Set<String> SUPPORTED_BACKENDS = Set.of("markdown", "sqlite", "mem0");

  /** 归档区截断阈值的文档默认值（FR-004）；store 的便捷构造与配置绑定共用此单一来源。 */
  public static final int DEFAULT_ARCHIVE_MAX_CHARS = 4000;

  /** 后端选择；绑定为字符串而非枚举，以便非法取值由 {@link #validate} 给出「列出合法取值」的报错。 */
  private String backend = "markdown";

  private int archiveMaxChars = DEFAULT_ARCHIVE_MAX_CHARS;

  private Mem0 mem0 = new Mem0();

  public String getBackend() {
    return backend;
  }

  public void setBackend(String backend) {
    this.backend = backend;
  }

  public int getArchiveMaxChars() {
    return archiveMaxChars;
  }

  public void setArchiveMaxChars(int archiveMaxChars) {
    this.archiveMaxChars = archiveMaxChars;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Spring Boot @ConfigurationProperties binding requires a mutable bean; the getter/setter pair IS the binding mechanism")
  public Mem0 getMem0() {
    return mem0;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Spring Boot @ConfigurationProperties binding requires a mutable bean; the getter/setter pair IS the binding mechanism")
  public void setMem0(Mem0 mem0) {
    this.mem0 = mem0;
  }

  /**
   * 装配期校验（非法配置直接拒绝启动，不静默失败）。
   *
   * <p>Rejects unknown backends (listing the valid values), non-positive truncation thresholds, and
   * a Mem0 backend missing its address or credential. The credential check mirrors US-1: plaintext
   * is detected on the RAW config value because the bound value has already been resolved by Spring
   * and would be indistinguishable from plaintext; an unresolved placeholder means the environment
   * variable is missing (the Boot 3.5 binder leaves it literal instead of failing).
   */
  public void validate(ConfigurableEnvironment environment) {
    if (backend == null || !SUPPORTED_BACKENDS.contains(backend)) {
      throw new IllegalStateException(
          "Invalid config: memory.backend=" + backend + " (supported: " + SUPPORTED_BACKENDS + ")");
    }
    if (archiveMaxChars <= 0) {
      throw new IllegalStateException(
          "Invalid config: memory.archive-max-chars must be positive, got " + archiveMaxChars);
    }
    if ("mem0".equals(backend)) {
      validateMem0(environment);
    }
  }

  private void validateMem0(ConfigurableEnvironment environment) {
    if (mem0 == null || mem0.getUrl() == null || mem0.getUrl().isBlank()) {
      throw new IllegalStateException(
          "Missing required config: memory.mem0.url (required by memory.backend=mem0)");
    }
    String keyPath = "memory.mem0.api-key";
    if (mem0.getApiKey() == null || mem0.getApiKey().isBlank()) {
      throw new IllegalStateException("Missing required config: " + keyPath);
    }
    // 检查一：明文拒绝——按原始配置值判断（绑定值已被 Spring 解析，无法区分来源）
    // 例外：来自本地密钥文件 nivroos-secrets.yml 的明文值放行（FR-016 双通道）
    String raw = findRawValue(environment, keyPath);
    if (raw != null && !raw.startsWith("${") && !fromSecretsFile(environment, keyPath)) {
      throw new IllegalStateException(
          "api-key must be an ${ENV_VAR} placeholder, plaintext is forbidden: " + keyPath);
    }
    // 检查二：绑定值仍是 ${ENV} 字面量 = 环境变量未设置，报错指明缺失项
    String apiKey = mem0.getApiKey();
    if (apiKey.startsWith("${") && apiKey.endsWith("}")) {
      String envName = apiKey.substring(2, apiKey.length() - 1);
      if (System.getenv(envName) == null) {
        throw new IllegalStateException(
            "Environment variable not set: " + envName + " (required by " + keyPath + ")");
      }
    }
  }

  /** 在属性源里查找键的原始（未解析）值；找不到返回 null。 */
  private static String findRawValue(ConfigurableEnvironment environment, String key) {
    for (org.springframework.core.env.PropertySource<?> source : environment.getPropertySources()) {
      Object value = source.getProperty(key);
      if (value != null) {
        return String.valueOf(value);
      }
    }
    return null;
  }

  /** 键的值是否来自本地密钥文件（FR-016 双通道之二）。 */
  private static boolean fromSecretsFile(ConfigurableEnvironment environment, String key) {
    for (org.springframework.core.env.PropertySource<?> source : environment.getPropertySources()) {
      if (source.getProperty(key) != null && source.getName().contains("nivroos-secrets.yml")) {
        return true;
      }
    }
    return false;
  }

  /** Mem0 自托管服务配置（仅 backend=mem0 时需要）。 */
  public static class Mem0 {

    private String url;
    private String apiKey;

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }
  }
}
