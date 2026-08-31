package com.nivroos.core.profile;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Profile 内存索引（技术方案 §8.2 简化版；目录扫描与运行时注册归 US-4）。
 *
 * <p>In-memory index keyed by profile name; channels and AgentService resolve the active Profile
 * through it.
 */
public class ProfileRegistry {

  private final ConcurrentMap<String, Profile> profiles = new ConcurrentHashMap<>();

  public void register(Profile profile) {
    profiles.put(profile.getName(), profile);
  }

  /** 按名查找；不存在返回 null。 */
  public Profile get(String name) {
    return profiles.get(name);
  }
}
