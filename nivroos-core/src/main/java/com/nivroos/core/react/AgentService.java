package com.nivroos.core.react;

import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileContext;
import com.nivroos.core.profile.ProfileRegistry;
import com.nivroos.core.session.Session;

/**
 * 三种触发源共用的统一入口（技术方案 §4.2；定时触发源 US-5 接入）。
 *
 * <p>Orchestrator of one processing round: resolves the Profile by name, puts it into
 * ProfileContext (ThreadLocal), delegates to the ReAct loop, and clears the context in finally - a
 * leaked ThreadLocal on reused virtual threads would hand one agent's config to the next request.
 */
public class AgentService {

  private final ReActLoop reActLoop;
  private final ProfileRegistry profileRegistry;

  public AgentService(ReActLoop reActLoop, ProfileRegistry profileRegistry) {
    this.reActLoop = reActLoop;
    this.profileRegistry = profileRegistry;
  }

  /**
   * 处理一条用户消息。
   *
   * @param session 会话（由渠道层经 SessionManager 取得）
   * @param userMessage 用户消息
   * @return 最终响应
   */
  public String process(Session session, String userMessage) {
    Profile profile = profileRegistry.get(session.getProfileName());
    if (profile == null) {
      throw new IllegalStateException("Profile not registered: " + session.getProfileName());
    }
    ProfileContext.set(profile);
    try {
      return reActLoop.run(session, userMessage);
    } finally {
      // 处理结束（含异常路径）必须清理，防止虚拟线程复用串号（FR-006）
      ProfileContext.clear();
    }
  }
}
