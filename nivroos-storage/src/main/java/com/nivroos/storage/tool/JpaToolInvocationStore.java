package com.nivroos.storage.tool;

import com.nivroos.core.provider.ToolInvocationStore;
import java.time.LocalDateTime;

/**
 * ToolInvocationStore 的 JPA 实现（依赖倒置：接口在 core，实现在 storage）。
 *
 * <p>Persists every execution - success, failure, or sandbox rejection - with the session
 * association, matching the authoritative nine-column schema.
 */
public class JpaToolInvocationStore implements ToolInvocationStore {

  private final ToolInvocationRepository repository;

  public JpaToolInvocationStore(ToolInvocationRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs) {
    ToolInvocation invocation = new ToolInvocation();
    invocation.setSessionId(sessionId);
    invocation.setToolName(toolName);
    invocation.setInputJson(inputJson);
    invocation.setResultJson(resultJson);
    invocation.setSuccess(success);
    invocation.setErrorMessage(errorMessage);
    invocation.setDurationMs(durationMs);
    invocation.setCreatedAt(LocalDateTime.now());
    repository.save(invocation);
  }
}
