package com.nivroos.core.provider;

/**
 * 工具调用审计写入接口（宪法原则五：审计 day one 写入）。
 *
 * <p>Audit write contract for tool executions; interface in core, JPA implementation in
 * nivroos-storage (dependency inversion, same pattern as LlmCallStore). Every execution - success,
 * failure, or sandbox rejection - must be recorded.
 */
public interface ToolInvocationStore {

  /**
   * 写入一条工具执行记录（成功/失败/白名单拒绝都写）。
   *
   * @param sessionId 会话关联
   * @param toolName 工具名
   * @param inputJson 调用参数（JSON，可空）
   * @param resultJson 执行结果（JSON，失败或拒绝时为 null）
   * @param success 是否成功
   * @param errorMessage 错误信息（含 Sandbox 拒绝原因；成功时为 null）
   * @param durationMs 执行耗时
   */
  void record(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs);
}
