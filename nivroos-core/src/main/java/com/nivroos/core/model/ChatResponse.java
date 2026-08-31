package com.nivroos.core.model;

import java.util.List;

/**
 * 一次 LLM 调用的响应。
 *
 * <p>Response of one LLM call; toolCalls describe what the model requested and are NOT executed by
 * the provider layer.
 *
 * @param content 最终文本内容
 * @param toolCalls 模型请求的工具调用（只描述，不执行）
 * @param usage token 用量（厂商未返回或调用失败时字段可为 null）
 */
public record ChatResponse(String content, List<ToolCallRequest> toolCalls, Usage usage) {

  public ChatResponse {
    toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
  }
}
