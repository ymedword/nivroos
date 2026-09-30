package com.nivroos.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nivroos.core.memory.LongTermMemoryStore;
import com.nivroos.core.memory.MemoryScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mem0 自托管后端（{@code memory.backend: mem0}，技术方案 §5.1）。
 *
 * <p>Talks to a self-hosted Mem0 OSS REST server with the JDK {@link HttpClient} (zero new
 * third-party dependencies, synchronous send, virtual-thread friendly). Credentials and address
 * come from {@code memory.mem0.*}; the credential rule (placeholder only, plaintext rejected) is
 * enforced by {@link MemoryProperties} at wiring time - this class does HTTP mapping and nothing
 * else.
 *
 * <p><b>三条语义差异（选型固有代价，非缺陷，research §3 逐条登记）</b>：
 *
 * <p>1. {@code POST /memories} 会做 **LLM 抽取**，落库的是抽取后的条目而非原文——因此本档的 {@code append} **不是逐字追加**；2.
 * {@code POST /search} 是**语义检索**（结果带 score），与 markdown/sqlite 两档的**关键词匹配**语义不同，这是技术方案 §5.1
 * 预留的升级方向；3. 精确路径与 字段随 OSS 版本演进，**实际联调以部署实例的 {@code /openapi.json} 为准**（人工项）。
 *
 * <p>Path note: the OSS self-hosted server has **no {@code /v1} prefix** (that belongs to the
 * hosted platform {@code api.mem0.ai}, which would send data out of the enterprise - forbidden by
 * §5.1).
 */
public class Mem0MemoryStore implements LongTermMemoryStore {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 部署级单份记忆的归属标识（与 markdown/sqlite 两档「单文件/单表」语义一致）。 */
  private static final String USER_ID = "nivroos";

  private static final String PATH_MEMORIES = "/memories";
  private static final String PATH_SEARCH = "/search";
  private static final String SCOPE_FIELD = "scope";
  private static final String MEMORY_FIELD = "memory";
  private static final String API_KEY_HEADER = "X-API-Key";
  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVAL_HEADER = "## 归档记忆";
  private static final String BULLET = "- ";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** 错误信息里回显的响应体上限（避免把整页错误 HTML 灌进审计表和日志）。 */
  private static final int ERROR_BODY_LIMIT = 200;

  private final HttpClient client;
  private final String baseUrl;
  private final String apiKey;
  private final int archiveMaxChars;

  /** 构造函数注入 HttpClient，便于测试替换（同 HttpTools 先例，不碰真实网络）。 */
  public Mem0MemoryStore(HttpClient client, String baseUrl, String apiKey, int archiveMaxChars) {
    this.client = client;
    this.baseUrl =
        baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl; // 容忍尾斜杠
    this.apiKey = apiKey;
    this.archiveMaxChars = archiveMaxChars;
  }

  /**
   * 追加一条记忆：{@code POST /memories}，分区放在 {@code metadata.scope}。
   *
   * <p>注意 Mem0 服务端会做 LLM 抽取，落库内容可能与传入正文不同（类注释差异 1）。
   */
  @Override
  public void append(String content, MemoryScope scope) {
    MemoryScope target = scope == null ? MemoryScope.ARCHIVAL : scope; // 缺省 ARCHIVAL（FR-006）
    ObjectNode body = MAPPER.createObjectNode();
    body.putArray("messages").addObject().put("role", "user").put("content", content);
    body.put("user_id", USER_ID);
    body.putObject("metadata").put(SCOPE_FIELD, target.name());
    send(post(PATH_MEMORIES, body.toString()), "append");
  }

  /** 读取：核心区全量 + 归档区按字符预算保留最新，核心在前、归档在后。 */
  @Override
  public String load() {
    JsonNode root = readTree(send(get(), "load"), "load");
    List<String> core = new ArrayList<>();
    List<String> archival = new ArrayList<>();
    for (JsonNode item : itemsOf(root)) {
      String text = item.path(MEMORY_FIELD).asText("");
      if (text.isBlank()) {
        continue;
      }
      if (MemoryScope.CORE.name().equals(item.path("metadata").path(SCOPE_FIELD).asText(null))) {
        core.add(text);
      } else {
        archival.add(text); // 分区缺失视同 ARCHIVAL，与 markdown/sqlite 两档同语义
      }
    }
    return render(core, truncateKeepingNewest(archival));
  }

