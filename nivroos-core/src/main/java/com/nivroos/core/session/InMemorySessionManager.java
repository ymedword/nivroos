package com.nivroos.core.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话管理的内存实现（US-2；US-5 换 SQLite 实现，接口不变）。
 *
 * <p>session_id 拼接公式只存在于此（data-model §1）：channel + ":" + userId + ":" + profileName；并发安全由
 * ConcurrentMap 与虚拟线程下的同步模型保证。
 */
public class InMemorySessionManager implements SessionManager {

  private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

  @Override
  public Session getOrCreate(String channel, String userId, String profileName) {
    // session_id 公式唯一拼接处（FR-008），不得在其他地方重新拼接
    String sessionId = channel + ":" + userId + ":" + profileName;
    return sessions.computeIfAbsent(sessionId, id -> new Session(id, profileName, channel, userId));
  }

  @Override
  public Session findById(String sessionId) {
    return sessions.get(sessionId);
  }
}
