package com.nivroos.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nivroos.core.model.Message;
import com.nivroos.core.profile.Profile;
import com.nivroos.core.profile.ProfileContext;
import com.nivroos.core.session.Session;
import com.nivroos.core.session.SessionManager;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 记忆统一门面验收点：颗粒度文档 §4.2（拼接顺序、门面委托、读写区分失败语义）。 */
class MemoryServiceTest {

  @AfterEach
  void tearDown() {
    ProfileContext.clear();
  }

  // ---------------------------------------------------------------- loadContext：拼接与顺序

  @Test
  @DisplayName("loadContext 拼接顺序：会话历史在前、长期记忆在后")
  void loadContext_historyFirstThenLongTermMemory() {
    SessionManager sessionManager = mock(SessionManager.class);
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    Session session = sessionWith("你好", "你好！");
    when(sessionManager.findById(any())).thenReturn(session);
    when(store.load()).thenReturn("## 核心记忆\n\n- 用户项目使用 Spring Boot");
    MemoryService memory = new MemoryService(sessionManager, store);

    List<Message> context = memory.loadContext(session);

    assertThat(context).hasSize(3);
    assertThat(context.get(0).content()).isEqualTo("你好");
    assertThat(context.get(1).content()).isEqualTo("你好！");
    assertThat(context.get(2).role()).isEqualTo("system");
    assertThat(context.get(2).content()).contains("Spring Boot"); // 记忆在最后，不抢历史的位置
  }

  @Test
  @DisplayName("历史截断迁入门面：超限保留最近 max_turns * 2 条（US-2 断言逐条保真）")
  void loadContext_truncatesHistoryKeepingNewest() {
    SessionManager sessionManager = mock(SessionManager.class);
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    Session session = new Session("cli:u:weather", "weather", "cli", "u");
    // 25 轮对话（50 条 user/assistant），max_history_turns=20 → 截断早期 5 轮
    for (int i = 1; i <= 25; i++) {
      session.appendMessage(new Message("user", "问题" + i));
      session.appendMessage(new Message("assistant", "回答" + i));
    }
    when(sessionManager.findById(any())).thenReturn(session);
    when(store.load()).thenReturn("");
    ProfileContext.set(profileWithDefaults());
    MemoryService memory = new MemoryService(sessionManager, store);

    List<Message> context = memory.loadContext(session);

    assertThat(context).hasSize(40); // 20 轮 * 2；截断只作用于视图
    assertThat(context.get(0).content()).isEqualTo("问题6"); // 第 6 轮起保留
    assertThat(session.getMessages()).hasSize(50); // 会话内全量累积不丢
  }

  @Test
  @DisplayName("截断轮数随 Profile 的 max_history_turns 走")
  void loadContext_respectsProfileMaxHistoryTurns() {
    SessionManager sessionManager = mock(SessionManager.class);
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    Session session = sessionWith("一", "二", "三", "四", "五", "六");
    when(sessionManager.findById(any())).thenReturn(session);
    when(store.load()).thenReturn("");
    Profile profile = profileWithDefaults();
    profile.getSettings().setMaxHistoryTurns(2); // → 保留最近 4 条
    ProfileContext.set(profile);
    MemoryService memory = new MemoryService(sessionManager, store);

    List<Message> context = memory.loadContext(session);

    assertThat(context).hasSize(4);
    assertThat(context.get(0).content()).isEqualTo("三");
  }

  // ---------------------------------------------------------------- 门面委托正确

  @Test
  @DisplayName("会话历史取自 SessionManager，而非传入对象的快照")
  void loadContext_readsHistoryFromSessionManager() {
    SessionManager sessionManager = mock(SessionManager.class);
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    Session live = sessionWith("新的一轮");
    Session stale = sessionWith("过期的旧副本");
    when(sessionManager.findById(live.getSessionId())).thenReturn(live);
    when(store.load()).thenReturn("");
    ProfileContext.set(profileWithDefaults());
    MemoryService memory = new MemoryService(sessionManager, store);

    List<Message> context = memory.loadContext(stale); // 故意传过期副本

    assertThat(context).extracting(Message::content).containsExactly("新的一轮");
  }

