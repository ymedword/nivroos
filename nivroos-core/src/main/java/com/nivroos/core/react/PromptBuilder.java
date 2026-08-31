package com.nivroos.core.react;

import com.nivroos.core.context.ContextLoader;
import com.nivroos.core.model.ChatRequest;
import com.nivroos.core.model.Message;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileContext;
import com.nivroos.core.session.Session;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 组装（技术方案 §4.2）。
 *
 * <p>Assembles the four parts per round: system prompt (AGENT.md body + Bootstrap + date, via
 * ContextLoader), conversation history (truncated to max_history_turns rounds - a view only, the
 * session keeps the full history), and the tool list. Memory injection is a placeholder here and
 * lands in US-3 (MemoryService).
 */
public class PromptBuilder {

  private final ContextLoader contextLoader;

  public PromptBuilder(ContextLoader contextLoader) {
    this.contextLoader = contextLoader;
  }

  /**
   * 组装一轮调用的请求。
   *
   * @param session 当前会话（截断只作用于视图，会话内全量累积）
   * @param tools 本轮可用的工具列表（已按 Profile.tools 解析）
   * @return 含 system 消息、截断历史与会话关联的请求
   */
  public ChatRequest build(Session session, List<NivroTool> tools) {
    Profile profile = ProfileContext.current();
    int maxTurns = profile != null ? profile.getSettings().getMaxHistoryTurns() : 20;

    List<Message> messages = new ArrayList<>();
    messages.add(new Message("system", contextLoader.loadSystemPrompt()));
    messages.addAll(truncateHistory(session.getMessages(), maxTurns));
    return new ChatRequest(messages, session.getSessionId(), tools);
  }

  /** 简单截断（需求文档 §5.4：超长时截断早期对话、保留近期）： 保留最近 maxTurns * 2 条消息，总结压缩放扩展阶段。 */
  private static List<Message> truncateHistory(List<Message> all, int maxTurns) {
    int limit = maxTurns * 2;
    return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
  }
}
