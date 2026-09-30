package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** HttpTools 验收点：颗粒度文档 §4.2（http_get 回归 + 新增 http_post）+ 契约 §3。 */
class HttpToolsTest {

  @SuppressWarnings("unchecked")
  private static HttpClient clientReturning(String body) throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(HttpURLConnection.HTTP_OK);
    when(response.body()).thenReturn(body);
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    return client;
  }

  private static Sandbox denyingSandbox() {
    Sandbox sandbox = mock(Sandbox.class);
    doThrow(new SandboxViolationException("domain not allowed: evil.com"))
        .when(sandbox)
        .enforce(any());
    return sandbox;
  }

  // ---------------------------------------------------------------- http_get（US-2 回归）

  @Test
  @DisplayName("http_get：白名单通过 → 发送 GET 并返回响应体")
  void httpGet_allowedUrl_returnsBody() throws Exception {
    Sandbox sandbox = mock(Sandbox.class);
    HttpTools tools = new HttpTools(sandbox, clientReturning("{\"temp\":15}"));

    var result = tools.httpGet("https://wttr.in/beijing");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("15");
    verify(sandbox)
        .enforce(
            new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, "https://wttr.in/beijing"));
  }

  @Test
  @DisplayName("http_get：域名白名单外 → 请求不发出（Sandbox 校验先行）")
  void httpGet_rejectedUrl_neverSendsRequest() throws Exception {
    HttpClient client = clientReturning("{}");
    HttpTools tools = new HttpTools(denyingSandbox(), client);

    assertThatThrownBy(() -> tools.httpGet("https://evil.com"))
        .isInstanceOf(SandboxViolationException.class);

    verify(client, never()).send(any(), any());
  }

  @Test
  @DisplayName("http_get：改标 @Tool 后工具名与参数名逐字不变（http_get / url）")
  void httpGet_keepsContractNamesAfterAnnotationSwitch() {
    Map<String, ToolCallback> callbacks = callbacksByToolName();

    assertThat(callbacks).containsKey("http_get");
    assertThat(callbacks.get("http_get").getToolDefinition().inputSchema()).contains("\"url\"");
    assertThat(callbacks.get("http_get").getToolDefinition().description()).contains("GET");
  }

  // ---------------------------------------------------------------- http_post（新增）

  @Test
  @DisplayName("http_post：方法 / URL / body 正确，Content-Type 为 application/json")
  @SuppressWarnings("unchecked")
  void httpPost_sendsJsonBodyToRequestedUrl() throws Exception {
    HttpClient client = clientReturning("{\"ok\":true}");
    HttpTools tools = new HttpTools(mock(Sandbox.class), client);

    var result = tools.httpPost("https://api.github.com/repos", "{\"name\":\"nivroos\"}");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("ok");

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    HttpRequest sent = captor.getValue();
    assertThat(sent.method()).isEqualTo("POST");
    assertThat(sent.uri().toString()).isEqualTo("https://api.github.com/repos");
    assertThat(sent.headers().firstValue("Content-Type")).contains("application/json");
  }

  @Test
  @DisplayName("http_post：域名白名单外 → 请求不发出")
  void httpPost_rejectedUrl_neverSendsRequest() throws Exception {
    HttpClient client = clientReturning("{}");
    HttpTools tools = new HttpTools(denyingSandbox(), client);

    assertThatThrownBy(() -> tools.httpPost("https://evil.com", "{}"))
        .isInstanceOf(SandboxViolationException.class);

    verify(client, never()).send(any(), any());
  }

  @Test
  @DisplayName("http_post：body 缺失 → 失败结果而非异常")
  void httpPost_missingBody_returnsFailureResult() {
    HttpTools tools = new HttpTools(mock(Sandbox.class), mock(HttpClient.class));

    var result = tools.httpPost("https://api.github.com/repos", null);

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("body");
  }

  // ---------------------------------------------------------------- 失败不吞

  @Test
  @DisplayName("传输失败：异常上抛且落 WARN 日志（不吞）")
  void httpGet_transportFailure_throwsAndLogsWarning() throws Exception {
    HttpClient client = mock(HttpClient.class);
    when(client.send(any(), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new java.io.IOException("connection reset"));
    HttpTools tools = new HttpTools(mock(Sandbox.class), client);

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      assertThatThrownBy(() -> tools.httpGet("https://wttr.in/beijing"))
          .isInstanceOf(SandboxViolationException.class)
          .hasMessageContaining("connection reset");
    } finally {
      detachAppender(appender);
    }

    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("http_get failed");
            });
  }

  private Map<String, ToolCallback> callbacksByToolName() {
    HttpTools tools = new HttpTools(mock(Sandbox.class), mock(HttpClient.class));
    return java.util.Arrays.stream(
            MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
        .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), cb -> cb));
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(HttpTools.class)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(HttpTools.class)).detachAppender(appender);
  }
}
