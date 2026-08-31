package com.nivroos.core.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolCallRequest;
import com.nivroos.core.model.ToolResult;
import com.nivroos.core.provider.ToolInvocationStore;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 工具执行验收点：颗粒度文档 §4.2/§4.3（FR-002 / FR-003）。 */
class ToolExecutorTest {

  private final ToolInvocationStore audit = mock(ToolInvocationStore.class);

  @Test
  @DisplayName("验收点：Sandbox 拒绝 → tool_invocations 落 success=false + 错误信息")
  void execute_sandboxRejected_recordsFailureAudit() {
    NivroTool tool = mock(NivroTool.class);
    doThrow(new RuntimeException("SandboxViolation: domain not allowed: evil.com (HTTP_REQUEST)"))
        .when(tool)
        .execute(any(JsonNode.class));
    ToolExecutor executor = new ToolExecutor(Map.of("http_get", tool), audit);

    ToolResult result =
        executor.execute("s-1", new ToolCallRequest("http_get", "{\"url\":\"https://evil.com\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("evil.com");
    verify(audit)
        .record(
            eq("s-1"), eq("http_get"), any(), isNull(), eq(false), contains("evil.com"), anyLong());
  }

  @Test
  @DisplayName("工具存在 → 执行并落 success=true")
  void execute_success_recordsAudit() {
    NivroTool tool = mock(NivroTool.class);
    when(tool.execute(any(JsonNode.class)))
        .thenReturn(new ToolResult(true, "{\"temp\":15}", null, false));
    ToolExecutor executor = new ToolExecutor(Map.of("http_get", tool), audit);

    ToolResult result =
        executor.execute("s-1", new ToolCallRequest("http_get", "{\"url\":\"https://wttr.in\"}"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("15");
    verify(audit)
        .record(
            eq("s-1"), eq("http_get"), any(), eq("{\"temp\":15}"), eq(true), isNull(), anyLong());
  }

  @Test
  @DisplayName("工具不存在 → 清晰错误且审计记失败")
  void execute_unknownTool_recordsFailure() {
    ToolExecutor executor = new ToolExecutor(Map.of(), audit);

    ToolResult result = executor.execute("s-1", new ToolCallRequest("no_such_tool", "{}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("no_such_tool");
    verify(audit)
        .record(
            eq("s-1"),
            eq("no_such_tool"),
            any(),
            isNull(),
            eq(false),
            contains("no_such_tool"),
            anyLong());
  }
}
