package com.nivroos.core.react;

import com.nivroos.core.context.ContextLoader;
import com.nivroos.core.memory.MemoryService;
import com.nivroos.core.model.ChatRequest;
import com.nivroos.core.model.Message;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.session.Session;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 组装（技术方案 §4.2）。
 *
 * <p>Assembles the four parts per round: system prompt (AGENT.md body + Bootstrap + date, via
 * ContextLoader), then everything MemoryService hands over - truncated history plus long-term
 * memory - and finally the tool list. History truncation moved into MemoryService in US-3 (the
 * facade owns both memory sources, so the loop never asks two places); this class stays a pure
 * assembler.
 */
public class PromptBuilder {

  private final ContextLoader contextLoader;
  private final MemoryService memoryService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ContextLoader 是长寿命协作 Bean（只持白名单列表与路径等不可变派生值），字段私有且无访问器外泄；"
              + "The loader is a long-lived collaborator holding only immutable derived values; the field is private and no accessor returns it")
  public PromptBuilder(ContextLoader contextLoader, MemoryService memoryService) {
    this.contextLoader = contextLoader;
    this.memoryService = memoryService;
  }

  /**
   * 组装一轮调用的请求。
   *
   * @param session 当前会话（截断只作用于视图，会话内全量累积）
   * @param tools 本轮可用的工具列表（已按 Profile.tools 解析）
   * @return 含 system 消息、记忆上下文与会话关联的请求
   */
  public ChatRequest build(Session session, List<NivroTool> tools) {
    List<Message> messages = new ArrayList<>();
    messages.add(new Message("system", contextLoader.loadSystemPrompt()));
    messages.addAll(memoryService.loadContext(session)); // 会话历史 + 长期记忆，每轮重读不缓存
    return new ChatRequest(messages, session.getSessionId(), tools);
  }
}
