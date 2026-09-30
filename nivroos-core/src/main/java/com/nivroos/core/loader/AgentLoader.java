package com.nivroos.core.loader;

import com.nivroos.core.context.ContextLoader;
import com.nivroos.core.profile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Agent 加载（技术方案 §8.2）。
 *
 * <p>Reads a single agent's AGENT.md frontmatter and derives the runtime Profile. Validation fails
 * fast with a clear message naming the agent and the missing field. US-4 adds {@link #scan()},
 * which walks the agents root directory.
 */
public class AgentLoader {

  private static final Logger log = LoggerFactory.getLogger(AgentLoader.class);

  private static final Yaml YAML = new Yaml();

  private final Path agentsRoot;

  public AgentLoader(Path agentsRoot) {
    this.agentsRoot = agentsRoot;
  }

  /**
   * 扫描 agents 根目录下的全部 Agent 目录并逐个派生 Profile（技术方案 §8.2）。
   *
   * <p>Per-agent failures are logged and skipped instead of propagated - 一个坏目录不拖垮全部 （FR-008）。{@link
   * #loadProfile} 仍抛出含 Agent 名与缺失字段的清晰异常，那句原文会进 WARN 日志。
   *
   * @return 成功派生的 Profile 列表；根目录不存在或为空时返回空列表
   */
  public List<Profile> scan() {
    if (!Files.isDirectory(agentsRoot)) {
      return List.of();
    }
    List<Profile> profiles = new ArrayList<>();
    try (Stream<Path> entries = Files.list(agentsRoot)) {
      for (Path agentDir : entries.filter(Files::isDirectory).sorted().toList()) {
        // 没有 AGENT.md 的子目录不是 Agent（不报错，跳过）
        if (!Files.isRegularFile(agentDir.resolve("AGENT.md"))) {
          continue;
        }
        // agents 根的子目录必定有名字；requireNonNull 只是把 SpotBugs 的可空路径分析钉住
        String agentName = Objects.requireNonNull(agentDir.getFileName()).toString();
        try {
          profiles.add(loadProfile(agentName));
        } catch (RuntimeException e) {
          log.warn(
              "agent skipped, frontmatter invalid: name={}, reason={}",
              sanitizeForLog(agentName),
              sanitizeForLog(e.getMessage()));
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to scan agents directory: " + agentsRoot, e);
    }
    return profiles;
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

    if (frontmatter.get("description") != null) {
      profile.setDescription(String.valueOf(frontmatter.get("description")));
    }
    if (frontmatter.get("identity") instanceof Map<?, ?> identityMap) {
      // 只派生登记，不额外注入 system prompt（颗粒度文档「待决事项」默认建议）
      profile.setIdentity(
          new Profile.Identity(
              text(identityMap.get("agent_name")), text(identityMap.get("prompt"))));
    }
    profile.setMcpServers(stringList(frontmatter.get("mcp_servers")));
    profile.setBootstrap(stringList(frontmatter.get("bootstrap")));
    profile.setChannels(channelList(frontmatter.get("channels")));
    profile.setSchedules(scheduleList(frontmatter.get("schedules")));
    return profile;
  }

  /** 字符串列表取值：非列表（含缺失）一律当空列表，与可选字段语义一致。 */
  private static List<String> stringList(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (Object item : list) {
      values.add(String.valueOf(item));
    }
    return values;
  }

  private static List<Profile.Channel> channelList(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<Profile.Channel> channels = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        channels.add(new Profile.Channel(text(map.get("name")), stringKeyedMap(map.get("config"))));
      }
    }
    return channels;
  }

  private static List<Profile.Schedule> scheduleList(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<Profile.Schedule> schedules = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        schedules.add(new Profile.Schedule(text(map.get("cron")), text(map.get("message"))));
      }
    }
    return schedules;
  }

  /** YAML 的键是字符串，取值时统一成 Map<String, Object> 供 Channel.config 使用。 */
  private static Map<String, Object> stringKeyedMap(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private static String text(Object raw) {
    return raw == null ? null : String.valueOf(raw);
  }

  /** 日志参数 CRLF 消毒：Agent 名与异常文案可能带换行，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
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
