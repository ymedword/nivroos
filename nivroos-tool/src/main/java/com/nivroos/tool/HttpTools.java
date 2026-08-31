package com.nivroos.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.nivroos.core.model.JsonSchema;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolResult;
import com.nivroos.tool.sandbox.Sandbox;
import com.nivroos.tool.sandbox.SandboxAction;
import com.nivroos.tool.sandbox.SandboxViolationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内置 HTTP 工具（技术方案 §6.2；US-2 交付 http_get，http_post 归 US-4）。
 *
 * <p>Uses the JDK HttpClient (zero new third-party dependencies, synchronous send, virtual-thread
 * friendly). Sandbox enforcement runs first inside the tool itself (constitution §6.2); violations
 * surface as SandboxViolationException and are recorded as failed tool invocations by ToolExecutor.
 */
public class HttpTools {

  private static final Logger log = LoggerFactory.getLogger(HttpTools.class);

  /** 连接与读取超时（research §4 默认 10s）。 */
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final Sandbox sandbox;
  private final HttpClient client;

  /** 构造函数注入 HttpClient，便于测试替换（颗粒度文档 T014/T019）。 */
  public HttpTools(Sandbox sandbox, HttpClient client) {
    this.sandbox = sandbox;
    this.client = client;
  }

  public NivroTool httpGet() {
    return new HttpGetTool();
  }

  /** 日志参数 CRLF 消毒：URL 来自模型输出，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }

  private final class HttpGetTool implements NivroTool {

    @Override
    public String getName() {
      return "http_get";
    }

    @Override
    public String getDescription() {
      return "发起 GET 请求获取网页或 API 内容（受域名白名单约束）";
    }

    @Override
    public JsonSchema getInputSchema() {
      return new JsonSchema(
          "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"目标 URL\"}},\"required\":[\"url\"]}");
    }

    @Override
    public ToolResult execute(JsonNode input) {
      String url = input.path("url").asText();
      // Sandbox 校验先行（宪法原则六），拒绝时异常由 ToolExecutor 转失败审计
      sandbox.enforce(new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, url));

      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
      try {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("http_get ok: url={}, status={}", sanitizeForLog(url), response.statusCode());
        return new ToolResult(true, response.body(), null, false);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SandboxViolationException("http_get interrupted: " + url);
      } catch (Exception e) {
        throw new SandboxViolationException("http_get failed: " + url + " - " + e.getMessage());
      }
    }
  }
}
