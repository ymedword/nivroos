package com.nivroos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Mem0MemoryStore 验收点：颗粒度文档 §4.2（add/get/search 映射正确，mock HTTP，不碰真实网络）。
 *
 * <p>凭证缺失与明文拒绝由 {@code MemoryPropertiesTest} 承接（校验归 MemoryProperties，store 只管 HTTP 映射）——颗粒度文档 §4.2
 * 把该项挂在 Mem0 类下，实际落点见 T023。Base URL 语义：OSS 自托管 **无 {@code /v1} 前缀**（research §3），断言逐条守住这一点。
 */
class Mem0MemoryStoreTest {

  private static final String BASE_URL = "http://localhost:8000";

  @SuppressWarnings("unchecked")
  private static HttpResponse<String> response(int status, String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    return response;
  }

  @SuppressWarnings("unchecked")
  private static HttpClient clientReturning(HttpResponse<String>... responses) throws Exception {
    HttpClient client = mock(HttpClient.class);
    if (responses.length == 1) {
      when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(responses[0]);
    } else {
      when(client.send(any(), any(HttpResponse.BodyHandler.class)))
          .thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
    }
    return client;
  }

  private static Mem0MemoryStore store(HttpClient client, int archiveMaxChars) {
    return new Mem0MemoryStore(client, BASE_URL, "test-key", archiveMaxChars);
  }

