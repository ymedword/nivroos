package com.nivroos.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** 配置校验验收点：颗粒度文档 §4.2（FR-003 / FR-007 / FR-009 / base-url 可选）。 */
class ProviderPropertiesTest {

  @Test
  @DisplayName("明文 api-key 必须被校验拒绝并指明键路径（FR-003）")
  void plaintextApiKey_rejectedWithKeyPath() {
    ProviderProperties props = properties("deepseek", "sk-plaintext", null);

    assertThatThrownBy(props::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nivroos.providers.deepseek.api-key");
  }

  @Test
  @DisplayName("空 api-key 必须被校验拒绝")
  void blankApiKey_rejected() {
    ProviderProperties props = properties("deepseek", " ", null);

    assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("占位环境变量未设置时报错并指明缺失项（FR-007）")
  void missingEnvVar_rejectedWithEnvName() {
    ProviderProperties props = properties("deepseek", "${MISSING_ENV_XYZ}", null);

    assertThatThrownBy(props::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MISSING_ENV_XYZ")
        .hasMessageContaining("nivroos.providers.deepseek.api-key");
  }

  @Test
  @DisplayName("base-url 可选；占位符形式且环境变量已设置时校验通过")
  void optionalBaseUrl_passesValidation() {
    // PATH 在所有测试/CI 环境必然存在，用于验证"占位符 + 环境变量已设置"的正路径
    ProviderProperties props = properties("deepseek", "${PATH}", null);

    props.validate();
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
}
