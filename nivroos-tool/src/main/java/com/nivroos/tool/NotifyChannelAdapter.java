package com.nivroos.tool;

/**
 * 通知出站适配器（技术方案 §6.8：接口先行，核心阶段只实现 Webhook 一档）。
 *
 * <p>Symmetric to the inbound ChannelAdapter but deliberately not merged with it: inbound answers
 * "what starts the agent", outbound answers "where the result goes". Per-channel payload formats
 * (WeCom / Feishu / DingTalk signatures) are added later as new implementations - this interface
 * does not change.
 */
public interface NotifyChannelAdapter {

  /**
   * 把一条内容送到指定通知目标；失败抛异常（不吞，由上层落审计与日志）。
   *
   * @param target 投递目标（渠道类型 + 配置）
   * @param content 推送文本
   */
  void send(NotifyTarget target, String content);
}
