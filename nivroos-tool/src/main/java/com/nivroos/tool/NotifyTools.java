package com.nivroos.tool;

import com.nivroos.core.model.ToolResult;
import com.nivroos.core.notify.NotifyChannel;
import com.nivroos.core.notify.NotifyChannelStore;
import com.nivroos.tool.sandbox.Sandbox;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 通知工具（技术方案 §6.8；契约 contracts/builtin-tools.md §5）。
 *
 * <p>The model only ever names a channel; the registry lookup turns that name into a webhook url,
 * so the address (itself a credential) never enters the conversation history. Every failure path
 * backfills the available channel names - the model cannot guess a channel it has never seen, and a
 * silent failure would leave it retrying the same wrong name.
 *
 * <p>依契约 §1，白名单校验在适配器内、发送前发生（`notify` 是唯一不在工具方法首步 enforce 的内置工具）， 因此构造签名里的 {@code Sandbox}
 * 本类不持有、不重复校验。
 */
public class NotifyTools {

  private static final Logger log = LoggerFactory.getLogger(NotifyTools.class);

  private static final String TYPE_WEBHOOK = "webhook";

  private final NotifyChannelStore channelStore;
  private final NotifyChannelAdapter adapter;

  /** 构造签名是颗粒度文档 §3.1 的已定字面量；{@code sandbox} 由适配器持有并执行校验（见类注释）。 */
  public NotifyTools(
      Sandbox sandbox, NotifyChannelStore channelStore, NotifyChannelAdapter adapter) {
    this.channelStore = channelStore;
    this.adapter = adapter;
  }

  /**
   * 推送到指定名称的通知渠道。
   *
   * <p>缺省 {@code channel} 即失败（不猜"默认渠道"——渠道由全局注册表管理，无默认概念）， 与"渠道不存在 / 类型不支持"同样回填可用渠道名。
   */
  @Tool(name = "notify", description = "推送消息到通知渠道（按名解析全局注册表里的渠道，受域名白名单约束）")
  public ToolResult notify(
      @ToolParam(description = "推送内容") String content,
      @ToolParam(description = "通知渠道名（全局注册表里的 name）", required = false) String channel) {
    if (content == null) {
      return new ToolResult(false, null, "notify 需要 content 参数", false);
    }
    if (channel == null || channel.isBlank()) {
      return failure("notify 需要 channel 参数（无默认渠道）");
    }

    Optional<NotifyChannel> found = channelStore.findByName(channel);
    if (found.isEmpty()) {
      return failure("notify 渠道不存在：" + channel);
    }
    NotifyChannel resolved = found.get();
    if (!TYPE_WEBHOOK.equals(resolved.type())) {
      return failure("notify 渠道类型不受支持：" + resolved.type() + "（核心阶段仅支持 webhook）");
    }

    // 白名单校验与发送都在适配器内完成，顺序是安全边界（契约 §4）
    adapter.send(new NotifyTarget(resolved.type(), Map.of("url", resolved.url())), content);
    return new ToolResult(true, "已推送到渠道 " + resolved.name(), null, false);
  }

  /** 失败结果 + 可用渠道名清单（业务性失败返回结果，不抛异常——契约 §0.2）。 */
  private ToolResult failure(String reason) {
    List<String> available = channelStore.channelNames();
    String names = available.isEmpty() ? "（无，需先手工 SQL 注册）" : String.join(", ", available);
    log.warn("{}, 可用渠道: {}", sanitizeForLog(reason), sanitizeForLog(names));
    return new ToolResult(false, null, reason + "；可用渠道：" + names, false);
  }

  /** 日志参数 CRLF 消毒：渠道名与模型给的 channel 都可能带换行，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
