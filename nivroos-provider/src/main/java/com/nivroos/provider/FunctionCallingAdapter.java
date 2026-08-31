package com.nivroos.provider;

import com.nivroos.core.model.NivroTool;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具格式翻译（宪法原则二：只翻译，不执行）。
 *
 * <p>Translates NivroTool definitions into the vendor protocol format (Spring AI ToolCallback). The
 * callbacks carry schema/description only - their execution method is never invoked: the model's
 * auto tool execution is disabled on every request, and the real execution belongs to ToolExecutor
 * (US-2).
 */
public class FunctionCallingAdapter {

  /** 把工具定义列表翻译为供应商协议格式（只翻译）。 */
  public List<ToolCallback> fromTools(List<NivroTool> tools) {
    return tools.stream().map(this::toCallback).toList();
  }

  private ToolCallback toCallback(NivroTool tool) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name(tool.getName())
            .description(tool.getDescription())
            .inputSchema(tool.getInputSchema().value())
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        throw new UnsupportedOperationException(
            "Tool execution is disabled in the provider layer; tools are executed by ToolExecutor (constitution)");
      }
    };
  }
}
