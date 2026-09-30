package com.nivroos.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nivroos.tool.sandbox.Sandbox;
import com.nivroos.tool.sandbox.SandboxAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用 webhook 通知适配器（技术方案 §6.8 核心阶段唯一实现；契约 contracts/mcp-and-notify.md §4）。
 *
 * <p>Enterprise IM group robots all expose a webhook URL, so one generic POST covers WeCom / Feishu
 * / DingTalk for the core phase without their signing and AccessToken machinery. The whitelist
 * check runs before dispatch and shares {@code http.allowed_domains} with the HTTP tools - no new
 * sandbox concept, and a URL outside the whitelist never reaches the network.
 */
public class WebhookNotifyAdapter implements NotifyChannelAdapter {

  private static final Logger log = LoggerFactory.getLogger(WebhookNotifyAdapter.class);

  /** 投递超时：与 HTTP 工具同量级（通知是尽力而为，不长时间挂住 ReAct 循环）。 */
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Sandbox sandbox;
  private final HttpClient client;

  /** 构造函数注入 HttpClient，便于测试替换（同 HttpTools 先例）。 */
  public WebhookNotifyAdapter(Sandbox sandbox, HttpClient client) {
    this.sandbox = sandbox;
    this.client = client;
  }

  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "url 已过 sanitizeForLog（CR/LF 剥离）；findsecbugs 不识别自定义消毒方法，异常对象仅作堆栈附加")
  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    // 顺序即安全边界：白名单校验必须先于发送发生（契约 §4 顺序要求；顺序断言是关键回归）
    sandbox.enforce(new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, url));

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload(content)))
            .build();
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        // 非 2xx 是发送失败：抛异常让上层落失败审计 + WARN（不吞）
        log.warn(
            "notify webhook non-2xx: url={}, status={}",
            sanitizeForLog(url),
            response.statusCode());
        throw new IllegalStateException(
            "webhook 返回非 2xx：" + response.statusCode() + "（" + url + "）");
      }
      log.info("notify sent: url={}, status={}", sanitizeForLog(url), response.statusCode());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("notify interrupted: url={}", sanitizeForLog(url), e);
      throw new IllegalStateException("notify interrupted: " + url);
    } catch (IOException e) {
      log.warn("notify failed: url={}", sanitizeForLog(url), e);
      throw new IllegalStateException("notify failed: " + url + " - " + e.getMessage());
    }
  }

  /** 通用 payload `{"content": "<文本>"}`（契约 §4；其余渠道的格式差异归扩展阶段按 channelType 加适配器）。 */
  private static String payload(String content) {
    try {
      return MAPPER.writeValueAsString(Map.of("content", content));
    } catch (JsonProcessingException e) {
      // 序列化 String 不会失败：真出现说明 ObjectMapper 配置被破坏，大声失败
      throw new IllegalStateException("notify payload 序列化失败", e);
    }
  }

  /** 日志参数 CRLF 消毒：URL 来自注册表但可能由运维手填，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
