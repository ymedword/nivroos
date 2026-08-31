package com.nivroos.core.provider;

/**
 * LLM 调用审计写入接口（宪法原则五：审计 day one 写入）。
 *
 * <p>Audit write contract; interface in core, JPA implementation in nivroos-storage (dependency
 * inversion, same pattern as ScheduledTaskStore). Every call - success or failure - must be
 * recorded; on failure the token columns are null and duration carries the actual elapsed time.
 */
public interface LlmCallStore {

  /**
   * 写入一条调用记录（成功与失败都写）。
   *
   * @param sessionId 会话关联（US-2 起填充；US-1 阶段可为 null）
   * @param provider 供应商名称
   * @param model 模型名
   * @param promptTokens 输入 token（失败或厂商未返回时 null）
   * @param completionTokens 输出 token（同上）
   * @param totalTokens 总 token（同上）
   * @param durationMs 实际耗时（含失败调用）
   */
  void record(
      String sessionId,
      String provider,
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      long durationMs);
}
