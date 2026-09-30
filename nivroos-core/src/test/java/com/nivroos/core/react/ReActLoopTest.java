package com.nivroos.core.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nivroos.core.model.ChatRequest;
import com.nivroos.core.model.ChatResponse;
import com.nivroos.core.model.Message;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolCallRequest;
import com.nivroos.core.model.ToolResult;
import com.nivroos.core.model.Usage;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileContext;
import com.nivroos.core.provider.ProviderService;
import com.nivroos.core.session.Session;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/** 循环验收点：颗粒度文档 §4.2/§4.3（FR-001 / FR-004 / FR-009）。 */
class ReActLoopTest {

  @AfterEach
  void tearDown() {
    ProfileContext.clear();
  }

  @Test
  @DisplayName("验收点：循环收敛——第一轮返回工具调用，第二轮返回最终文本")
  void run_withToolCall_convergesAfterToolResult() {
    ProviderService provider = mock(ProviderService.class);
    ToolExecutor executor = mock(ToolExecutor.class);
    when(provider.call(any(), any()))
        .thenReturn(
            responseWithToolCall(
                new ToolCallRequest("http_get", "{\"url\":\"https://wttr.in/beijing\"}")))
        .thenReturn(responseWithText("北京今天 15 度，建议穿外套"));
    when(executor.execute(anyString(), any()))
        .thenReturn(new ToolResult(true, "{\"temp\":15}", null, false));

    ReActLoop loop =
        new ReActLoop(provider, promptBuilderStub(), executor, Map.of("http_get", mockTool()));
    ProfileContext.set(profileWithDefaults());
    Session session = session();

    String result = loop.run(session, "查一下北京天气");

    verify(executor, times(1)).execute(anyString(), any());
    assertThat(result).contains("穿外套");
    // 每轮都留痕：user + assistant(tool call) + tool + assistant(final) 共 4 条
    assertThat(session.getMessages()).hasSize(4);
    assertThat(session.getMessages().get(0).role()).isEqualTo("user");
    assertThat(session.getMessages().get(2).role()).isEqualTo("tool");
  }

  @Test
  @DisplayName("验收点：持续请求工具的循环在 max_iterations 处强制结束")
  void run_neverConverging_stopsAtMaxIterations() {
    ProviderService provider = mock(ProviderService.class);
    ToolExecutor executor = mock(ToolExecutor.class);
    when(provider.call(any(), any()))
        .thenReturn(responseWithToolCall(new ToolCallRequest("http_get", "{}")));
    when(executor.execute(anyString(), any())).thenReturn(new ToolResult(true, "ok", null, false));

    Profile profile = profileWithDefaults();
    profile.getSettings().setMaxIterations(3);
    ProfileContext.set(profile);
    ReActLoop loop =
        new ReActLoop(provider, promptBuilderStub(), executor, Map.of("http_get", mockTool()));

    String result = loop.run(session(), "查天气");

    verify(provider, times(3)).call(any(), any()); // 恰好 3 轮，不多不少
    assertThat(result).contains("已停止继续尝试");
  }

  @Test
  @DisplayName("无工具调用单轮直接返回（US2 快速路径）")
  void run_withoutToolCall_returnsInSingleRound() {
    ProviderService provider = mock(ProviderService.class);
    ToolExecutor executor = mock(ToolExecutor.class);
    when(provider.call(any(), any())).thenReturn(responseWithText("你好！"));

    ProfileContext.set(profileWithDefaults());
    ReActLoop loop = new ReActLoop(provider, promptBuilderStub(), executor, Map.of());

    String result = loop.run(session(), "你好");

    assertThat(result).isEqualTo("你好！");
    verify(provider, times(1)).call(any(), any());
    verify(executor, times(0)).execute(anyString(), any());
  }

  @Test
  @DisplayName("一次响应多个工具调用时按顺序执行（FR-009）")
  void run_withMultipleToolCalls_executesSequentially() {
    ProviderService provider = mock(ProviderService.class);
    ToolExecutor executor = mock(ToolExecutor.class);
    when(provider.call(any(), any()))
        .thenReturn(
            responseWithToolCalls(
                new ToolCallRequest("http_get", "{\"url\":\"https://wttr.in/a\"}"),
                new ToolCallRequest("http_get", "{\"url\":\"https://wttr.in/b\"}")))
        .thenReturn(responseWithText("完成"));
    when(executor.execute(anyString(), any())).thenReturn(new ToolResult(true, "ok", null, false));

    ProfileContext.set(profileWithDefaults());
    ReActLoop loop =
        new ReActLoop(provider, promptBuilderStub(), executor, Map.of("http_get", mockTool()));

    loop.run(session(), "查两个地方");

    // 同一轮内两次执行按顺序：用区分参数的匹配器逐次校验调用次序（FR-009）
    var inOrder = org.mockito.Mockito.inOrder(executor);
    inOrder
        .verify(executor)
        .execute(anyString(), argThat(c -> c.arguments().contains("wttr.in/a")));
    inOrder
        .verify(executor)
        .execute(anyString(), argThat(c -> c.arguments().contains("wttr.in/b")));
  }

