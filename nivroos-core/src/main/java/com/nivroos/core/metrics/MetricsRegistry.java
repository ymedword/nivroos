package com.nivroos.core.metrics;

/**
 * 指标收集接口（核心阶段预留，遵循原则六「接口先行」模式）。
 *
 * <p>核心阶段唯一实现为 {@link NoopMetricsRegistry}（零依赖）；扩展阶段引入 Micrometer + Prometheus 时新增实现类并替换装配，接口不变。
 * 埋点从 US-2（ReActLoop 迭代耗时）起即可写入调用方。 核心阶段可观测性另由结构化日志与审计表（llm_calls / tool_invocations）承担。
 */
public interface MetricsRegistry {

  /**
   * 累加计数（如 LLM 调用次数、Tool 调用次数）。
   *
   * @param name 指标名，小写下划线命名，如 llm_calls_total
   */
  void incrementCounter(String name);

  /**
   * 记录一次耗时（如单轮 ReAct 迭代耗时）。
   *
   * @param name 指标名，小写下划线命名，如 react_iteration_seconds
   * @param durationMs 耗时（毫秒）
   */
  void recordTimer(String name, long durationMs);
}