  /**
   * 关键词检索：{@code POST /search}，随后在客户端剔除核心区结果（FR-009）。
   *
   * <p>服务端是语义检索（类注释差异 2）——命中范围比 md/sqlite 两档宽，但「核心区不参与检索」这 条门面契约必须由客户端补齐，否则三档行为会漂移。
   */
  @Override
  public List<String> recallByKeyword(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    String body = MAPPER.createObjectNode().put("query", query).put("user_id", USER_ID).toString();
    JsonNode root = readTree(send(post(PATH_SEARCH, body), "recall"), "recall");
    List<String> hits = new ArrayList<>();
    for (JsonNode item : itemsOf(root)) {
      String text = item.path(MEMORY_FIELD).asText("");
      if (text.isBlank()
          || MemoryScope.CORE.name().equals(item.path("metadata").path(SCOPE_FIELD).asText(null))) {
        continue;
      }
      hits.add(text);
    }
    return hits;
  }

  private HttpRequest get() {
    return HttpRequest.newBuilder(URI.create(baseUrl + PATH_MEMORIES + "?user_id=" + USER_ID))
        .header(API_KEY_HEADER, apiKey)
        .timeout(TIMEOUT)
        .GET()
        .build();
  }

  private HttpRequest post(String path, String body) {
    return HttpRequest.newBuilder(URI.create(baseUrl + path))
        .header(API_KEY_HEADER, apiKey)
        .header("Content-Type", "application/json")
        .timeout(TIMEOUT)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
  }

  /**
   * 发送请求并返回响应体；非 2xx 抛 {@link IllegalStateException}，网络异常抛 {@link UncheckedIOException}。
   *
   * <p>Failures are never swallowed: the facade decides between degrading (reads) and letting
   * ToolExecutor audit the failure (writes).
   */
  private String send(HttpRequest request, String operation) {
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "mem0 "
                + operation
                + " failed: HTTP "
                + response.statusCode()
                + " - "
                + snippet(response.body()));
      }
      return response.body();
    } catch (IOException e) {
      throw new UncheckedIOException("mem0 " + operation + " failed: service unreachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // 恢复中断标志，不吞（原则七：同步阻塞模型）
      throw new UncheckedIOException(
          "mem0 " + operation + " interrupted", new IOException(e.getMessage(), e));
    }
  }

  private static JsonNode readTree(String body, String operation) {
    try {
      return MAPPER.readTree(body);
    } catch (IOException e) {
      throw new UncheckedIOException("mem0 " + operation + " failed: malformed response", e);
    }
  }

  /** 响应条目：数组直接取；对象则取 {@code results}（{@code /search} 的包壳）。 */
  private static JsonNode itemsOf(JsonNode root) {
    if (root != null && root.isArray()) {
      return root;
    }
    return root == null ? MAPPER.createArrayNode() : root.path("results");
  }

  /**
   * 归档区取最新若干条：倒序取到字符预算耗尽为止，再恢复时间正序（同 sqlite 档语义）。
   *
   * <p>服务端按创建顺序返回，故从末尾向前取即「保留最新」；最新一条即使超预算也保留，避免记忆 凭空消失。
   */
  private List<String> truncateKeepingNewest(List<String> entries) {
    List<String> kept = new ArrayList<>();
    int used = 0;
    for (int i = entries.size() - 1; i >= 0; i--) {
      String entry = entries.get(i);
      int cost = entry.length() + BULLET.length();
      if (used + cost > archiveMaxChars && !kept.isEmpty()) {
        break;
      }
      kept.add(entry);
      used += cost;
    }
    Collections.reverse(kept);
    return kept;
  }

  private static String render(List<String> core, List<String> archival) {
    if (core.isEmpty() && archival.isEmpty()) {
      return ""; // 无记忆 = 空串（三档同语义，FR-020）
    }
    StringBuilder out = new StringBuilder();
    appendSection(out, CORE_HEADER, core);
    appendSection(out, ARCHIVAL_HEADER, archival);
    return out.toString();
  }

  private static void appendSection(StringBuilder out, String header, List<String> entries) {
    if (entries.isEmpty()) {
      return;
    }
    if (!out.isEmpty()) {
      out.append("\n\n");
    }
    out.append(header);
    for (String entry : entries) {
      out.append("\n\n").append(BULLET).append(entry);
    }
  }

  /** 响应体摘要：截断 + 剥离 CR/LF，防止错误信息与原样响应体污染日志行（findsecbugs）。 */
  private static String snippet(String body) {
    if (body == null) {
      return "";
    }
    String flat = body.replace('\r', ' ').replace('\n', ' ');
    return flat.length() <= ERROR_BODY_LIMIT ? flat : flat.substring(0, ERROR_BODY_LIMIT) + "...";
  }
}
