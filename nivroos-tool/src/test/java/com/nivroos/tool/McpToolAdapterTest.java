package com.nivroos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/** McpToolAdapter 验收点：颗粒度文档 §4.2（名称/描述/schema 映射、参数转发、isError 映射）。 */
class McpToolAdapterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("McpSchema.Tool → NivroTool：名称 / 描述 / schema 字符串逐项映射")
  void mapping_nameDescriptionAndSchema_comeFromServerTool() {
    NivroTool tool =
        new McpToolAdapter("github-mcp", toolNamed("search_prs"), mock(McpSyncClient.class));

    assertThat(tool.getName()).isEqualTo("search_prs");
    assertThat(tool.getDescription()).isEqualTo("检索 PR");
    assertThat(tool.getInputSchema().value())
        .contains("\"type\":\"object\"")
        .contains("\"q\"")
        .contains("\"required\":[\"q\"]");
  }

  @Test
  @DisplayName("SDK 未给 schema → 补空对象 schema（schema 为空会拖垮全部 LLM 调用）")
  void getInputSchema_missingSdkSchema_fallsBackToEmptyObjectSchema() {
    McpSchema.Tool bare = McpSchema.Tool.builder().name("no-schema").build();

    NivroTool tool = new McpToolAdapter("local-mcp", bare, mock(McpSyncClient.class));

    assertThat(tool.getInputSchema().value())
        .contains("\"type\":\"object\"")
        .contains("properties");
  }

  @Test
  @DisplayName("execute → callTool 转发：工具名一致、入参拆成纯 Map（不夹带 Jackson 2 节点）")
  void execute_forwardsToolNameAndPlainArguments() {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.callTool(any())).thenReturn(result("ok"));
    NivroTool tool = new McpToolAdapter("github-mcp", toolNamed("search_prs"), client);

    ObjectNode input = MAPPER.createObjectNode().put("q", "is:open").put("limit", 10);
    input.putObject("filter").put("state", "open");
    input.putArray("labels").add("bug").add("p1");

    ToolResult result = tool.execute(input);

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("ok");
    ArgumentCaptor<McpSchema.CallToolRequest> captor =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(client).callTool(captor.capture());
    assertThat(captor.getValue().name()).isEqualTo("search_prs");
    assertThat(captor.getValue().arguments()).containsEntry("q", "is:open");
    assertThat(captor.getValue().arguments().get("filter")).isEqualTo(Map.of("state", "open"));
    assertThat(captor.getValue().arguments().get("labels")).isEqualTo(List.of("bug", "p1"));
    // 桥接判定：交给 SDK 的值必须是纯 Java 类型（Jackson 3 序列化不了 Jackson 2 的节点）
    assertThat(captor.getValue().arguments().values()).noneMatch(JsonNode.class::isInstance);
  }

  @Test
  @DisplayName("MCP 工具返回 isError=true → success=false，错误文本回填给模型")
  void execute_mcpToolReturnsError_mappedToFailure() {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.callTool(any()))
        .thenReturn(
            McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("upstream 403: rate limited")))
                .isError(true)
                .build());
    NivroTool tool = new McpToolAdapter("github-mcp", toolNamed("search_prs"), client);

    ListAppender<ILoggingEvent> appender = attachAppender();
    ToolResult result;
    try {
      result = tool.execute(MAPPER.createObjectNode().put("q", "is:open"));
    } finally {
      detachAppender(appender);
    }

    assertThat(result.success()).isFalse();
    assertThat(result.content() == null ? result.errorMessage() : result.content()).contains("403");
    // 失败路径同样落 WARN（可观测性双轨：审计在 ToolExecutor，运行日志在本类）
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("search_prs").contains("403");
            });
  }

  private static McpSchema.CallToolResult result(String text) {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(text)))
        .isError(false)
        .build();
  }

  private static McpSchema.Tool toolNamed(String name) {
    return McpSchema.Tool.builder()
        .name(name)
        .description("检索 PR")
        .inputSchema(
            new McpSchema.JsonSchema(
                "object",
                Map.of("q", Map.of("type", "string")),
                List.of("q"),
                Boolean.FALSE,
                null,
                null))
        .build();
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(McpToolAdapter.class)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(McpToolAdapter.class)).detachAppender(appender);
  }
}
