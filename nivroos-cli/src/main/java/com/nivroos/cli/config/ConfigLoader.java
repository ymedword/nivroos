package com.nivroos.cli.config;

/**
 * 敏感配置加载基础版（技术方案 §8.8）。
 *
 * <p>Resolves ${ENV_VAR} placeholders and validates required entries; missing or invalid values
 * fail with a clear error naming the exact item - never silently. This utility stays
 * provider-agnostic; provider binding and validation are owned by the provider module's
 * assembly-time checks.
 */
public final class ConfigLoader {

  private ConfigLoader() {}

  /**
   * 解析 ${ENV_VAR} 占位；环境变量缺失时抛出指明缺失项的异常（FR-007）。
   *
   * @param value 占位表达式（如 ${DEEPSEEK_API_KEY}）或普通值
   * @param keyPath 配置键路径（错误信息用）
   * @return 解析后的值
   */
  public static String resolveEnv(String value, String keyPath) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required config: " + keyPath);
    }
    if (!value.startsWith("${")) {
      return value;
    }
    if (!value.endsWith("}")) {
      throw new IllegalArgumentException("Invalid ${ENV_VAR} placeholder: " + keyPath);
    }
    String envName = value.substring(2, value.length() - 1);
    String resolved = System.getenv(envName);
    if (resolved == null || resolved.isBlank()) {
      throw new IllegalArgumentException(
          "Environment variable not set: " + envName + " (required by " + keyPath + ")");
    }
    return resolved;
  }

  /** 必填校验：值缺失或全空白时报错并指明配置项。 */
  public static void requireNonBlank(String keyPath, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required config: " + keyPath);
    }
  }
}
