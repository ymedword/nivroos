package com.nivroos.core.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nivroos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  private static final String FULL_AGENT =
      """
      ---
      name: ops-agent
      description: 运维助手
      identity:
        agent_name: 运维小欧
        prompt: 你是一个专业的运维助手
      provider:
        name: deepseek
        model: deepseek-chat
      tools:
        - read_file
        - shell
      mcp_servers:
        - github-mcp
      channels:
        - name: cli
          config:
            user_id: ops
      bootstrap:
        - AGENTS.md
        - SOUL.md
        - USER.md
      settings:
        max_iterations: 5
      schedules:
        - cron: "0 8 * * *"
          message: "查一下今天的天气"
      ---

      你是运维助手。
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
  @DisplayName("完整派生：description / identity / mcp_servers / bootstrap / channels / schedules 逐字段")
  void loadProfile_derivesFullFrontmatter() throws IOException {
    writeAgent("ops-agent", FULL_AGENT);
    AgentLoader loader = new AgentLoader(agentsRoot);

    Profile profile = loader.loadProfile("ops-agent");

    assertThat(profile.getDescription()).isEqualTo("运维助手");
    assertThat(profile.getIdentity().agentName()).isEqualTo("运维小欧");
    assertThat(profile.getIdentity().prompt()).isEqualTo("你是一个专业的运维助手");
    assertThat(profile.getMcpServers()).containsExactly("github-mcp");
    assertThat(profile.getBootstrap()).containsExactly("AGENTS.md", "SOUL.md", "USER.md");
    assertThat(profile.getChannels()).hasSize(1);
    assertThat(profile.getChannels().get(0).name()).isEqualTo("cli");
    assertThat(profile.getChannels().get(0).config()).containsEntry("user_id", "ops");
    assertThat(profile.getSchedules()).hasSize(1);
    assertThat(profile.getSchedules().get(0).cron()).isEqualTo("0 8 * * *");
    assertThat(profile.getSchedules().get(0).message()).isEqualTo("查一下今天的天气");
  }

  @Test
  @DisplayName("新增字段缺失时取空值（可选字段），不报错")
  void loadProfile_optionalSectionsAbsent_defaultEmpty() throws IOException {
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

    Profile profile = new AgentLoader(agentsRoot).loadProfile("minimal");

    assertThat(profile.getDescription()).isNull();
    assertThat(profile.getIdentity()).isNull();
    assertThat(profile.getMcpServers()).isEmpty();
    assertThat(profile.getBootstrap()).isEmpty();
    assertThat(profile.getChannels()).isEmpty();
    assertThat(profile.getSchedules()).isEmpty();
    // 列表是防御性副本：改返回值不影响 Profile
    assertThatThrownBy(() -> profile.getBootstrap().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("scan：扫多个 Agent 目录逐个派生")
  void scan_derivesEveryAgentDirectory() throws IOException {
    writeAgent("weather", VALID_AGENT);
    writeAgent("ops-agent", FULL_AGENT);
    AgentLoader loader = new AgentLoader(agentsRoot);

    List<Profile> profiles = loader.scan();

    assertThat(profiles).extracting(Profile::getName).containsExactly("ops-agent", "weather");
  }

  @Test
  @DisplayName("scan：单个 Agent frontmatter 非法不阻断其它 Agent")
  void scan_oneInvalidAgent_othersStillLoaded() throws IOException {
    writeAgent(
        "broken",
        """
        ---
        name: broken
        ---

        缺 provider
        """);
    writeAgent("weather", VALID_AGENT);
    AgentLoader loader = new AgentLoader(agentsRoot);

    List<Profile> profiles = loader.scan();

    assertThat(profiles).extracting(Profile::getName).containsExactly("weather");
  }

  @Test
  @DisplayName("scan：agents 根目录不存在或为空 → 视为没有 Agent")
  void scan_missingRootOrEmpty_returnsEmpty() throws IOException {
    AgentLoader loader = new AgentLoader(agentsRoot.resolve("nope"));

    assertThat(loader.scan()).isEmpty();
    assertThat(new AgentLoader(agentsRoot).scan()).isEmpty();
  }

  @Test
  @DisplayName("scan：无 AGENT.md 的子目录不算 Agent，静默跳过")
  void scan_directoryWithoutAgentMd_skipped() throws IOException {
    Files.createDirectories(agentsRoot.resolve("not-an-agent/scripts"));
    writeAgent("weather", VALID_AGENT);

    List<Profile> profiles = new AgentLoader(agentsRoot).scan();

    assertThat(profiles).extracting(Profile::getName).containsExactly("weather");
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