  // ------------------------------------------------ 工具池边界（前序改造点 7：resolveTools 签名不变，只补测试）

  @Test
  @DisplayName("工具池严格按 Profile.tools 精确匹配：声明几个就只给几个，未声明的内置工具不进池")
  void run_profileDeclaresSubset_poolPassesExactlyTheDeclaredTools() {
    ProviderService provider = mock(ProviderService.class);
    when(provider.call(any(), any())).thenReturn(responseWithText("ok"));
    PromptBuilder builder = promptBuilderStub();
    Profile profile = profileWithDefaults();
    profile.setTools(List.of("http_get"));
    ProfileContext.set(profile);

    ReActLoop loop =
        new ReActLoop(
            provider,
            builder,
            mock(ToolExecutor.class),
            Map.of(
                "http_get", namedTool("http_get"),
                "shell", namedTool("shell"),
                "read_file", namedTool("read_file")));

    loop.run(session(), "查天气");

    assertThat(capturedToolNames(builder)).containsExactly("http_get"); // 不多不少：能力边界即工具池边界
  }

  @Test
  @DisplayName("声明了未注册的工具名 → 告警跳过（不静默）")
  void run_profileDeclaresUnregisteredTool_warnsAndSkips() {
    ProviderService provider = mock(ProviderService.class);
    when(provider.call(any(), any())).thenReturn(responseWithText("ok"));
    PromptBuilder builder = promptBuilderStub();
    Profile profile = profileWithDefaults();
    profile.setTools(List.of("http_get", "nope"));
    ProfileContext.set(profile);
    ReActLoop loop =
        new ReActLoop(
            provider, builder, mock(ToolExecutor.class), Map.of("http_get", namedTool("http_get")));

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      loop.run(session(), "查天气");
    } finally {
      detachAppender(appender);
    }

    assertThat(capturedToolNames(builder)).containsExactly("http_get");
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("not registered").contains("nope");
            });
  }

  @Test
  @DisplayName("Profile 未声明任何工具 → 工具池为空（不兜底给全部）")
  void run_profileDeclaresNoTools_poolIsEmpty() {
    ProviderService provider = mock(ProviderService.class);
    when(provider.call(any(), any())).thenReturn(responseWithText("ok"));
    PromptBuilder builder = promptBuilderStub();
    Profile profile = profileWithDefaults();
    profile.setTools(List.of());
    ProfileContext.set(profile);
    ReActLoop loop =
        new ReActLoop(
            provider, builder, mock(ToolExecutor.class), Map.of("http_get", namedTool("http_get")));

    loop.run(session(), "随便聊聊");

    assertThat(capturedToolNames(builder)).isEmpty();
  }

  /** 从送入 PromptBuilder 的工具池里取回工具名（resolveTools 是私有的，只能从下游观察）。 */
  @SuppressWarnings("unchecked")
  private static List<String> capturedToolNames(PromptBuilder builder) {
    ArgumentCaptor<List<NivroTool>> captor = ArgumentCaptor.forClass(List.class);
    verify(builder).build(any(), captor.capture());
    return captor.getValue().stream().map(NivroTool::getName).toList();
  }

  private static NivroTool namedTool(String name) {
    NivroTool tool = mock(NivroTool.class);
    when(tool.getName()).thenReturn(name);
    return tool;
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ReActLoop.class)).addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(ReActLoop.class)).detachAppender(appender);
  }

  private static PromptBuilder promptBuilderStub() {
    PromptBuilder builder = mock(PromptBuilder.class);
    when(builder.build(any(), any()))
        .thenAnswer(
            inv -> {
              Session s = inv.getArgument(0);
              return new ChatRequest(
                  List.of(new Message("system", "sys")), s.getSessionId(), List.of());
            });
    return builder;
  }

  private static Session session() {
    return new Session("cli:u:weather", "weather", "cli", "u");
  }

  private static Profile profileWithDefaults() {
    Profile profile = new Profile();
    profile.setName("weather");
    profile.setProviderName("deepseek");
    profile.setModel("deepseek-chat");
    profile.setTools(List.of("http_get"));
    return profile;
  }

  private static NivroTool mockTool() {
    return mock(NivroTool.class);
  }

  private static ChatResponse responseWithText(String content) {
    return new ChatResponse(content, List.of(), new Usage(1, 1, 2));
  }

  private static ChatResponse responseWithToolCall(ToolCallRequest call) {
    return new ChatResponse(null, List.of(call), new Usage(1, 1, 2));
  }

  private static ChatResponse responseWithToolCalls(ToolCallRequest... calls) {
    return new ChatResponse(null, List.of(calls), new Usage(1, 1, 2));
  }
}
