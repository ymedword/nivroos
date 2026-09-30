package com.nivroos.core.memory;

import com.nivroos.core.model.Message;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileContext;
import com.nivroos.core.session.Session;
import com.nivroos.core.session.SessionManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆统一门面（技术方案 §5.1；核心能力三）。
 *
 * <p>Single memory entry point for the ReAct loop: conversation history and long-term memory are
 * gathered behind one call so the loop never asks two sources separately (FR-019). History is read
 * through {@link SessionManager} rather than off the passed-in object, keeping the manager the
 * single source of truth for session state - that is what keeps this class correct once US-5 moves
 * sessions into SQLite. Truncation lives here too, because "how the history is handed over" belongs
 * with the history source.
 *
 * <p>读写失败语义不同（spec Clarifications 2026-09-30）：写入失败上抛，让 ToolExecutor 落失败审计、 模型得知未记住（FR-012）；读取失败记
 * WARN 并按「本轮无长期记忆」继续，外部记忆服务抖动不得拖垮 整轮对话（FR-021）。
 */
public class MemoryService {

  private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

  /** Profile 未设置时的历史轮数上限（同 US-2 语义）。 */
  private static final int DEFAULT_MAX_HISTORY_TURNS = 20;

  private final SessionManager sessionManager;
  private final LongTermMemoryStore store;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Long-term store is a long-lived collaborator bean; the field is private and no accessor ever returns it")
  public MemoryService(SessionManager sessionManager, LongTermMemoryStore store) {
    this.sessionManager = sessionManager;
    this.store = store;
  }

  /**
   * 组装本轮上下文：截断后的会话历史在前 + 长期记忆（一条 system 消息）在后。
   *
   * <p>Builds this round's context - truncated history first, long-term memory second. Both are
   * read fresh on every round (contract 1: no caching).
   *
   * @param session 当前会话（截断只作用于视图，会话内全量累积）
   * @return 可直接追加到 system 消息之后的消息列表
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "The message parameter is passed through sanitizeForLog (CR/LF stripped); the trailing Throwable contributes a stack trace, not log text")
  public List<Message> loadContext(Session session) {
    Session canonical = sessionManager.findById(session.getSessionId());
    Session source = canonical != null ? canonical : session;

    List<Message> context = new ArrayList<>(truncateHistory(source.getMessages()));

    try {
      String memory = store.load();
      if (memory != null && !memory.isBlank()) {
        context.add(new Message("system", memory));
      }
    } catch (RuntimeException e) {
      // 读取每轮必经：失败只能降级不能中断；下一轮重试，失败结果不缓存（契约①）
      log.warn("长期记忆读取失败，本轮按无长期记忆继续（不缓存，下一轮重试）: {}", sanitizeForLog(e.getMessage()), e);
    }
    return context;
  }

  /**
   * 写入长期记忆。
   *
   * <p>Writes one entry; a {@code null} scope means ARCHIVAL (FR-006). Failures propagate so the
   * caller (ToolExecutor) records a failed tool invocation instead of silently pretending success.
   *
   * @param content 记忆正文
   * @param scope 目标分区；null 视为 ARCHIVAL
   */
  public void save(String content, MemoryScope scope) {
    store.append(content, scope == null ? MemoryScope.ARCHIVAL : scope);
  }

  /**
   * 关键词检索归档区。
   *
   * <p>Keyword recall over the ARCHIVAL section only; an empty result is a valid answer and the
   * no-match wording is produced by the tool layer.
   *
   * @param query 检索关键词
   * @return 命中的内容行；无命中返回空列表
   */
  public List<String> recall(String query) {
    return store.recallByKeyword(query);
  }

  /** 日志参数 CRLF 消毒：异常信息可能来自外部服务响应体，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }

  /**
   * 保留最近 maxTurns * 2 条消息（需求文档 §5.4：超长时截断早期对话、保留近期）。
   *
   * <p>Keeps the newest messages; summarisation-based compression is deferred to the extension
   * phase. The full history stays in the session - this is a view only.
   */
  private List<Message> truncateHistory(List<Message> all) {
    Profile profile = ProfileContext.current();
    int maxTurns =
        profile != null ? profile.getSettings().getMaxHistoryTurns() : DEFAULT_MAX_HISTORY_TURNS;
    int limit = maxTurns * 2;
    return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
  }
}