  @Test
  @DisplayName("SessionManager 查不到会话时退回传入的会话（不 NPE）")
  void loadContext_unknownSession_fallsBackToPassedSession() {
    SessionManager sessionManager = mock(SessionManager.class);
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    Session session = sessionWith("只有这一条");
    when(sessionManager.findById(any())).thenReturn(null);
    when(store.load()).thenReturn("");
    ProfileContext.set(profileWithDefaults());
    MemoryService memory = new MemoryService(sessionManager, store);

    assertThat(memory.loadContext(session)).extracting(Message::content).containsExactly("只有这一条");
  }

  @Test
  @DisplayName("长期记忆为空时不塞空 system 消息")
  void loadContext_blankMemory_noMemoryMessage() {
    SessionManager sessionManager = mock(SessionManager.class);
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    Session session = sessionWith("你好");
    when(sessionManager.findById(any())).thenReturn(session);
    when(store.load()).thenReturn("   ");
    ProfileContext.set(profileWithDefaults());
    MemoryService memory = new MemoryService(sessionManager, store);

    List<Message> context = memory.loadContext(session);

    assertThat(context).hasSize(1);
    assertThat(context.get(0).role()).isEqualTo("user");
  }

  @Test
  @DisplayName("存储读取失败降级：本轮按无长期记忆继续，历史仍在，异常不上抛")
  void loadContext_storeFailure_degradesToHistoryOnly() {
    SessionManager sessionManager = mock(SessionManager.class);
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    Session session = sessionWith("你好");
    when(sessionManager.findById(any())).thenReturn(session);
    when(store.load())
        .thenThrow(new UncheckedIOException("disk on fire", new java.io.IOException()));
    ProfileContext.set(profileWithDefaults());
    MemoryService memory = new MemoryService(sessionManager, store);

    List<Message> context = memory.loadContext(session);

    assertThat(context).extracting(Message::content).containsExactly("你好");
  }

  // ---------------------------------------------------------------- 读写区分失败语义

  @Test
  @DisplayName("save 委托 store.append，scope 原样传递（系统不猜分区）")
  void save_delegatesToStoreWithScope() {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    MemoryService memory = new MemoryService(mock(SessionManager.class), store);

    memory.save("用户项目使用 Spring Boot", MemoryScope.CORE);

    verify(store).append("用户项目使用 Spring Boot", MemoryScope.CORE);
  }

  @Test
  @DisplayName("save 未指定分区默认归档区（FR-006）")
  void save_nullScope_defaultsToArchival() {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    MemoryService memory = new MemoryService(mock(SessionManager.class), store);

    memory.save("随手记一条", null);

    verify(store).append("随手记一条", MemoryScope.ARCHIVAL);
  }

  @Test
  @DisplayName("写入失败上抛：交由 ToolExecutor 落失败审计，不静默吞掉")
  void save_failurePropagates() {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    org.mockito.Mockito.doThrow(new UncheckedIOException("read-only fs", new java.io.IOException()))
        .when(store)
        .append(any(), any());
    MemoryService memory = new MemoryService(mock(SessionManager.class), store);

    assertThatThrownBy(() -> memory.save("写不进去", MemoryScope.ARCHIVAL))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  @DisplayName("recall 委托 store.recallByKeyword")
  void recall_delegatesToStore() {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    when(store.recallByKeyword("SQLite")).thenReturn(List.of("上次讨论过使用 SQLite 作为本地存储"));
    MemoryService memory = new MemoryService(mock(SessionManager.class), store);

    assertThat(memory.recall("SQLite")).containsExactly("上次讨论过使用 SQLite 作为本地存储");
  }

  // ---------------------------------------------------------------- helpers

  private static Session sessionWith(String... contents) {
    Session session = new Session("cli:u:weather", "weather", "cli", "u");
    for (int i = 0; i < contents.length; i++) {
      session.appendMessage(new Message(i % 2 == 0 ? "user" : "assistant", contents[i]));
    }
    return session;
  }

  private static Profile profileWithDefaults() {
    Profile profile = new Profile();
    profile.setName("weather");
    return profile;
  }
}
