package com.nivroos.tool;

import com.nivroos.core.model.ToolResult;
import com.nivroos.tool.sandbox.Sandbox;
import com.nivroos.tool.sandbox.SandboxAction;
import com.nivroos.tool.sandbox.SandboxViolationException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置 HTTP 工具（技术方案 §6.2、契约 contracts/builtin-tools.md §3）。
 *
 * <p>US-4 switches registration to {@code @Tool} annotations, keeping the tool name and parameter
 * names of {@code http_get} verbatim (AGENT.md references them by name), and adds {@code
 * http_post}. JDK HttpClient keeps the dependency count at zero and the call synchronous, which the
 * virtual thread model wants. Sandbox enforcement runs first inside each tool (constitution VI), so
 * a rejected domain never reaches the network.
 */
public class HttpTools {

  private static final Logger log = LoggerFactory.getLogger(HttpTools.class);

  /** 连接与读取超时（research §4 默认 10s）。 */
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final Sandbox sandbox;
  private final HttpClient client;

  /** 构造函数注入 HttpClient，便于测试替换（颗粒度文档 T012）。 */
  public HttpTools(Sandbox sandbox, HttpClient client) {
    this.sandbox = sandbox;
    this.client = client;
  }

  /** GET 请求（工具名与参数名逐字沿用 US-2）。 */
  @Tool(name = "http_get", description = "发起 GET 请求获取网页或 API 内容（受域名白名单约束）")
  public ToolResult httpGet(@ToolParam(description = "目标 URL") String url) {
    // 首步校验：白名单外域名连请求都不发（契约 §3）
    sandbox.enforce(new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, url));
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
    return send(request, "http_get", url);
  }

  /** POST 请求：请求体按 JSON 提交（渠道专用格式归扩展阶段）。 */
  @Tool(name = "http_post", description = "发起 POST 请求提交 JSON 数据（受域名白名单约束）")
  public ToolResult httpPost(
      @ToolParam(description = "目标 URL") String url,
      @ToolParam(description = "请求体（JSON 文本）") String body) {
    if (body == null) {
      // 业务性失败：参数缺失返回失败结果，不抛异常（契约 §0.2）
      return new ToolResult(false, null, "http_post 需要 body 参数", false);
    }
    sandbox.enforce(new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, url));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return send(request, "http_post", url);
  }

  /**
   * 同步发送并映射结果；异常上抛由既有 ToolExecutor 落失败审计。
   *
   * <p>Wrapped as SandboxViolationException for both rejection and transport failure - that is
   * US-2's existing semantics for this class, kept unchanged so the {@code http_get} regression
   * stays byte-for-byte (a failure-type split would belong in its own change).
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "url 已过 sanitizeForLog（CR/LF 剥离）；findsecbugs 不识别自定义消毒方法，异常对象仅作堆栈附加")
  private ToolResult send(HttpRequest request, String toolName, String url) {
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      log.info("{} ok: url={}, status={}", toolName, sanitizeForLog(url), response.statusCode());
      return new ToolResult(true, response.body(), null, false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("{} interrupted: url={}", toolName, sanitizeForLog(url), e);
      throw new SandboxViolationException(toolName + " interrupted: " + url);
    } catch (Exception e) {
      log.warn("{} failed: url={}", toolName, sanitizeForLog(url), e);
      throw new SandboxViolationException(toolName + " failed: " + url + " - " + e.getMessage());
    }
  }

  /** 日志参数 CRLF 消毒：URL 来自模型输出，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
