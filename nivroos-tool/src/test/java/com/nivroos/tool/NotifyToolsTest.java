package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nivroos.core.model.ToolResult;
import com.nivroos.core.notify.NotifyChannel;
import com.nivroos.core.notify.NotifyChannelStore;
import com.nivroos.tool.sandbox.Sandbox;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/** NotifyTools 验收点：颗粒度文档 §4.2（渠道解析 / 失败回填可用渠道名 / 不静默）。 */
class NotifyToolsTest {

  private static final NotifyChannel OPS_TEAM =
      new NotifyChannel("ops-team", "webhook", "https://qyapi.example.com/hook", "运维值班群");

  private static NotifyChannelStore storeWith(NotifyChannel channel) {
    NotifyChannelStore store = mock(NotifyChannelStore.class);
    when(store.findByName(channel.name())).thenReturn(Optional.of(channel));
    when(store.channelNames()).thenReturn(List.of("ops-team", "dev-team"));
    return store;
  }

  @Test
  @DisplayName("渠道解析 → 适配器收到注册表里的 type / url（地址不进对话上下文）")
  void notify_knownChannel_sendsResolvedTarget() {
    NotifyChannelAdapter adapter = mock(NotifyChannelAdapter.class);
    NotifyTools tools = new NotifyTools(mock(Sandbox.class), storeWith(OPS_TEAM), adapter);

    ToolResult result = tools.notify("磁盘告警", "ops-team");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("ops-team");

    ArgumentCaptor<NotifyTarget> captor = ArgumentCaptor.forClass(NotifyTarget.class);
    verify(adapter).send(captor.capture(), eq("磁盘告警"));
    assertThat(captor.getValue().channelType()).isEqualTo("webhook");
    assertThat(captor.getValue().config()).containsEntry("url", "https://qyapi.example.com/hook");
  }

  @Test
  @DisplayName("channel 缺省 → 清晰失败并回填可用渠道名（不猜默认渠道、不静默）")
  void notify_missingChannel_failsWithAvailableNames() {
    NotifyChannelAdapter adapter = mock(NotifyChannelAdapter.class);
    NotifyTools tools = new NotifyTools(mock(Sandbox.class), storeWith(OPS_TEAM), adapter);

    ListAppender<ILoggingEvent> appender = attachAppender();
    ToolResult result;
    try {
      result = tools.notify("告警", null);
    } finally {
      detachAppender(appender);
    }

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("channel").contains("ops-team").contains("dev-team");
    verify(adapter, never()).send(any(), any());
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("notify 需要 channel");
            });
  }

  @Test
  @DisplayName("渠道名不存在 → 清晰失败并回填可用渠道名")
  void notify_unknownChannel_failsWithAvailableNames() {
    NotifyChannelAdapter adapter = mock(NotifyChannelAdapter.class);
    NotifyTools tools = new NotifyTools(mock(Sandbox.class), storeWith(OPS_TEAM), adapter);

    ListAppender<ILoggingEvent> appender = attachAppender();
    ToolResult result;
    try {
      result = tools.notify("告警", "nope");
    } finally {
      detachAppender(appender);
    }

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("nope").contains("ops-team");
    verify(adapter, never()).send(any(), any());
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("notify 渠道不存在");
            });
  }

  @Test
  @DisplayName("渠道类型不受支持（非 webhook）→ 失败结果 + 可用渠道名（归扩展阶段按 channelType 加适配器）")
  void notify_unsupportedChannelType_failsWithAvailableNames() {
    NotifyChannelAdapter adapter = mock(NotifyChannelAdapter.class);
    NotifyChannelStore store =
        storeWith(new NotifyChannel("mail", "smtp", "smtp://mail.example.com", null));
    NotifyTools tools = new NotifyTools(mock(Sandbox.class), store, adapter);

    ListAppender<ILoggingEvent> appender = attachAppender();
    ToolResult result;
    try {
      result = tools.notify("告警", "mail");
    } finally {
      detachAppender(appender);
    }

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("smtp").contains("ops-team");
    verify(adapter, never()).send(any(), any());
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("notify 渠道类型不受支持");
            });
  }

  @Test
  @DisplayName("content 缺失 → 失败结果（参数非法返回结果，不抛异常）")
  void notify_missingContent_returnsFailureResult() {
    NotifyTools tools =
        new NotifyTools(mock(Sandbox.class), storeWith(OPS_TEAM), mock(NotifyChannelAdapter.class));

    ToolResult result = tools.notify(null, "ops-team");

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("content");
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(NotifyTools.class)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(NotifyTools.class)).detachAppender(appender);
  }
}
