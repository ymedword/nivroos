package com.nivroos.core.profile;

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
}
