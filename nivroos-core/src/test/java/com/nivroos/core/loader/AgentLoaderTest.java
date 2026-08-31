package com.nivroos.core.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nivroos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Agent 加载验收点：颗粒度文档 §4.2（frontmatter 解析 / 校验）。 */
class AgentLoaderTest {

  @TempDir Path agentsRoot;

  private static final String VALID_AGENT =
      """
      ---
      name: weather
      description: 天气助手
      provider:
        name: deepseek
        model: deepseek-chat
        temperature: 0.7
      tools:
        - http_get
      settings:
        max_iterations: 5
        max_history_turns: 8
      ---

      你是天气助手。
      """;

  @Test
  @DisplayName("frontmatter 派生 Profile：provider/tools/settings 全部解析")
  void loadProfile_parsesFrontmatter() throws IOException {
    writeAgent("weather", VALID_AGENT);
    AgentLoader loader = new AgentLoader(agentsRoot);

    Profile profile = loader.loadProfile("weather");

    assertThat(profile.getName()).isEqualTo("weather");
    assertThat(profile.getProviderName()).isEqualTo("deepseek");
    assertThat(profile.getModel()).isEqualTo("deepseek-chat");
    assertThat(profile.getTemperature()).isEqualTo(0.7);
    assertThat(profile.getTools()).containsExactly("http_get");
    assertThat(profile.getSettings().getMaxIterations()).isEqualTo(5);
    assertThat(profile.getSettings().getMaxHistoryTurns()).isEqualTo(8);
  }

  @Test
  @DisplayName("settings 缺省时使用默认值 10/20")
  void loadProfile_appliesDefaults() throws IOException {
    writeAgent(
        "minimal",
        """
        ---
        name: minimal
        provider:
          name: deepseek
          model: deepseek-chat
        ---

        正文
        """);
    AgentLoader loader = new AgentLoader(agentsRoot);

    Profile profile = loader.loadProfile("minimal");

    assertThat(profile.getSettings().getMaxIterations()).isEqualTo(10);
    assertThat(profile.getSettings().getMaxHistoryTurns()).isEqualTo(20);
    assertThat(profile.getTools()).isEmpty();
  }

  @Test
  @DisplayName("provider 缺失时报错清晰")
  void loadProfile_missingProvider_reportsClearly() throws IOException {
    writeAgent(
        "bad",
        """
        ---
        name: bad
        ---

        正文
        """);
    AgentLoader loader = new AgentLoader(agentsRoot);

    assertThatThrownBy(() -> loader.loadProfile("bad"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bad")
        .hasMessageContaining("provider");
  }

  @Test
  @DisplayName("Agent 不存在时报错清晰")
  void loadProfile_missingAgent_reportsClearly() {
    AgentLoader loader = new AgentLoader(agentsRoot);

    assertThatThrownBy(() -> loader.loadProfile("nope"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nope");
  }

  private void writeAgent(String name, String content) throws IOException {
    Path dir = agentsRoot.resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("AGENT.md"), content, StandardCharsets.UTF_8);
  }
}
