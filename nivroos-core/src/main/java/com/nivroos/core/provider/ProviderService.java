package com.nivroos.core.provider;

import com.nivroos.core.model.ChatRequest;
import com.nivroos.core.model.ChatResponse;
import com.nivroos.core.profile.Profile;
import java.util.Set;

/**
 * 供应商统一抽象（技术方案 §3.1）。
 *
 * <p>Uniform provider abstraction consumed by the ReAct loop (US-2). The interface lives in core
 * for dependency inversion - the Spring AI implementation lives in nivroos-provider (Maven would
 * otherwise be circular). Contract: contracts/provider-service.md.
 */
public interface ProviderService {

  /**
   * 发起一次同步 LLM 调用（宪法原则七）。
   *
   * <p>Synchronous blocking call; every call (success or failure) must be recorded via
   * LlmCallStore. Tool calls in the response are descriptions only - this interface never executes
   * them (constitution: Spring AI is used for protocol conversion only).
   *
   * @param profile 运行时配置（供应商名 + 模型）
   * @param request 调用请求
   * @return 调用响应
   * @throws ProviderNotFoundException profile 引用的供应商未注册
   * @throws ProviderCallException 调用失败（不自动切换、不静默重试）
   */
  ChatResponse call(Profile profile, ChatRequest request);

  /** 已注册供应商名称集合（供配置校验与 provider list）。 */
  Set<String> providerNames();
}
