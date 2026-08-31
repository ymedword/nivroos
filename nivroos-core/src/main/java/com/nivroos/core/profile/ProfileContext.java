package com.nivroos.core.profile;

/**
 * 当前处理的 Agent 配置的线程级持有（技术方案 §4.2）。
 *
 * <p>ThreadLocal holder for the active Profile; virtual threads are reused by carrier threads, so
 * the value MUST be cleared (finally) after every process - a leaked value would silently leak one
 * agent's config into another request.
 */
public final class ProfileContext {

  private static final ThreadLocal<Profile> CURRENT = new ThreadLocal<>();

  private ProfileContext() {}

  public static void set(Profile profile) {
    CURRENT.set(profile);
  }

  /** 当前 Profile；未设置时返回 null（调用方按需判空）。 */
  public static Profile current() {
    return CURRENT.get();
  }

  /** 处理结束（含异常路径）必须调用（FR-006）。 */
  public static void clear() {
    CURRENT.remove();
  }
}
