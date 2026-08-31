package com.nivroos.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.yaml.snakeyaml.Yaml;

/**
 * 配置校验验收点：颗粒度文档 §4.2（FR-003 / FR-007 / FR-009 / base-url 可选）。
 *
 * <p>validate(Environment) 语义：明文检查按原始配置值、缺失环境变量按绑定值判断—— 绑定值已被 Spring 解析，无法区分"明文"与"解析后的真实 key"。
 */
class ProviderPropertiesTest {

  private static final String KEY_PATH = "nivroos.providers.deepseek.api-key";

  @Test
  @DisplayName("明文 api-key 必须被校验拒绝并指明键路径（FR-003）")
  void plaintextApiKey_rejectedWithKeyPath() {
    ProviderProperties props = properties("deepseek", "sk-plaintext", null);

    assertThatThrownBy(() -> props.validate(environmentWith(KEY_PATH, "sk-plaintext")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(KEY_PATH);
  }

  @Test
  @DisplayName("空 api-key 必须被校验拒绝")
  void blankApiKey_rejected() {
    ProviderProperties props = properties("deepseek", " ", null);

    assertThatThrownBy(() -> props.validate(environmentWith(KEY_PATH, " ")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("占位环境变量未设置时报错并指明缺失项（FR-007）")
  void missingEnvVar_rejectedWithEnvName() {
    ProviderProperties props = properties("deepseek", "${MISSING_ENV_XYZ}", null);

    assertThatThrownBy(() -> props.validate(environmentWith(KEY_PATH, "${MISSING_ENV_XYZ}")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MISSING_ENV_XYZ")
        .hasMessageContaining(KEY_PATH);
  }

  @Test
  @DisplayName("FR-003 双通道之二：来自本地密钥文件的明文值放行")
  void plaintextFromSecretsFile_passesValidation() {
    ProviderProperties props = properties("deepseek", "sk-from-local-file", null);

    // 属性源名含 nivroos-secrets.yml 的明文值 = 独立本地配置文件通道，放行
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "Config resource 'file [./nivroos-secrets.yml]'",
                Map.of(KEY_PATH, "sk-from-local-file")));

    props.validate(environment);
  }

  @Test
  @DisplayName("回归（US-2 修复）：Spring 已解析占位符后绑定值为真实 key，必须放行")
  void resolvedPlaceholderValue_passesValidation() {
    // 原始配置是 ${ENV} 占位、绑定值已被 Spring 解析为真实 key —— 不得误判为明文
    ProviderProperties props = properties("deepseek", "sk-resolved-real-key", null);

    props.validate(environmentWith(KEY_PATH, "${DEEPSEEK_API_KEY}"));
  }

  @Test
  @DisplayName("base-url 可选；占位符形式且环境变量已设置时校验通过")
  void optionalBaseUrl_passesValidation() {
    // PATH 在所有测试/CI 环境必然存在，用于验证"占位符 + 环境变量已设置"的正路径
    ProviderProperties props = properties("deepseek", "${PATH}", null);

    props.validate(environmentWith(KEY_PATH, "${PATH}"));
  }

  @Test
  @DisplayName("同名供应商由 YAML 解析层拒绝（FR-009）")
  void duplicateProviderNames_rejectedByYamlParser() {
    // 配置契约是 map 形式（name 为键）：重复键由 SnakeYAML 在解析层直接拒绝
    String yaml = "deepseek: {api-key: ${A}}\ndeepseek: {api-key: ${B}}\n";

    assertThatThrownBy(() -> new Yaml().load(yaml))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("deepseek");
  }

  private static ProviderProperties properties(String name, String apiKey, String baseUrl) {
    ProviderProperties props = new ProviderProperties();
    props.setProviders(Map.of(name, new ProviderProperties.ProviderConfig(apiKey, baseUrl)));
    return props;
  }

  private static StandardEnvironment environmentWith(String key, String rawValue) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(key, rawValue)));
    return environment;
  }
}
