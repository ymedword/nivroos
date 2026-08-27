package com.nivroos.core.metrics;

/**
 * {@link MetricsRegistry} 核心阶段唯一实现：空操作。
 *
 * <p>核心阶段不采集指标（原则九：Prometheus 属扩展阶段）， 扩展阶段由 Micrometer 实现替换本类，接口与调用方不变。
 */
public class NoopMetricsRegistry implements MetricsRegistry {

  @Override
  public void incrementCounter(String name) {
    // no-op：核心阶段不采集指标
  }

  @Override
  public void recordTimer(String name, long durationMs) {
    // no-op：核心阶段不采集指标
  }
}
