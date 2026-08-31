package com.nivroos.storage.llm;

import com.nivroos.core.provider.LlmCallStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LlmCallStore 装配（boot 组件扫描 com.nivroos 全包，本配置随类路径生效）。
 *
 * <p>Bean wiring for the audit store; the interface stays in core so the provider layer depends
 * only on the contract.
 */
@Configuration(proxyBeanMethods = false)
public class LlmCallStoreConfiguration {

  @Bean
  LlmCallStore llmCallStore(LlmCallRepository repository) {
    return new JpaLlmCallStore(repository);
  }
}
