package com.nivroos.core.session;

import com.nivroos.core.model.Message;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话容器（技术方案 §9.2；US-2 内存版，US-5 落库）。
 *
 * <p>Container for one conversation; fields mirror the authoritative sessions table so the US-5
 * persistence migration is a direct mapping. The full history is accumulated here (auditable);
 * truncation is a view applied at prompt assembly.
 */
public class Session {

  private final String sessionId;
  private final String profileName;
  private final String channel;
  private final String userId;
  private final List<Message> messages = new ArrayList<>();
  private final LocalDateTime createdAt = LocalDateTime.now();
  private LocalDateTime lastActiveAt = LocalDateTime.now();

  public Session(String sessionId, String profileName, String channel, String userId) {
    this.sessionId = sessionId;
    this.profileName = profileName;
    this.channel = channel;
    this.userId = userId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getProfileName() {
    return profileName;
  }

  public String getChannel() {
    return channel;
  }

  public String getUserId() {
    return userId;
  }

  /** 每轮都留痕：用户消息、模型响应、工具结果都经此累积（可审计）。 */
  public void appendMessage(Message message) {
    messages.add(message);
    lastActiveAt = LocalDateTime.now();
  }

  public List<Message> getMessages() {
    // 不可变视图：累积只经 appendMessage（每轮留痕），读取方不修改
    return List.copyOf(messages);
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getLastActiveAt() {
    return lastActiveAt;
  }
}
