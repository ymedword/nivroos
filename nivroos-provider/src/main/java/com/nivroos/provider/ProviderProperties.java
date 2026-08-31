package com.nivroos.provider;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.ConfigurableEnvironment;

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
   * <p>Two distinct checks: (1) plaintext is detected on the RAW config value from the property
   * sources - the bound value cannot be used because Spring resolves ${ENV_VAR} placeholders into
   * real keys, which would be indistinguishable from plaintext; (2) an unresolved placeholder means
   * the environment variable is missing (the Boot 3.5 binder leaves it literal instead of failing,
   * verified empirically) - reject with the exact variable name.
   */
  public void validate(ConfigurableEnvironment environment) {
    providers.forEach(
        (name, config) -> {
          String keyPath = "nivroos.providers." + name + ".api-key";
          if (config == null || config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("Missing required config: " + keyPath);
          }
          // 检查一：明文拒绝——按原始配置值判断（绑定值已被 Spring 解析，无法区分来源）
          // 例外：来自本地密钥文件 nivroos-secrets.yml 的明文值放行（FR-003 双通道之二）
          String raw = findRawValue(environment, keyPath);
          if (raw != null && !raw.startsWith("${") && !fromSecretsFile(environment, keyPath)) {
            throw new IllegalStateException(
                "api-key must be an ${ENV_VAR} placeholder, plaintext is forbidden: " + keyPath);
          }
          // 检查二：绑定值仍是 ${ENV} 字面量 = 环境变量未设置，报错指明缺失项
          String apiKey = config.getApiKey();
          if (apiKey.startsWith("${") && apiKey.endsWith("}")) {
            String envName = apiKey.substring(2, apiKey.length() - 1);
            if (System.getenv(envName) == null) {
              throw new IllegalStateException(
                  "Environment variable not set: " + envName + " (required by " + keyPath + ")");
            }
          }
        });
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

  /** 键的值是否来自本地密钥文件（FR-003 双通道：独立本地配置文件）。 */
  private static boolean fromSecretsFile(ConfigurableEnvironment environment, String key) {
    for (org.springframework.core.env.PropertySource<?> source : environment.getPropertySources()) {
      if (source.getProperty(key) != null && source.getName().contains("nivroos-secrets.yml")) {
        return true;
      }
    }
    return false;
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
