package com.nivroos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.nivroos.core.model.ChatRequest;
import com.nivroos.core.model.ChatResponse;
import com.nivroos.core.model.JsonSchema;
import com.nivroos.core.model.Message;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolCallRequest;
import com.nivroos.core.model.ToolResult;
import com.nivroos.core.model.Usage;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.provider.LlmCallStore;
import com.nivroos.core.provider.ProviderCallException;
import com.nivroos.core.provider.ProviderNotFoundException;
import com.nivroos.core.provider.ProviderService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** 服务层验收点：颗粒度文档 §4.2/§4.3（FR-001 / FR-004 / FR-005 / FR-006 / FR-008 / FR-010）。 */
class SpringAiProviderServiceTest {

  private static final String DEEPSEEK = "deepseek";

  private final LlmCallStore audit = mock(LlmCallStore.class);

  @Test
  @DisplayName("验收点：按名路由，多供应商互不影响")
  void chat_routesToNamedProviderOnly() {
    ChatModel deepseek = mock(ChatModel.class);
    ChatModel kimi = mock(ChatModel.class);
    ProviderService service =
        new SpringAiProviderService(
            Map.of("deepseek", deepseek, "kimi", kimi), new FunctionCallingAdapter(), audit);

    when(deepseek.call(any(Prompt.class))).thenReturn(responseWith("answer"));
    when(kimi.call(any(Prompt.class))).thenReturn(responseWith("answer"));

    service.call(profileUsing("kimi"), request());

    verify(kimi, times(1)).call(any(Prompt.class));
    verify(deepseek, never()).call(any(Prompt.class)); // deepseek 未被调用——路由互不影响
  }

  @Test
  @DisplayName("验收点：调用失败，审计必须留痕（token 空 + 实际耗时），异常继续上抛")
  void callFailure_stillRecordsAudit_thenRethrows() {
    ChatModel model = mock(ChatModel.class);
    ProviderService service =
        new SpringAiProviderService(Map.of(DEEPSEEK, model), new FunctionCallingAdapter(), audit);

    when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("connect timeout"));

    assertThrows(
        ProviderCallException.class, () -> service.call(profileUsing(DEEPSEEK), request()));

