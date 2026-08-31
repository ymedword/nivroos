package com.nivroos.core.profile;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;

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
