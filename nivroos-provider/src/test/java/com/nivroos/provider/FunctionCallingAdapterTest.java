package com.nivroos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.nivroos.core.model.JsonSchema;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 工具翻译验收点：颗粒度文档 §4.2（翻译字段一一对应、产物不含执行逻辑）。 */
class FunctionCallingAdapterTest {

  private static final NivroTool HTTP_GET_TOOL =
      new NivroTool() {
        @Override
        public String getName() {
          return "http_get";
        }

        @Override
        public String getDescription() {
          return "发起 GET 请求";
        }

        @Override
        public JsonSchema getInputSchema() {
          return new JsonSchema(
              "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}");
        }

        @Override
        public ToolResult execute(JsonNode input) {
          return new ToolResult(true, "ok", null, false);
        }
      };

  @Test
  @DisplayName("NivroTool 翻译为工具回调后字段一一对应")
  void translatesFieldByField() {
    var callbacks = new FunctionCallingAdapter().fromTools(List.of(HTTP_GET_TOOL));

    assertThat(callbacks).hasSize(1);
    var definition = callbacks.get(0).getToolDefinition();
    assertThat(definition.name()).isEqualTo("http_get");
    assertThat(definition.description()).isEqualTo("发起 GET 请求");
    assertThat(definition.inputSchema()).contains("\"url\"");
  }

  @Test
  @DisplayName("翻译产物不含执行逻辑：回调的执行方法必须拒绝被调用")
  void callbackExecution_rejected() {
    var callbacks = new FunctionCallingAdapter().fromTools(List.of(HTTP_GET_TOOL));

    assertThatThrownBy(() -> callbacks.get(0).call("{}"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
