package com.nivroos.core.model;

import java.util.List;

/**
 * 一次 LLM 调用请求。
 *
 * <p>One LLM call request; tools are described to the model but never executed here (constitution:
 * the ReAct loop executes tools).
 *
 * @param messages 对话消息列表
 * @param sessionId 会话标识（US-1 阶段可为 null，US-2 起填充）
 * @param tools 本次调用携带的工具定义（仅翻译为协议格式，不执行）
 */
public record ChatRequest(List<Message> messages, String sessionId, List<NivroTool> tools) {

  public ChatRequest {
    messages = List.copyOf(messages == null ? List.of() : messages);
    tools = List.copyOf(tools == null ? List.of() : tools);
  }
}
