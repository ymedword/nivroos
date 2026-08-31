package com.nivroos.provider;

import com.nivroos.core.model.ChatRequest;
import com.nivroos.core.model.ChatResponse;
import com.nivroos.core.model.Message;
import com.nivroos.core.model.ToolCallRequest;
import com.nivroos.core.model.Usage;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.provider.LlmCallStore;
import com.nivroos.core.provider.ProviderCallException;
import com.nivroos.core.provider.ProviderNotFoundException;
import com.nivroos.core.provider.ProviderService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * ProviderService 的 Spring AI 实现（宪法原则三：显式映射，不扫描容器）。
 *
 * <p>Routes by an explicit provider-name-to-ChatModel map; disables Spring AI auto tool execution
 * on every request (constitution); records every call - success or failure - through LlmCallStore
 * (audit day one).
 */
public class SpringAiProviderService implements ProviderService {

  private static final Logger log = LoggerFactory.getLogger(SpringAiProviderService.class);

  private final Map<String, ChatModel> providerMap;
  private final FunctionCallingAdapter adapter;
  private final LlmCallStore audit;

  public SpringAiProviderService(
      Map<String, ChatModel> providerMap, FunctionCallingAdapter adapter, LlmCallStore audit) {
    this.providerMap = Map.copyOf(providerMap);
    this.adapter = adapter;
    this.audit = audit;
  }

  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "log arguments are sanitized via sanitizeForLog (strips CR/LF); findsecbugs does not recognize the custom sanitizer")
  @Override
  public ChatResponse call(Profile profile, ChatRequest request) {
    String providerName = profile.getProviderName();
    ChatModel model = providerMap.get(providerName);
    if (model == null) {
      throw new ProviderNotFoundException(providerName, providerMap.keySet());
    }
    String modelName = profile.getModel();

    Prompt prompt = buildPrompt(profile, request);
    long startedAt = System.currentTimeMillis();
    try {
      org.springframework.ai.chat.model.ChatResponse response = model.call(prompt);
      long duration = System.currentTimeMillis() - startedAt;
      Usage usage = toUsage(response);
      audit.record(
          providerName,
          modelName,
          usage == null ? null : usage.promptTokens(),
          usage == null ? null : usage.completionTokens(),
          usage == null ? null : usage.totalTokens(),
          duration);
      log.info(
          "Provider call ok: provider={}, model={}, durationMs={}",
          sanitizeForLog(providerName),
          sanitizeForLog(modelName),
          duration);
      return toChatResponse(response, usage);
    } catch (RuntimeException e) {
      long duration = System.currentTimeMillis() - startedAt;
      // 调用失败也留痕（token 记空、duration 记实际耗时），再把错误抛给上层处理
      audit.record(providerName, modelName, null, null, null, duration);
      log.warn(
          "Provider call failed: provider={}, model={}, durationMs={}",
          sanitizeForLog(providerName),
          sanitizeForLog(modelName),
          duration,
          e);
      throw new ProviderCallException(providerName, modelName, e.getMessage(), e);
    }
  }

  @Override
  public Set<String> providerNames() {
    return providerMap.keySet();
  }

  private Prompt buildPrompt(Profile profile, ChatRequest request) {
    List<org.springframework.ai.chat.messages.Message> messages =
        request.messages().stream().map(SpringAiProviderService::toSpringMessage).toList();

    ToolCallingChatOptions.Builder options =
        DefaultToolCallingChatOptions.builder()
            .model(profile.getModel())
            .toolCallbacks(adapter.fromTools(request.tools()))
            .internalToolExecutionEnabled(false); // 宪法原则二：禁用 Spring AI 自动 tool 执行
    if (profile.getTemperature() != null) {
      options.temperature(profile.getTemperature());
    }
    return new Prompt(messages, options.build());
  }

  private static org.springframework.ai.chat.messages.Message toSpringMessage(Message message) {
    // US-1 阶段覆盖 system/user/assistant；tool 角色消息随 US-2 工具回填引入
    return switch (message.role()) {
      case "system" -> new SystemMessage(message.content());
      case "assistant" -> new AssistantMessage(message.content());
      default -> new UserMessage(message.content());
    };
  }

  private static Usage toUsage(org.springframework.ai.chat.model.ChatResponse response) {
    org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
    if (usage == null) {
      return null;
    }
    return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
  }

  private static ChatResponse toChatResponse(
      org.springframework.ai.chat.model.ChatResponse response, Usage usage) {
    AssistantMessage output = response.getResult().getOutput();
    List<ToolCallRequest> toolCalls =
        output.getToolCalls().stream()
            .map(call -> new ToolCallRequest(call.name(), call.arguments()))
            .toList();
    return new ChatResponse(output.getText(), toolCalls, usage);
  }

  /** 日志参数 CRLF 消毒：供应商/模型名来自配置，防止日志行注入（findsecbugs）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
