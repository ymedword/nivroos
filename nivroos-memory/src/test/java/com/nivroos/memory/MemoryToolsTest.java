package com.nivroos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nivroos.core.memory.LongTermMemoryStore;
import com.nivroos.core.memory.MemoryScope;
import com.nivroos.core.memory.MemoryService;
import com.nivroos.core.model.ToolResult;
import com.nivroos.core.session.SessionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * MemoryTools 验收点：颗粒度文档 §4.2 + 契约 contracts/memory-tools.md（两个工具段）。
 *
 * <p>US-4 起工具是 {@code @Tool} 方法：行为用直接调用断言，工具名/参数名/schema 用 Spring AI 生成
 * 的回调元数据断言（与生产注册路径同源）。JSON→ToolResult 的桥接与审计落库由 nivroos-tool 侧 （AnnotatedToolAdapter + 既有
 * ToolExecutor 机制）覆盖。
 */
class MemoryToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  /** 真实门面 + mock store：断言落在「哪个分区」，与 MemoryServiceTest 的 mock 层次互补。 */
  private static LongTermMemoryStore store() {
    return mock(LongTermMemoryStore.class);
  }

  private static MemoryTools toolsWith(LongTermMemoryStore store) {
    return new MemoryTools(new MemoryService(mock(SessionManager.class), store));
  }

  private static JsonNode args(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------------------------------------------------------------- 契约表：写入分区

  @Test
  @DisplayName("content + scope: CORE → 追加到核心区")
  void saveMemory_scopeCore_writesToCore() {
    LongTermMemoryStore store = store();

    ToolResult result = toolsWith(store).saveMemory("用户项目用 Spring Boot", "CORE");

    assertThat(result.success()).isTrue();
    verify(store).append("用户项目用 Spring Boot", MemoryScope.CORE);
  }

  @Test
  @DisplayName("content + scope: ARCHIVAL → 追加到归档区")
  void saveMemory_scopeArchival_writesToArchival() {
    LongTermMemoryStore store = store();

    ToolResult result = toolsWith(store).saveMemory("上次讨论过 SQLite", "ARCHIVAL");

    assertThat(result.success()).isTrue();
    verify(store).append("上次讨论过 SQLite", MemoryScope.ARCHIVAL);
  }

  @Test
  @DisplayName("省略 scope → 默认归档区（FR-006）")
  void saveMemory_defaultsToArchivalWhenScopeOmitted() {
    LongTermMemoryStore store = store();

    ToolResult result = toolsWith(store).saveMemory("随手记一条", null);

    assertThat(result.success()).isTrue();
    verify(store).append("随手记一条", MemoryScope.ARCHIVAL);
  }

  // ---------------------------------------------------------------- 契约表：失败分支

  @Test
  @DisplayName("content 缺失 → 失败结果，不写存储")
  void saveMemory_missingContent_failsWithoutWriting() {
    LongTermMemoryStore store = store();

    ToolResult result = toolsWith(store).saveMemory(null, "CORE");

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).isNotBlank();
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("content 为空串 → 失败结果，不写存储")
  void saveMemory_blankContent_failsWithoutWriting() {
    LongTermMemoryStore store = store();

    ToolResult result = toolsWith(store).saveMemory("   ", null);

    assertThat(result.success()).isFalse();
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("scope 非法 → 失败结果，不静默按默认处理")
  void saveMemory_illegalScope_failsInsteadOfFallingBack() {
    LongTermMemoryStore store = store();

    ToolResult result = toolsWith(store).saveMemory("一条记忆", "PERMANENT");

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("PERMANENT"); // 报错指明非法取值
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("存储 IO 失败 → 异常上抛（由 ToolExecutor 落失败审计，FR-012）")
  void saveMemory_storeFailure_propagates() {
    LongTermMemoryStore store = store();
    doThrow(new UncheckedIOException("read-only fs", new IOException()))
        .when(store)
        .append(any(), any());

    assertThatThrownBy(() -> toolsWith(store).saveMemory("写不进去", null))
        .isInstanceOf(UncheckedIOException.class);
  }

  // ---------------------------------------------------------------- recall_memory 契约表

  @Test
  @DisplayName("query 命中归档区 → 命中行以换行连接")
  void recallMemory_hits_joinsLinesWithNewline() {
    LongTermMemoryStore store = store();
    when(store.recallByKeyword("SQLite"))
        .thenReturn(List.of("上次讨论过使用 SQLite 作为本地存储", "SQLite 的 WAL 模式已开启"));

    ToolResult result = toolsWith(store).recallMemory("SQLite");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("上次讨论过使用 SQLite 作为本地存储\nSQLite 的 WAL 模式已开启");
  }

  @Test
  @DisplayName("query 无命中 → 返回「无匹配」文案（不返回编造内容、不返回空串）")
  void recallMemory_noMatch_returnsPlaceholder() {
    LongTermMemoryStore store = store();
    when(store.recallByKeyword(any())).thenReturn(List.of());

    ToolResult result = toolsWith(store).recallMemory("量子计算");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isNotBlank().contains("无匹配"); // 不回显关键词（避免被当成命中内容）
  }

  @Test
  @DisplayName("query 只命中核心区 → 不返回（核心区不参与检索，FR-009）")
  void recallMemory_coreOnlyMatch_notReturned() throws IOException {
    // 用真实 store：关键词只在核心区，检索必须落空——这是 FR-009 的端到端守点
    MarkdownMemoryStore realStore =
        new MarkdownMemoryStore(Files.createFile(tempDir.resolve("MEMORY.md")));
    realStore.append("项目使用 Spring Boot", MemoryScope.CORE);

    ToolResult result = toolsWith(realStore).recallMemory("Spring Boot");

    assertThat(result.content()).contains("无匹配").doesNotContain("Spring Boot");
  }

  @Test
  @DisplayName("query 缺失 → 失败结果，不查存储")
  void recallMemory_missingQuery_fails() {
    LongTermMemoryStore store = store();

    ToolResult result = toolsWith(store).recallMemory(null);

    assertThat(result.success()).isFalse();
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("query 为空串 → 失败结果，不查存储")
  void recallMemory_blankQuery_fails() {
    LongTermMemoryStore store = store();

    ToolResult result = toolsWith(store).recallMemory("  ");

    assertThat(result.success()).isFalse();
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("存储 IO 失败 → 异常上抛，由 ToolExecutor 落失败审计")
  void recallMemory_storeFailure_propagates() {
    LongTermMemoryStore store = store();
    when(store.recallByKeyword(any()))
        .thenThrow(new UncheckedIOException("db locked", new IOException()));

    assertThatThrownBy(() -> toolsWith(store).recallMemory("SQLite"))
        .isInstanceOf(UncheckedIOException.class);
  }

  // ---------------------------------------------------------------- 注解契约（US-4 改造点）

  @Test
  @DisplayName("改标 @Tool 后工具名与参数名逐字不变（save_memory / recall_memory、content / scope / query）")
  void annotatedTools_keepContractNamesAndParams() {
    Map<String, ToolCallback> callbacks = callbacksByToolName();

    assertThat(callbacks).containsOnlyKeys("save_memory", "recall_memory");

    JsonNode saveSchema = args(callbacks.get("save_memory").getToolDefinition().inputSchema());
    assertThat(propertyNames(saveSchema)).containsExactlyInAnyOrder("content", "scope");
    assertThat(saveSchema.path("required").toString()).contains("\"content\"");
    assertThat(saveSchema.path("properties").path("scope").path("description").asText())
        .contains("CORE")
        .contains("ARCHIVAL"); // 注解 schema 表达不了 enum，合法取值只在描述里（见 MemoryTools javadoc）

    JsonNode recallSchema = args(callbacks.get("recall_memory").getToolDefinition().inputSchema());
    assertThat(propertyNames(recallSchema)).containsExactly("query");
    assertThat(recallSchema.path("required").toString()).contains("\"query\"");
  }

  @Test
  @DisplayName("工具描述随注解生成，逐字对齐契约（AGENT.md 按名引用）")
  void annotatedTools_descriptions_matchContract() {
    Map<String, ToolCallback> callbacks = callbacksByToolName();

    assertThat(callbacks.get("save_memory").getToolDefinition().description()).contains("长期记忆");
    assertThat(callbacks.get("recall_memory").getToolDefinition().description()).contains("核心区");
  }

  private Map<String, ToolCallback> callbacksByToolName() {
    return Arrays.stream(
            MethodToolCallbackProvider.builder()
                .toolObjects(toolsWith(store()))
                .build()
                .getToolCallbacks())
        .collect(
            Collectors.toMap(callback -> callback.getToolDefinition().name(), Function.identity()));
  }

  private static List<String> propertyNames(JsonNode schema) {
    List<String> names = new ArrayList<>();
    schema.path("properties").fieldNames().forEachRemaining(names::add);
    return names;
  }
}
