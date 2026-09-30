package com.nivroos.core.notify;

import java.util.List;
import java.util.Optional;

/**
 * 通知渠道注册表读取接口（技术方案 §6.8）。
 *
 * <p>Read contract for the global notification registry; interface in core, JPA implementation in
 * nivroos-storage (dependency inversion, same pattern as ToolInvocationStore). The registry is
 * global - channels are not a Profile field (AGENT.md frontmatter must not carry {@code
 * notify_channels}).
 */
public interface NotifyChannelStore {

  /**
   * 按名解析通知渠道。
   *
   * <p>Returns empty rather than throwing when the name is unknown: the caller composes the failure
   * message and backfills the available names, so the model can self-correct.
   */
  Optional<NotifyChannel> findByName(String name);

  /** 全部已注册渠道名（按名排序），失败信息回填可用渠道名清单用。 */
  List<String> channelNames();
}
