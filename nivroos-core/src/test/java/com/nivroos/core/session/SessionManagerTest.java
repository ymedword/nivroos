package com.nivroos.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.nivroos.core.model.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 会话管理验收点：颗粒度文档 §4.2（FR-007 / FR-008）。 */
class SessionManagerTest {

  private final InMemorySessionManager manager = new InMemorySessionManager();

  @Test
  @DisplayName("session_id 公式：channel+user+profile 联合生成且唯一")
  void sessionId_generatedFromTriple() {
    Session session = manager.getOrCreate("cli", "u1", "weather");

    assertThat(session.getSessionId()).isEqualTo("cli:u1:weather");
  }

  @Test
  @DisplayName("同一身份复用同一会话；不同身份不同会话（US2 会话复用）")
  void sameIdentity_reusesSession() {
    Session first = manager.getOrCreate("cli", "u1", "weather");
    first.appendMessage(new Message("user", "第一轮"));

    Session second = manager.getOrCreate("cli", "u1", "weather");

    assertThat(second).isSameAs(first);
    assertThat(second.getMessages()).hasSize(1);

    Session other = manager.getOrCreate("cli", "u2", "weather");
    assertThat(other).isNotSameAs(first);
  }

  @Test
  @DisplayName("多轮消息累积不丢失")
  void messages_accumulateAcrossRounds() {
    Session session = manager.getOrCreate("cli", "u1", "weather");
    session.appendMessage(new Message("user", "问题1"));
    session.appendMessage(new Message("assistant", "回答1"));
    session.appendMessage(new Message("user", "问题2"));

    assertThat(session.getMessages()).hasSize(3);
    assertThat(manager.findById("cli:u1:weather")).isSameAs(session);
  }
}
