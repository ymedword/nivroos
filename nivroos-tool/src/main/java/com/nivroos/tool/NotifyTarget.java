package com.nivroos.tool;

import java.util.Map;

/**
 * 通知投递目标（技术方案 §6.8：`NotifyTarget = { channelType, config }`）。
 *
 * <p>Deliberately channel-agnostic: the adapter decides how to dispatch based on {@code
 * channelType}, and everything it needs (url today, signature params later) travels in {@code
 * config}. The webhook url therefore stays out of the conversation history - the model only names
 * the channel.
 *
 * @param channelType 渠道类型（核心阶段唯一受支持取值 `webhook`）
 * @param config 渠道配置（至少含 `url`）
 */
public record NotifyTarget(String channelType, Map<String, String> config) {

  /** 紧凑构造器：防御性拷贝，避免调用方事后修改 Map 影响已构造的目标（SpotBugs EI_EXPOSE_REP）。 */
  public NotifyTarget {
    config = config == null ? Map.of() : Map.copyOf(config);
  }
}
