package com.nivroos.core.session;

/**
 * 会话管理接口（技术方案 §5.1；US-2 内存实现，US-5 落 SQLite）。
 *
 * <p>session_id 公式（channel + user + profile 联合生成）只在实现类内拼接一处， US-5 迁移时复用同一公式，保证行为一致。
 */
public interface SessionManager {

  /**
   * 按渠道+用户+Agent 取已有会话，不存在则创建。
   *
   * @param channel 接入渠道（核心阶段取值 cli）
   * @param userId 用户标识
   * @param profileName Agent 名
   * @return 会话（同一身份历次调用复用同一实例）
   */
  Session getOrCreate(String channel, String userId, String profileName);

  /** 按会话标识查找；不存在返回 null。 */
  Session findById(String sessionId);
}
