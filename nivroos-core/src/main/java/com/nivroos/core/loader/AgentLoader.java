package com.nivroos.core.loader;

import com.nivroos.core.context.ContextLoader;
import com.nivroos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Agent 加载（技术方案 §8.2 简化版；目录扫描/软连接/运行时注册归 US-4）。
 *
 * <p>Reads a single agent's AGENT.md frontmatter and derives the runtime Profile. Validation fails
 * fast with a clear message naming the agent and the missing field.
 */
public class AgentLoader {

  private static final Yaml YAML = new Yaml();

  private final Path agentsRoot;

  public AgentLoader(Path agentsRoot) {
    this.agentsRoot = agentsRoot;
  }

  /**
   * 按名加载单个 Agent 并派生 Profile。
   *
   * @param agentName Agent 目录名
   * @return 派生的 Profile（settings 缺省时取默认 10/20）
   */
  public Profile loadProfile(String agentName) {
    Path agentFile = agentsRoot.resolve(agentName).resolve("AGENT.md");
    String raw;
    try {
      raw = Files.readString(agentFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Agent not found: " + agentName + " (" + agentFile + ")", e);
    }

    Map<String, Object> frontmatter = parseFrontmatter(raw, agentName);
    return deriveProfile(agentName, frontmatter);
  }

  @SuppressWarnings("unchecked")
  private Profile deriveProfile(String agentName, Map<String, Object> frontmatter) {
    Profile profile = new Profile();
    profile.setName(String.valueOf(frontmatter.getOrDefault("name", agentName)));

    Object providerRaw = frontmatter.get("provider");
    if (!(providerRaw instanceof Map<?, ?> providerMap)) {
      // provider 必填（US-1 装配期校验依赖它），缺失必须清晰报错
      throw new IllegalStateException("Agent '" + agentName + "': missing 'provider' section");
    }
    profile.setProviderName(String.valueOf(providerMap.get("name")));
    if (providerMap.get("model") != null) {
      profile.setModel(String.valueOf(providerMap.get("model")));
    }
    if (providerMap.get("temperature") instanceof Number temperature) {
      profile.setTemperature(temperature.doubleValue());
    }

    Object toolsRaw = frontmatter.get("tools");
    if (toolsRaw instanceof List<?> toolsList) {
      List<String> tools = new ArrayList<>();
      for (Object tool : toolsList) {
        tools.add(String.valueOf(tool));
      }
      profile.setTools(tools);
    }

    if (frontmatter.get("settings") instanceof Map<?, ?> settingsMap) {
      Profile.Settings settings = profile.getSettings();
      if (settingsMap.get("max_iterations") instanceof Number value) {
        settings.setMaxIterations(value.intValue());
      }
      if (settingsMap.get("max_history_turns") instanceof Number value) {
        settings.setMaxHistoryTurns(value.intValue());
      }
    }
    return profile;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseFrontmatter(String raw, String agentName) {
    String stripped = ContextLoader.stripFrontmatter(raw);
    if (stripped.equals(raw.strip())) {
      throw new IllegalStateException(
          "Agent '" + agentName + "': AGENT.md must start with --- frontmatter");
    }
    String frontmatterText = raw.strip();
    int end = frontmatterText.indexOf("---", 3);
    String yamlText = frontmatterText.substring(3, end).strip();
    Object loaded = YAML.load(yamlText);
    if (!(loaded instanceof Map<?, ?> map)) {
      throw new IllegalStateException("Agent '" + agentName + "': frontmatter must be a YAML map");
    }
    return (Map<String, Object>) map;
  }
}
