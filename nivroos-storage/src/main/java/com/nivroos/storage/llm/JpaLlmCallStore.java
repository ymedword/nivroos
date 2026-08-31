package com.nivroos.storage.llm;

import com.nivroos.core.provider.LlmCallStore;
import java.time.LocalDateTime;

/**
 * LlmCallStore 的 JPA 实现（依赖倒置：接口在 core，实现在 storage）。
 *
 * <p>Persists every call - success or failure; on failure the token columns are null and duration
 * carries the actual elapsed time.
 */
public class JpaLlmCallStore implements LlmCallStore {

  private final LlmCallRepository repository;

  public JpaLlmCallStore(LlmCallRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(
      String provider,
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      long durationMs) {
    LlmCall call = new LlmCall();
    call.setProvider(provider);
    call.setModel(model);
    call.setPromptTokens(promptTokens);
    call.setCompletionTokens(completionTokens);
    call.setTotalTokens(totalTokens);
    call.setDurationMs(durationMs);
    call.setCreatedAt(LocalDateTime.now());
    repository.save(call);
  }
}
