package com.nivroos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.nivroos.core.model.ChatRequest;
import com.nivroos.core.model.Message;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.provider.LlmCallStore;
import com.nivroos.core.provider.ProviderService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.yaml.snakeyaml.Yaml;

/**
 * 集成冒烟（颗粒度文档 §4.1）：要碰真实网络，按凭证存在性执行。
 *
 * <p>Real-network smoke tests guarded by credential availability - skipped in CI without keys
 * (never red there); with keys each provider makes one real call and the audit store must capture
 * it. Credentials resolve in two ways (FR-003 dual channel): the environment variable first, then a
 * local secrets file (default {@code ./nivroos-secrets.yml}, overridable via the {@code
 * nivroos.secrets.file} system property).
 */
class ProviderSmokeIT {

  private static final String DEEPSEEK_API_KEY_ENV = "DEEPSEEK_API_KEY";
  private static final String KIMI_API_KEY_ENV = "KIMI_API_KEY";

  @Test
  @DisplayName("DeepSeek 真实调用：返回非空且审计留痕")
  @EnabledIf("hasDeepseekKey")
  void deepseek_realCall_returnsContentAndRecordsAudit() {
    DeepSeekApi api =
        DeepSeekApi.builder().apiKey(apiKey(DEEPSEEK_API_KEY_ENV, "deepseek")).build();
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
  @EnabledIf("hasKimiKey")
  void kimi_realCall_returnsContentAndRecordsAudit() {
    // 模型名以 Moonshot 官方当前口径为准（2026-08-31 实测账户可用：kimi-k2.6 / k2.7-code / k3），可用 KIMI_MODEL 覆盖
    String kimiModel = System.getenv().getOrDefault("KIMI_MODEL", "kimi-k2.6");
    // OpenAiApi 自行追加 /v1 路径：base-url 不带 /v1（带会 404 /v1/v1，2026-08-31 实测）
    OpenAiApi api =
        OpenAiApi.builder()
            .apiKey(apiKey(KIMI_API_KEY_ENV, "kimi"))
            .baseUrl("https://api.moonshot.cn")
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

  /**
   * @EnabledIf 守卫：凭证可用才执行（环境变量或本地密钥文件）。
   */
  public static boolean hasDeepseekKey() {
    return apiKey(DEEPSEEK_API_KEY_ENV, "deepseek") != null;
  }

  /**
   * @EnabledIf 守卫：凭证可用才执行（环境变量或本地密钥文件）。
   */
  public static boolean hasKimiKey() {
    return apiKey(KIMI_API_KEY_ENV, "kimi") != null;
  }

  /**
   * 凭证解析（FR-003 双通道）：环境变量优先，其次本地密钥文件（默认 ./nivroos-secrets.yml，可用系统属性 nivroos.secrets.file 指定位置）。
   */
  @SuppressWarnings("unchecked")
  private static String apiKey(String envName, String providerName) {
    String envValue = System.getenv(envName);
    if (envValue != null && !envValue.isBlank()) {
      return envValue;
    }
    String configuredPath = System.getProperty("nivroos.secrets.file", "nivroos-secrets.yml");
    Path file = Path.of(configuredPath);
    if (!Files.exists(file)) {
      return null;
    }
    try {
      Map<String, Object> root = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
      if (!(root.get("nivroos") instanceof Map<?, ?> nivroos)) {
        return null;
      }
      if (!(nivroos.get("providers") instanceof Map<?, ?> providers)) {
        return null;
      }
      if (!(providers.get(providerName) instanceof Map<?, ?> provider)) {
        return null;
      }
      Object key = provider.get("api-key");
      return key == null ? null : String.valueOf(key);
    } catch (Exception e) {
      return null;
    }
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
        String sessionId,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        long durationMs) {
      records.add(
          new AuditRecord(sessionId, provider, model, promptTokens, completionTokens, totalTokens));
    }
  }

  private record AuditRecord(
      String sessionId,
      String provider,
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens) {}
}
