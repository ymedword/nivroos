package com.nivroos.provider;

import com.nivroos.core.provider.LlmCallStore;
import com.nivroos.core.provider.ProviderService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provider 装配（宪法原则三：显式映射，不扫描容器）。
 *
 * <p>Builds one ChatModel per configured provider and assembles the explicit name-to-model map.
 * Discriminator: no base-url means the official DeepSeek starter (default endpoint
 * api.deepseek.com); a custom base-url means the OpenAI-compatible channel (Kimi/Moonshot has no GA
 * starter). OpenAiAutoConfiguration stays excluded (application.yml) - eager wiring would bypass
 * this explicit mapping.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderAutoConfiguration {

  private final Map<String, ProviderProperties.ProviderConfig> providers;

  public ProviderAutoConfiguration(ProviderProperties properties) {
    properties.validate(); // 非法配置直接拒绝启动（FR-007）
    // 防御性拷贝：不再持有可变的配置对象引用
    this.providers = Map.copyOf(properties.getProviders());
  }

  @Bean
  Map<String, ChatModel> providerModels() {
    Map<String, ChatModel> models = new HashMap<>();
    providers.forEach((name, config) -> models.put(name, buildModel(config)));
    return Map.copyOf(models);
  }

  @Bean
  ProviderService providerService(
      Map<String, ChatModel> providerModels, LlmCallStore llmCallStore) {
    return new SpringAiProviderService(providerModels, new FunctionCallingAdapter(), llmCallStore);
  }

  private static ChatModel buildModel(ProviderProperties.ProviderConfig config) {
    ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();
    if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
      // 无自定义端点 → 官方 DeepSeek starter（默认端点 api.deepseek.com）
      DeepSeekApi api = DeepSeekApi.builder().apiKey(config.getApiKey()).build();
      return DeepSeekChatModel.builder()
          .deepSeekApi(api)
          .defaultOptions(DeepSeekChatOptions.builder().build())
          .toolCallingManager(toolCallingManager)
          .build();
    }
    // 自定义端点 → OpenAI 兼容通道（Kimi 经 api.moonshot.cn/v1）
    OpenAiApi api =
        OpenAiApi.builder().apiKey(config.getApiKey()).baseUrl(config.getBaseUrl()).build();
    return OpenAiChatModel.builder()
        .openAiApi(api)
        .defaultOptions(OpenAiChatOptions.builder().build())
        .toolCallingManager(toolCallingManager)
        .build();
  }
}
