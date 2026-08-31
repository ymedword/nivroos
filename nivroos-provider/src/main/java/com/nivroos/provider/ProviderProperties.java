package com.nivroos.provider;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 供应商全局配置（契约 contracts/provider-config.md）。
 *
 * <p>Binds the {@code nivroos.providers.*} section; api-key must be an ${ENV_VAR} placeholder -
 * plaintext is rejected by {@link #validate()}.
 */
@ConfigurationProperties(prefix = "nivroos")
public class ProviderProperties {

  /** name（YAML 键）→ 供应商配置；重复键由 SnakeYAML 解析层拒绝（FR-009）。 */
  private Map<String, ProviderConfig> providers = new HashMap<>();

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Spring Boot @ConfigurationProperties binding requires a mutable bean; the getter/setter pair IS the binding mechanism")
  public Map<String, ProviderConfig> getProviders() {
    return providers;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Spring Boot @ConfigurationProperties binding requires a mutable bean; the getter/setter pair IS the binding mechanism")
  public void setProviders(Map<String, ProviderConfig> providers) {
    this.providers = providers;
  }

  /**
   * 装配期校验（非法配置直接拒绝启动，不静默失败）。
   *
   * <p>Startup validation; throws with the exact config key path on violation. Unresolved
   * ${ENV_VAR} placeholders are also rejected here because the config binder may leave them literal
   * instead of failing (Boot 3.5 behavior, verified empirically).
   */
  public void validate() {
    providers.forEach(
        (name, config) -> {
          String keyPath = "nivroos.providers." + name + ".api-key";
          if (config == null || config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("Missing required config: " + keyPath);
          }
          String apiKey = config.getApiKey();
          if (!apiKey.startsWith("${")) {
            throw new IllegalStateException(
                "api-key must be an ${ENV_VAR} placeholder, plaintext is forbidden: " + keyPath);
          }
          if (apiKey.endsWith("}")) {
            // 绑定层未解析成功的占位符保持字面量：环境变量未设置必须报错指明缺失项
            String envName = apiKey.substring(2, apiKey.length() - 1);
            if (System.getenv(envName) == null) {
              throw new IllegalStateException(
                  "Environment variable not set: " + envName + " (required by " + keyPath + ")");
            }
          }
        });
  }

  /** 单个供应商配置（name = 外层 map 键）。 */
  public static class ProviderConfig {

    private String apiKey;
    private String baseUrl;

    public ProviderConfig() {}

    public ProviderConfig(String apiKey, String baseUrl) {
      this.apiKey = apiKey;
      this.baseUrl = baseUrl;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }
  }
}
