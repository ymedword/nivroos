package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nivroos.tool.sandbox.Sandbox;
import com.nivroos.tool.sandbox.SandboxAction;
import com.nivroos.tool.sandbox.SandboxViolationException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

/** WebhookNotifyAdapter 验收点：颗粒度文档 §4.2（顺序断言 / payload / 白名单外不发出 / 非 2xx 不吞）。 */
class WebhookNotifyAdapterTest {

  private static final String URL = "https://qyapi.example.com/hook";

  @Test
  @DisplayName("notify 发送前必须先过 HTTP 域名白名单（顺序断言）")
  void send_enforcesWhitelistBeforeDispatch() throws Exception {
    Sandbox sandbox = mock(Sandbox.class);
    HttpClient client = clientReturning(200);
    NotifyChannelAdapter adapter = new WebhookNotifyAdapter(sandbox, client);

    adapter.send(new NotifyTarget("webhook", Map.of("url", URL)), "hello");

    InOrder inOrder = inOrder(sandbox, client);
    inOrder
        .verify(sandbox)
        .enforce(
            argThat(
                a -> a.type() == SandboxAction.ActionType.HTTP_REQUEST && a.target().equals(URL)));
    inOrder.verify(client).send(any(), any()); // 校验在前、发送在后
  }

  @Test
  @DisplayName("POST 到 target.config 里的 URL（非硬编码），body 含 content")
  void send_postsToUrlFromTargetWithContentInBody() throws Exception {
    HttpClient client = clientReturning(200);
    NotifyChannelAdapter adapter = new WebhookNotifyAdapter(mock(Sandbox.class), client);

    adapter.send(new NotifyTarget("webhook", Map.of("url", URL)), "今天的天气：晴 15℃");

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(captor.capture(), any());
    HttpRequest sent = captor.getValue();
    assertThat(sent.method()).isEqualTo("POST");
    assertThat(sent.uri().toString()).isEqualTo(URL);
    assertThat(sent.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(bodyOf(sent)).contains("content").contains("今天的天气：晴 15℃");
  }

  @Test
  @DisplayName("白名单外域名 → 请求未发出且异常上抛（被拒的动作没有真正发生）")
  void send_rejectedDomain_neverDispatches() throws Exception {
    Sandbox sandbox = mock(Sandbox.class);
    doThrow(new SandboxViolationException("domain not allowed: evil.com"))
        .when(sandbox)
        .enforce(any());
    HttpClient client = clientReturning(200);
    NotifyChannelAdapter adapter = new WebhookNotifyAdapter(sandbox, client);

    assertThatThrownBy(
            () -> adapter.send(new NotifyTarget("webhook", Map.of("url", "https://evil.com")), "x"))
        .isInstanceOf(SandboxViolationException.class);

    verify(client, never()).send(any(), any());
  }

  @Test
  @DisplayName("非 2xx → 异常上抛不吞，且落 WARN 日志")
  void send_non2xx_throwsAndLogsWarning() throws Exception {
    NotifyChannelAdapter adapter =
        new WebhookNotifyAdapter(mock(Sandbox.class), clientReturning(500));

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      assertThatThrownBy(
              () -> adapter.send(new NotifyTarget("webhook", Map.of("url", URL)), "hello"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("500");
    } finally {
      detachAppender(appender);
    }

    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("notify webhook non-2xx");
            });
  }

  @SuppressWarnings("unchecked")
  private static HttpClient clientReturning(int status) throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn("");
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    return client;
  }

  /** 取出 POST body：String publisher 同步完成，收集 ByteBuffer 后按 UTF-8 解码。 */
  private static String bodyOf(HttpRequest request) {
    List<ByteBuffer> chunks = new ArrayList<>();
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<ByteBuffer>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer item) {
                chunks.add(item);
              }

              @Override
              public void onError(Throwable throwable) {
                throw new IllegalStateException("读取请求体失败", throwable);
              }

              @Override
              public void onComplete() {}
            });
    ByteBuffer merged = ByteBuffer.allocate(chunks.stream().mapToInt(ByteBuffer::remaining).sum());
    chunks.forEach(merged::put);
    return StandardCharsets.UTF_8.decode(merged.flip()).toString();
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(WebhookNotifyAdapter.class)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(WebhookNotifyAdapter.class)).detachAppender(appender);
  }
}
