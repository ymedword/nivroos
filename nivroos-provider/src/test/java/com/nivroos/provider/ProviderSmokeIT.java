package com.nivroos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.nivroos.core.model.ChatRequest;
import com.nivroos.core.model.Message;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.provider.LlmCallStore;
import com.nivroos.core.provider.ProviderService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 集成冒烟（颗粒度文档 §4.1）：要碰真实网络，按环境守卫执行。
 *
 * <p>Real-network smoke tests guarded by environment variables - skipped in CI without keys (never
 * red there); with keys each provider makes one real call and the audit store must capture it.
 */
class ProviderSmokeIT {

  private static final String DEEPSEEK_API_KEY_ENV = "DEEPSEEK_API_KEY";
  private static final String KIMI_API_KEY_ENV = "KIMI_API_KEY";

  @Test
  @DisplayName("DeepSeek 真实调用：返回非空且审计留痕")
  @EnabledIfEnvironmentVariable(named = DEEPSEEK_API_KEY_ENV, matches = ".+")
  void deepseek_realCall_returnsContentAndRecordsAudit() {
    DeepSeekApi api = DeepSeekApi.builder().apiKey(System.getenv(DEEPSEEK_API_KEY_ENV)).build();
    var model =
        DeepSeekChatModel.builder()
            .deepSeekApi(api)
            .defaultOptions(DeepSeekChatOptions.builder().build())
            .toolCallingManager(ToolCallingManager.builder().build())
            .build();

    CapturingLlmCallStore audit = new CapturingLlmCallStore();
    ProviderService service =
        new SpringAiProviderService(
            java.util.Map.of("deepseek", model), new FunctionCallingAdapter(), audit);

    var response = service.call(profileUsing("deepseek", "deepseek-chat"), smokeRequest());

    assertThat(response.content()).isNotBlank();
    assertThat(audit.records).hasSize(1);
    assertThat(audit.records.get(0).provider()).isEqualTo("deepseek");
    assertThat(audit.records.get(0).promptTokens()).isNotNull();
  }

  @Test
  @DisplayName("Kimi（OpenAI 兼容通道）真实调用：返回非空且审计留痕")
  @EnabledIfEnvironmentVariable(named = KIMI_API_KEY_ENV, matches = ".+")
  void kimi_realCall_returnsContentAndRecordsAudit() {
    // 模型名以 Moonshot 官方当前口径为准，可用 KIMI_MODEL 覆盖
    String kimiModel = System.getenv().getOrDefault("KIMI_MODEL", "moonshot-v1-8k");
    OpenAiApi api =
        OpenAiApi.builder()
            .apiKey(System.getenv(KIMI_API_KEY_ENV))
            .baseUrl("https://api.moonshot.cn/v1")
            .build();
    var model =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().build())
            .toolCallingManager(ToolCallingManager.builder().build())
            .build();

    CapturingLlmCallStore audit = new CapturingLlmCallStore();
    ProviderService service =
        new SpringAiProviderService(
            java.util.Map.of("kimi", model), new FunctionCallingAdapter(), audit);

    var response = service.call(profileUsing("kimi", kimiModel), smokeRequest());

    assertThat(response.content()).isNotBlank();
    assertThat(audit.records).hasSize(1);
    assertThat(audit.records.get(0).provider()).isEqualTo("kimi");
    assertThat(audit.records.get(0).totalTokens()).isNotNull();
  }

  private static Profile profileUsing(String provider, String modelName) {
    Profile profile = new Profile();
    profile.setProviderName(provider);
    profile.setModel(modelName);
    return profile;
  }

  private static ChatRequest smokeRequest() {
    return new ChatRequest(List.of(new Message("user", "请回复：你好")), null, List.of());
  }

  /** 冒烟审计采集器：验证"每次调用都留痕"，落库断言由 US-3 的 JpaLlmCallStoreTest 覆盖。 */
  private static final class CapturingLlmCallStore implements LlmCallStore {

    private final List<AuditRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        long durationMs) {
      records.add(new AuditRecord(provider, model, promptTokens, completionTokens, totalTokens));
    }
  }

  private record AuditRecord(
      String provider,
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens) {}
}
