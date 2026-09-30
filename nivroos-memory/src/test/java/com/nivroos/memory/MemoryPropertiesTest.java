package com.nivroos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

/**
 * MemoryProperties 验收点：契约 contracts/memory-config.md（键默认值 + 凭证双通道 4 条规则）。
 *
 * <p>颗粒度文档 §4.2 把「凭证缺失报错清晰」挂在本类而不在 Mem0MemoryStore——校验归配置装配期， store 只做 HTTP 映射（见
 * Mem0MemoryStoreTest 类注释）。凭证检查分两条基准：明文按**原始配置值** 判定（绑定值已被 Spring 解析，无法区分来源），缺失按**绑定值**判定（Boot 3.5
 * 绑定器对 {@code ${ENV_VAR}} 保持字面量）。
 */
class MemoryPropertiesTest {

  /** 确信未设置的环境变量名，用于「占位符未解析」用例。 */
  private static final String UNSET_ENV_VAR = "NIVROOS_TEST_MEM0_KEY_UNSET";

  private static MemoryProperties props(String backend, int archiveMaxChars) {
    MemoryProperties properties = new MemoryProperties();
    properties.setBackend(backend);
    properties.setArchiveMaxChars(archiveMaxChars);
    return properties;
  }

  private static MockEnvironment envOf(String key, String value) {
    MockEnvironment environment = new MockEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MockPropertySource("test").withProperty(key, value));
    return environment;
  }

  private static MemoryProperties.Mem0 mem0(String url, String apiKey) {
    MemoryProperties.Mem0 mem0 = new MemoryProperties.Mem0();
    mem0.setUrl(url);
    mem0.setApiKey(apiKey);
    return mem0;
  }

  // ---------------------------------------------------------------- 默认值

  @Test
  @DisplayName("默认后端 markdown，未配置时归档阈值默认 4000 字符")
  void defaults_areMarkdownAnd4000() {
    MemoryProperties properties = new MemoryProperties();

    assertThat(properties.getBackend()).isEqualTo("markdown");
    assertThat(properties.getArchiveMaxChars()).isEqualTo(4000);
    assertThat(properties.getArchiveMaxChars())
        .isEqualTo(MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    assertThatCode(() -> properties.validate(new MockEnvironment())).doesNotThrowAnyException();
  }

  // ---------------------------------------------------------------- backend / 阈值校验

  @Test
  @DisplayName("非法 backend 取值 → 报错并列出合法取值")
  void validate_illegalBackend_listsSupportedValues() {
    MemoryProperties properties = props("redis", MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    assertThatThrownBy(() -> properties.validate(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("memory.backend=redis")
        .hasMessageContaining("markdown")
        .hasMessageContaining("sqlite")
        .hasMessageContaining("mem0");
  }

  @Test
  @DisplayName("archive-max-chars ≤ 0 → 报错（核心区不受此值影响，但值本身必须合法）")
  void validate_nonPositiveArchiveMaxChars_throws() {
    MemoryProperties properties = props("markdown", 0);

    assertThatThrownBy(() -> properties.validate(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("archive-max-chars");
  }

  // ---------------------------------------------------------------- mem0 必填项

  @Test
  @DisplayName("backend=mem0 但 url 缺失 → 报错指明缺失键")
  void validate_mem0WithoutUrl_throws() {
    MemoryProperties properties = props("mem0", MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    properties.setMem0(mem0("  ", "${MEM0_API_KEY}"));

    assertThatThrownBy(() -> properties.validate(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("memory.mem0.url");
  }

  @Test
  @DisplayName("backend=mem0 但 api-key 缺失 → 报错指明缺失键")
  void validate_mem0WithoutApiKey_throws() {
    MemoryProperties properties = props("mem0", MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    properties.setMem0(mem0("http://localhost:8000", null));

    assertThatThrownBy(() -> properties.validate(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("memory.mem0.api-key");
  }

  // ---------------------------------------------------------------- 凭证双通道（4 条规则）

  @Test
  @DisplayName("明文 api-key → 报错文案逐字同契约")
  void validate_mem0PlaintextKey_rejected() {
    MemoryProperties properties = props("mem0", MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    properties.setMem0(mem0("http://localhost:8000", "sk-plaintext-key"));

    assertThatThrownBy(
            () -> properties.validate(envOf("memory.mem0.api-key", "sk-plaintext-key"))) // 原始值也是明文
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "api-key must be an ${ENV_VAR} placeholder, plaintext is forbidden:"
                + " memory.mem0.api-key");
  }

  @Test
  @DisplayName("明文 api-key 来自 nivroos-secrets.yml → 放行（双通道之二）")
  void validate_mem0PlaintextKeyFromSecretsFile_accepted() {
    MemoryProperties properties = props("mem0", MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    properties.setMem0(mem0("http://localhost:8000", "sk-from-local-file"));
    MockEnvironment environment = new MockEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MockPropertySource("applicationConfig: [file:./nivroos-secrets.yml]")
                .withProperty("memory.mem0.api-key", "sk-from-local-file"));

    assertThatCode(() -> properties.validate(environment)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("占位符未解析（环境变量未设置）→ 报错指明变量名")
  void validate_mem0UnresolvedPlaceholder_namesEnvVar() {
    MemoryProperties properties = props("mem0", MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    // 绑定值仍是字面量 = Boot 3.5 绑定器未解析（同 US-1 实测语义）
    properties.setMem0(mem0("http://localhost:8000", "${" + UNSET_ENV_VAR + "}"));

    assertThatThrownBy(
            () -> properties.validate(envOf("memory.mem0.api-key", "${" + UNSET_ENV_VAR + "}")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Environment variable not set: " + UNSET_ENV_VAR)
        .hasMessageContaining("memory.mem0.api-key");
  }

  @Test
  @DisplayName("占位符已设置的环境变量 → 放行")
  void validate_mem0ResolvedPlaceholder_accepted() {
    MemoryProperties properties = props("mem0", MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    // PATH 恒存在，用来模拟「环境变量已设置、绑定值已被解析」的成功路径
    properties.setMem0(mem0("http://localhost:8000", "sk-resolved"));
    MockEnvironment environment = envOf("memory.mem0.api-key", "${PATH}");

    assertThatCode(() -> properties.validate(environment)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("backend=markdown / sqlite 时不校验 mem0 段（mem0 配置可缺省）")
  void validate_nonMem0Backend_skipsMem0Checks() {
    MemoryProperties markdown = props("markdown", MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);
    MemoryProperties sqlite = props("sqlite", MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    assertThatCode(() -> markdown.validate(new MockEnvironment())).doesNotThrowAnyException();
    assertThatCode(() -> sqlite.validate(new MockEnvironment())).doesNotThrowAnyException();
  }
}
