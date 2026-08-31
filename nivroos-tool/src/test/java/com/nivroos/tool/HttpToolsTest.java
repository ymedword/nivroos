package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolResult;
import com.nivroos.tool.sandbox.Sandbox;
import com.nivroos.tool.sandbox.SandboxAction;
import com.nivroos.tool.sandbox.SandboxViolationException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HTTP 工具验收点：颗粒度文档 §4.2（FR-003 白名单先行）。 */
class HttpToolsTest {

  @Test
  @DisplayName("白名单通过：发送 GET 并返回响应体")
  @SuppressWarnings("unchecked")
  void execute_allowedUrl_returnsBody() throws Exception {
    Sandbox sandbox = mock(Sandbox.class);
    HttpClient client = mock(HttpClient.class);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"temp\":15}");
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    HttpTools tools = new HttpTools(sandbox, client);

    NivroTool httpGet = tools.httpGet();
    ToolResult result = httpGet.execute(jsonWithUrl("https://wttr.in/beijing"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("15");
    verify(sandbox)
        .enforce(
            new SandboxAction(SandboxAction.ActionType.HTTP_REQUEST, "https://wttr.in/beijing"));
  }

  @Test
  @DisplayName("Sandbox 校验先行：拒绝时根本不发起请求")
  @SuppressWarnings("unchecked")
  void execute_rejectedUrl_neverSendsRequest() throws Exception {
    Sandbox sandbox = mock(Sandbox.class);
    org.mockito.Mockito.doThrow(new SandboxViolationException("domain not allowed: evil.com"))
        .when(sandbox)
        .enforce(any());
    HttpClient client = mock(HttpClient.class);
    HttpTools tools = new HttpTools(sandbox, client);

    org.junit.jupiter.api.Assertions.assertThrows(
        SandboxViolationException.class,
        () -> tools.httpGet().execute(jsonWithUrl("https://evil.com")));

    verify(client, org.mockito.Mockito.never()).send(any(), any());
  }

  private static com.fasterxml.jackson.databind.JsonNode jsonWithUrl(String url) {
    ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    node.put("url", url);
    return node;
  }
}
