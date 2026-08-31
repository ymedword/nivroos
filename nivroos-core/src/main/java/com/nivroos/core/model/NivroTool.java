package com.nivroos.core.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 所有 Tool 的统一抽象（技术方案 §6.1）。
 *
 * <p>Common abstraction for every tool; built-in tools, {@code @Tool} plugin tools and MCP tools
 * are all wrapped as NivroTool. The provider layer only translates this interface into vendor
 * protocol formats - it never executes {@link #execute} (constitution: tool execution belongs to
 * ToolExecutor).
 */
public interface NivroTool {

  /** 工具名称。 */
  String getName();

  /** 工具描述（进入模型上下文）。 */
  String getDescription();

  /** 输入参数的 JSON Schema。 */
  JsonSchema getInputSchema();

  /** 执行工具（由 ToolExecutor 调用，Provider 层不调用）。 */
  ToolResult execute(JsonNode input);
}
