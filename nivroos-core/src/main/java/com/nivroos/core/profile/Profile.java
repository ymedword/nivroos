package com.nivroos.core.profile;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运行时宿主配置（技术方案 §8.2）。
 *
 * <p>Runtime host configuration derived from AGENT.md frontmatter; US-1 defines only the provider
 * section - the full derivation (AgentLoader) lands in US-4.
 */
public class Profile {

  /** Agent 名（= 目录名）。 */
  private String name;

  /** 供应商名称，必须存在于已注册的供应商集合（FR-010）。 */
  private String providerName;

  /** 模型名（如 deepseek-chat）。 */
  private String model;

  /** 温度参数（可选 0~2，缺失走厂商默认）。 */
  private Double temperature;

  /** 可用工具名列表（US-2 起，工具池按名解析）。 */
  private List<String> tools = new ArrayList<>();

  /** 运行设置（US-2 起，带默认值）。 */
  private Settings settings = new Settings();

  /** Agent 描述（US-4 新增，AGENT.md 的 description）。 */
  private String description;

  /** 身份（US-4 新增；只派生登记，不额外注入 system prompt）。 */
  private Identity identity;

  /** 引用的 MCP server 名（US-4 新增，frontmatter 键 mcp_servers）。 */
  private List<String> mcpServers = new ArrayList<>();

  /** 引导文件列表（US-4 新增，frontmatter 键 bootstrap）。 */
  private List<String> bootstrap = new ArrayList<>();

  /** 接入渠道声明（US-4 新增）。 */
  private List<Channel> channels = new ArrayList<>();

  /** 定时任务声明（US-4 新增；消费归 US-5 的 AgentScheduler）。 */
  private List<Schedule> schedules = new ArrayList<>();

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getProviderName() {
    return providerName;
  }

  public void setProviderName(String providerName) {
    this.providerName = providerName;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public Double getTemperature() {
    return temperature;
  }

  public void setTemperature(Double temperature) {
    this.temperature = temperature;
  }

  public List<String> getTools() {
    // 不可变视图：调用方（ReActLoop 解析）只读
    return List.copyOf(tools);
  }

  public void setTools(List<String> tools) {
    this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Settings 是可变值对象，AgentLoader 与测试经 getter 就地设置字段；非安全边界（与 ProviderProperties 同模式）")
  public Settings getSettings() {
    return settings;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Settings 是可变值对象，AgentLoader 与测试经 getter 就地设置字段；非安全边界（与 ProviderProperties 同模式）")
  public void setSettings(Settings settings) {
    this.settings = settings;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Identity getIdentity() {
    return identity;
  }

  public void setIdentity(Identity identity) {
    this.identity = identity;
  }

  public List<String> getMcpServers() {
    // 不可变视图：调用方（ProfileConfiguration 校验）只读
    return List.copyOf(mcpServers);
  }

  public void setMcpServers(List<String> mcpServers) {
    this.mcpServers = mcpServers == null ? new ArrayList<>() : new ArrayList<>(mcpServers);
  }

  public List<String> getBootstrap() {
    // 不可变视图：调用方（ContextLoader）只读
    return List.copyOf(bootstrap);
  }

  public void setBootstrap(List<String> bootstrap) {
    this.bootstrap = bootstrap == null ? new ArrayList<>() : new ArrayList<>(bootstrap);
  }

  public List<Channel> getChannels() {
    return List.copyOf(channels);
  }

  public void setChannels(List<Channel> channels) {
    this.channels = channels == null ? new ArrayList<>() : new ArrayList<>(channels);
  }

  public List<Schedule> getSchedules() {
    return List.copyOf(schedules);
  }

  public void setSchedules(List<Schedule> schedules) {
    this.schedules = schedules == null ? new ArrayList<>() : new ArrayList<>(schedules);
  }

  /** 身份：AGENT.md 的 identity.agent_name / identity.prompt（技术方案 §8.2）。 */
  public record Identity(String agentName, String prompt) {}

  /** 渠道声明：name + 渠道自定义配置（config 为不可变副本）。 */
  public record Channel(String name, Map<String, Object> config) {

    public Channel {
      config = config == null ? Map.of() : Map.copyOf(config);
    }
  }

  /** 定时任务声明：cron 表达式 + 触发消息（消费归 US-5 AgentScheduler）。 */
  public record Schedule(String cron, String message) {}

  /** 运行设置（技术方案 §4.3 默认值）。 */
  public static class Settings {

    /** 最大 ReAct 迭代次数，防死循环（默认 10）。 */
    private int maxIterations = 10;

    /** 最大对话历史轮数（默认 20）。 */
    private int maxHistoryTurns = 20;

    public int getMaxIterations() {
      return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
      this.maxIterations = maxIterations;
    }

    public int getMaxHistoryTurns() {
      return maxHistoryTurns;
    }

    public void setMaxHistoryTurns(int maxHistoryTurns) {
      this.maxHistoryTurns = maxHistoryTurns;
    }
  }
}