  /** 读出已发出请求的 body（BodyPublishers.ofString 同步投递，无需异步等待）。 */
  private static String bodyOf(HttpRequest request) {
    HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
    StringBuilder body = new StringBuilder();
    publisher.subscribe(
        new Flow.Subscriber<ByteBuffer>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(ByteBuffer item) {
            body.append(StandardCharsets.UTF_8.decode(item));
          }

          @Override
          public void onError(Throwable throwable) {
            // 测试辅助：投递失败会让断言自然失败，无需额外处理
          }

          @Override
          public void onComplete() {
            // 无需处理
          }
        });
    return body.toString();
  }

  private static HttpRequest captureRequest(HttpClient client) throws Exception {
    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    org.mockito.Mockito.verify(client).send(captor.capture(), any());
    return captor.getValue();
  }

  // ---------------------------------------------------------------- append → POST /memories

  @Test
  @DisplayName("append → POST /memories（无 /v1 前缀），body 带 messages/user_id/metadata.scope")
  void append_postsToMemories() throws Exception {
    HttpClient client = clientReturning(response(200, "{\"id\":\"m-1\"}"));
    Mem0MemoryStore store = store(client, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    store.append("用户项目使用 Spring Boot", com.nivroos.core.memory.MemoryScope.CORE);

    HttpRequest request = captureRequest(client);
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.uri().toString()).isEqualTo(BASE_URL + "/memories");
    assertThat(request.headers().firstValue("X-API-Key")).contains("test-key");
    assertThat(bodyOf(request))
        .contains("用户项目使用 Spring Boot")
        .contains("\"role\":\"user\"")
        .contains("\"metadata\"")
        .contains("CORE");
  }

  @Test
  @DisplayName("append 未指定分区 → metadata.scope 落 ARCHIVAL")
  void append_nullScope_defaultsToArchival() throws Exception {
    HttpClient client = clientReturning(response(200, "{}"));
    Mem0MemoryStore store = store(client, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    store.append("随手记一条", null);

    assertThat(bodyOf(captureRequest(client))).contains("ARCHIVAL");
  }

  @Test
  @DisplayName("append 非 2xx → 抛错（交由 ToolExecutor 落失败审计）")
  void append_nonSuccessStatus_throws() throws Exception {
    HttpClient client = clientReturning(response(500, "internal error"));
    Mem0MemoryStore store = store(client, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    assertThatThrownBy(() -> store.append("写不进去", com.nivroos.core.memory.MemoryScope.ARCHIVAL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("500");
  }

  @Test
  @DisplayName("append 网络异常 → UncheckedIOException 上抛")
  void append_ioFailure_throwsUnchecked() throws Exception {
    HttpClient client = mock(HttpClient.class);
    when(client.send(any(), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("connect refused"));
    Mem0MemoryStore store = store(client, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    assertThatThrownBy(() -> store.append("写不进去", com.nivroos.core.memory.MemoryScope.ARCHIVAL))
        .isInstanceOf(UncheckedIOException.class);
  }

  // ---------------------------------------------------------------- load → GET /memories

  @Test
  @DisplayName("load → GET /memories（无 /v1 前缀），核心区在前、归档区在后")
  void load_getsMemoriesWithSections() throws Exception {
    String payload =
        "[{\"id\":\"1\",\"memory\":\"核心偏好：Spring Boot\",\"metadata\":{\"scope\":\"CORE\"}},"
            + "{\"id\":\"2\",\"memory\":\"上次讨论过 SQLite\",\"metadata\":{\"scope\":\"ARCHIVAL\"}}]";
    HttpClient client = clientReturning(response(200, payload));
    Mem0MemoryStore store = store(client, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    String loaded = store.load();

    HttpRequest request = captureRequest(client);
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.uri().toString()).startsWith(BASE_URL + "/memories");
    assertThat(loaded).contains("核心偏好：Spring Boot").contains("上次讨论过 SQLite");
    assertThat(loaded.indexOf("核心偏好：Spring Boot")).isLessThan(loaded.indexOf("上次讨论过 SQLite"));
  }

  @Test
  @DisplayName("load 归档区按预算截断：保留最新")
  void load_archivalTruncation_keepsNewest() throws Exception {
    String payload =
        "[{\"id\":\"1\",\"memory\":\"归档条目 1\",\"metadata\":{\"scope\":\"ARCHIVAL\"}},"
            + "{\"id\":\"2\",\"memory\":\"归档条目 2\",\"metadata\":{\"scope\":\"ARCHIVAL\"}},"
            + "{\"id\":\"3\",\"memory\":\"归档条目 3\",\"metadata\":{\"scope\":\"ARCHIVAL\"}}]";
    HttpClient client = clientReturning(response(200, payload));
    Mem0MemoryStore store = store(client, 12); // 只装得下最新一条

    String loaded = store.load();

    assertThat(loaded).contains("归档条目 3").doesNotContain("归档条目 1");
  }

  @Test
  @DisplayName("load 空结果 → 空串（与文件/库两档同语义）")
  void load_emptyResult_returnsEmpty() throws Exception {
    HttpClient client = clientReturning(response(200, "[]"));
    Mem0MemoryStore store = store(client, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    assertThat(store.load()).isEmpty();
  }

  @Test
  @DisplayName("load 非 2xx → 抛错（门面按「本轮无长期记忆」降级）")
  void load_nonSuccessStatus_throws() throws Exception {
    HttpClient client = clientReturning(response(503, "service unavailable"));
    Mem0MemoryStore store = store(client, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    assertThatThrownBy(store::load).isInstanceOf(IllegalStateException.class);
  }

  // ---------------------------------------------------------------- recallByKeyword → POST /search

  @Test
  @DisplayName("recallByKeyword → POST /search，返回命中的记忆正文")
  void recallByKeyword_postsToSearch() throws Exception {
    String payload =
        "{\"results\":[{\"id\":\"2\",\"memory\":\"上次讨论过 SQLite\","
            + "\"metadata\":{\"scope\":\"ARCHIVAL\"},\"score\":0.82}]}";
    HttpClient client = clientReturning(response(200, payload));
    Mem0MemoryStore store = store(client, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    java.util.List<String> hits = store.recallByKeyword("SQLite");

    HttpRequest request = captureRequest(client);
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.uri().toString()).isEqualTo(BASE_URL + "/search");
    assertThat(bodyOf(request)).contains("SQLite");
    assertThat(hits).containsExactly("上次讨论过 SQLite");
  }

  @Test
  @DisplayName("recallByKeyword 过滤核心区结果（FR-009：核心区不参与检索）")
  void recallByKeyword_filtersCoreResults() throws Exception {
    String payload =
        "{\"results\":["
            + "{\"id\":\"1\",\"memory\":\"核心偏好：Spring Boot\",\"metadata\":{\"scope\":\"CORE\"},\"score\":0.9},"
            + "{\"id\":\"2\",\"memory\":\"归档里的 Spring 版本\",\"metadata\":{\"scope\":\"ARCHIVAL\"},\"score\":0.8}]}";
    HttpClient client = clientReturning(response(200, payload));
    Mem0MemoryStore store = store(client, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    assertThat(store.recallByKeyword("Spring")).containsExactly("归档里的 Spring 版本");
  }

  @Test
  @DisplayName("recallByKeyword 空关键词 → 空列表，不发请求")
  void recallByKeyword_blankQuery_returnsEmptyWithoutCall() {
    HttpClient client;
    try {
      client = clientReturning(response(200, "{}"));
    } catch (Exception e) {
      throw new UncheckedIOException(new IOException(e));
    }
    Mem0MemoryStore store = store(client, MemoryProperties.DEFAULT_ARCHIVE_MAX_CHARS);

    assertThat(store.recallByKeyword("   ")).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(client);
  }
}
