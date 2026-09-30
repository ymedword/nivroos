package com.nivroos.core.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nivroos.core.context.ContextLoader;
import com.nivroos.core.memory.MemoryService;
import com.nivroos.core.model.Message;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.session.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Prompt 组装验收点：颗粒度文档 §4.2（四部分齐全；截断断言已随实现迁往 MemoryServiceTest）。 */
class PromptBuilderTest {

  @Test
  @DisplayName("Prompt 四部分含 Memory 注入：记忆内容出现在请求消息中")
  void build_includesMemoryContent() {
    ContextLoader contextLoader = mock(ContextLoader.class);
    when(contextLoader.loadSystemPrompt()).thenReturn("我是天气助手。");
    MemoryService memory = mock(MemoryService.class);
    when(memory.loadContext(any())).thenReturn(List.of(new Message("system", "核心偏好：Spring Boot")));

    PromptBuilder builder = new PromptBuilder(contextLoader, memory);
    var request = builder.build(sessionWithHistory(), List.of());

    assertThat(request.messages().stream().map(Message::content))
        .anyMatch(content -> content.contains("Spring Boot"));
  }

  @Test
  @DisplayName("四部分齐全：system 在首、记忆上下文紧随其后、工具与会话透传")
  void build_assemblesFourParts() {
    ContextLoader contextLoader = mock(ContextLoader.class);
    when(contextLoader.loadSystemPrompt()).thenReturn("我是天气助手。\n当前日期时间: 2026-09-30");
    MemoryService memory = mock(MemoryService.class);
    when(memory.loadContext(any()))
        .thenReturn(List.of(new Message("user", "你好"), new Message("system", "核心偏好：Spring Boot")));
    PromptBuilder builder = new PromptBuilder(contextLoader, memory);

    Session session = sessionWithHistory();
    var request = builder.build(session, List.of(mock(NivroTool.class)));

    assertThat(request.messages()).hasSize(3);
    assertThat(request.messages().get(0).role()).isEqualTo("system");
    assertThat(request.messages().get(0).content()).contains("当前日期时间");
    assertThat(request.messages().get(1).content()).isEqualTo("你好");
    assertThat(request.messages().get(2).content()).isEqualTo("核心偏好：Spring Boot");
    assertThat(request.sessionId()).isEqualTo(session.getSessionId());
    assertThat(request.tools()).hasSize(1);
  }

  @Test
  @DisplayName("记忆上下文由 MemoryService 原样提供：本类不再自行截断历史")
  void build_delegatesContextToMemoryService() {
    ContextLoader contextLoader = mock(ContextLoader.class);
    when(contextLoader.loadSystemPrompt()).thenReturn("sys");
    MemoryService memory = mock(MemoryService.class);
    List<Message> supplied = List.of(new Message("user", "最近一条"));
    when(memory.loadContext(any())).thenReturn(supplied);
    PromptBuilder builder = new PromptBuilder(contextLoader, memory);

    Session session = sessionWithHistory();
    for (int i = 1; i <= 50; i++) {
      session.appendMessage(new Message("user", "旧消息" + i));
    }

    var request = builder.build(session, List.of());

    assertThat(request.messages()).hasSize(2); // system + 门面给的，无本地二次截断
    assertThat(request.messages().get(1)).isEqualTo(supplied.get(0));
  }

  private static Session sessionWithHistory() {
    Session session = new Session("cli:u:weather", "weather", "cli", "u");
    session.appendMessage(new Message("user", "你好"));
    session.appendMessage(new Message("assistant", "你好！"));
    return session;
  }
}
