package com.nivroos.core.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nivroos.core.context.ContextLoader;
import com.nivroos.core.model.Message;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileContext;
import com.nivroos.core.session.Session;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Prompt 组装验收点：颗粒度文档 §4.2（FR-005 / FR-010 截断）。 */
class PromptBuilderTest {

  @AfterEach
  void tearDown() {
    ProfileContext.clear();
  }

  @Test
  @DisplayName("四部分齐全：system 在首、历史截断、工具传入、会话关联")
  void build_assemblesFourParts() {
    ContextLoader contextLoader = mock(ContextLoader.class);
    when(contextLoader.loadSystemPrompt()).thenReturn("我是天气助手。\n当前日期时间: 2026-08-31");
    PromptBuilder builder = new PromptBuilder(contextLoader);

    Session session = session();
    // 25 轮对话（50 条 user/assistant），max_history_turns=20 → 截断早期 5 轮
    for (int i = 1; i <= 25; i++) {
      session.appendMessage(new Message("user", "问题" + i));
      session.appendMessage(new Message("assistant", "回答" + i));
    }
    Profile profile = profileWithDefaults();
    ProfileContext.set(profile);

    var request = builder.build(session, List.of(mock(NivroTool.class)));

    assertThat(request.messages().get(0).role()).isEqualTo("system");
    assertThat(request.messages().get(0).content()).contains("当前日期时间");
    // 截断后：system + 20 轮 * 2 = 41 条；第 6 轮起保留
    assertThat(request.messages()).hasSize(41);
    assertThat(request.messages().get(1).content()).isEqualTo("问题6");
    assertThat(request.sessionId()).isEqualTo(session.getSessionId());
    assertThat(request.tools()).hasSize(1);
  }

  @Test
  @DisplayName("历史未超限时全量保留")
  void build_keepsAllHistoryWithinLimit() {
    ContextLoader contextLoader = mock(ContextLoader.class);
    when(contextLoader.loadSystemPrompt()).thenReturn("sys");
    PromptBuilder builder = new PromptBuilder(contextLoader);

    Session session = session();
    session.appendMessage(new Message("user", "你好"));
    session.appendMessage(new Message("assistant", "你好！"));
    ProfileContext.set(profileWithDefaults());

    var request = builder.build(session, List.of());

    assertThat(request.messages()).hasSize(3); // system + user + assistant
  }

  private static Session session() {
    return new Session("cli:u:weather", "weather", "cli", "u");
  }

  private static Profile profileWithDefaults() {
    Profile profile = new Profile();
    profile.setName("weather");
    return profile;
  }
}