    verify(audit)
        .record(
            isNull(), eq(DEEPSEEK), eq("deepseek-model"), isNull(), isNull(), isNull(), anyLong());
  }

  @Test
  @DisplayName("验收点：携带工具 schema 的调用，请求中已关闭 Spring AI 自动执行")
  void callWithToolSchema_disablesAutoExecution() {
    ChatModel model = mock(ChatModel.class);
    ProviderService service =
        new SpringAiProviderService(Map.of(DEEPSEEK, model), new FunctionCallingAdapter(), audit);

    when(model.call(any(Prompt.class))).thenReturn(responseWith("answer"));

    service.call(profileUsing(DEEPSEEK), requestWithTools(httpGetTool()));

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(captor.capture());
    // Spring AI 1.1.2 实测断言：自动执行开关必须关闭；工具仅作为 schema 传入
    ToolCallingChatOptions options = (ToolCallingChatOptions) captor.getValue().getOptions();
    assertThat(options.getInternalToolExecutionEnabled()).isFalse();
    assertThat(options.getToolCallbacks()).isNotEmpty();
  }

  @Test
  @DisplayName("未知名供应商抛 ProviderNotFoundException 且消息含可用列表（FR-010）")
  void unknownProvider_throwsWithAvailableList() {
    ProviderService service =
        new SpringAiProviderService(
            Map.of(DEEPSEEK, mock(ChatModel.class)), new FunctionCallingAdapter(), audit);

    ProviderNotFoundException exception =
        assertThrows(
            ProviderNotFoundException.class, () -> service.call(profileUsing("kimi"), request()));

    assertThat(exception.getMessage()).contains("kimi").contains(DEEPSEEK);
  }

  @Test
  @DisplayName("成功调用：内容、工具调用、token 用量映射正确且写审计（FR-005/FR-008）")
  void successfulCall_mapsContentToolCallsAndUsage() {
    ChatModel model = mock(ChatModel.class);
    ProviderService service =
        new SpringAiProviderService(Map.of(DEEPSEEK, model), new FunctionCallingAdapter(), audit);

    var springResponse = mock(org.springframework.ai.chat.model.ChatResponse.class);
    AssistantMessage output = mock(AssistantMessage.class);
    when(output.getText()).thenReturn("answer");
    when(output.getToolCalls())
        .thenReturn(
            List.of(
                new AssistantMessage.ToolCall(
                    "id-1", "function", "http_get", "{\"url\":\"https://x\"}")));
    ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
    when(metadata.getUsage())
        .thenReturn(new org.springframework.ai.chat.metadata.DefaultUsage(12, 3, 15));
    when(springResponse.getResult()).thenReturn(new Generation(output));
    when(springResponse.getMetadata()).thenReturn(metadata);
    when(model.call(any(Prompt.class))).thenReturn(springResponse);

    ChatResponse response = service.call(profileUsing(DEEPSEEK), request());

    assertThat(response.content()).isEqualTo("answer");
    assertThat(response.toolCalls())
        .containsExactly(new ToolCallRequest("http_get", "{\"url\":\"https://x\"}"));
    assertThat(response.usage()).isEqualTo(new Usage(12, 3, 15));
    verify(audit)
        .record(isNull(), eq(DEEPSEEK), eq("deepseek-model"), eq(12), eq(3), eq(15), anyLong());
  }

  @Test
  @DisplayName("回归（US-2 修复）：历史含 null 内容的 assistant/tool 消息时不炸（带工具调用的响应 content 为 null）")
  void callWithNullContentHistoryMessages_doesNotThrow() {
    ChatModel model = mock(ChatModel.class);
    ProviderService service =
        new SpringAiProviderService(Map.of(DEEPSEEK, model), new FunctionCallingAdapter(), audit);
    when(model.call(any(Prompt.class))).thenReturn(responseWith("answer"));

    ChatRequest requestWithNulls =
        new ChatRequest(
            List.of(
                new Message("user", "查天气"),
                new Message("assistant", null), // 带工具调用的响应 content 为 null
                new Message("tool", null)),
            "s-1",
            List.of());

    var response = service.call(profileUsing(DEEPSEEK), requestWithNulls);

    assertThat(response.content()).isEqualTo("answer");
    verify(model).call(any(Prompt.class));
  }

  @Test
  @DisplayName("providerNames 返回已注册供应商集合")
  void providerNames_returnsRegisteredSet() {
    ProviderService service =
        new SpringAiProviderService(
            Map.of(DEEPSEEK, mock(ChatModel.class), "kimi", mock(ChatModel.class)),
            new FunctionCallingAdapter(),
            audit);

    assertThat(service.providerNames()).containsExactlyInAnyOrder(DEEPSEEK, "kimi");
  }

  private static Profile profileUsing(String provider) {
    Profile profile = new Profile();
    profile.setProviderName(provider);
    profile.setModel(provider + "-model");
    return profile;
  }

  private static ChatRequest request() {
    return new ChatRequest(List.of(new Message("user", "你好")), null, List.of());
  }

  private static ChatRequest requestWithTools(NivroTool... tools) {
    return new ChatRequest(List.of(new Message("user", "查天气")), null, List.of(tools));
  }

  private static org.springframework.ai.chat.model.ChatResponse responseWith(String content) {
    return new org.springframework.ai.chat.model.ChatResponse(
        List.of(new Generation(new AssistantMessage(content))));
  }

  private static NivroTool httpGetTool() {
    return new NivroTool() {
      @Override
      public String getName() {
        return "http_get";
      }

      @Override
      public String getDescription() {
        return "发起 GET 请求";
      }

      @Override
      public JsonSchema getInputSchema() {
        return new JsonSchema("{\"type\":\"object\"}");
      }

      @Override
      public ToolResult execute(JsonNode input) {
        return new ToolResult(true, "ok", null, false);
      }
    };
  }
}
