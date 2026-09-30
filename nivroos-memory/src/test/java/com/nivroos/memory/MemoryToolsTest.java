package com.nivroos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolCallRequest;
import com.nivroos.core.model.ToolResult;
import com.nivroos.core.provider.ToolInvocationStore;
import com.nivroos.core.react.ToolExecutor;
import com.nivroos.core.session.SessionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MemoryTools 验收点：颗粒度文档 §4.2 + 契约 contracts/memory-tools.md（两个工具段）。 */
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
    NivroTool tool = toolsWith(store).saveMemory();

    ToolResult result =
        tool.execute(args("{\"content\":\"用户项目用 Spring Boot\",\"scope\":\"CORE\"}"));

    assertThat(result.success()).isTrue();
    verify(store).append("用户项目用 Spring Boot", MemoryScope.CORE);
  }

  @Test
  @DisplayName("content + scope: ARCHIVAL → 追加到归档区")
  void saveMemory_scopeArchival_writesToArchival() {
    LongTermMemoryStore store = store();
    NivroTool tool = toolsWith(store).saveMemory();

    ToolResult result = tool.execute(args("{\"content\":\"上次讨论过 SQLite\",\"scope\":\"ARCHIVAL\"}"));

    assertThat(result.success()).isTrue();
    verify(store).append("上次讨论过 SQLite", MemoryScope.ARCHIVAL);
  }

  @Test
  @DisplayName("省略 scope → 默认归档区（FR-006）")
  void saveMemory_defaultsToArchivalWhenScopeOmitted() {
    LongTermMemoryStore store = store();
    NivroTool tool = toolsWith(store).saveMemory();

    ToolResult result = tool.execute(args("{\"content\":\"随手记一条\"}"));

    assertThat(result.success()).isTrue();
    verify(store).append("随手记一条", MemoryScope.ARCHIVAL);
  }

  // ---------------------------------------------------------------- 契约表：失败分支

  @Test
  @DisplayName("content 缺失 → 失败结果，不写存储")
  void saveMemory_missingContent_failsWithoutWriting() {
    LongTermMemoryStore store = store();
    NivroTool tool = toolsWith(store).saveMemory();

    ToolResult result = tool.execute(args("{\"scope\":\"CORE\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).isNotBlank();
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("content 为空串 → 失败结果，不写存储")
  void saveMemory_blankContent_failsWithoutWriting() {
    LongTermMemoryStore store = store();
    NivroTool tool = toolsWith(store).saveMemory();

    ToolResult result = tool.execute(args("{\"content\":\"   \"}"));

    assertThat(result.success()).isFalse();
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("scope 非法 → 失败结果，不静默按默认处理")
  void saveMemory_illegalScope_failsInsteadOfFallingBack() {
    LongTermMemoryStore store = store();
    NivroTool tool = toolsWith(store).saveMemory();

    ToolResult result = tool.execute(args("{\"content\":\"一条记忆\",\"scope\":\"PERMANENT\"}"));

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
    NivroTool tool = toolsWith(store).saveMemory();

    assertThatThrownBy(() -> tool.execute(args("{\"content\":\"写不进去\"}")))
        .isInstanceOf(UncheckedIOException.class);
  }

  // ---------------------------------------------------------------- 审计（继承 ToolExecutor 机制，不新增路径）

  @Test
  @DisplayName("经 ToolExecutor 执行 → tool_invocations 收到 success=true 记录")
  void saveMemory_viaToolExecutor_recordsSuccessAudit() {
    LongTermMemoryStore store = store();
    ToolInvocationStore audit = mock(ToolInvocationStore.class);
    ToolExecutor executor =
        new ToolExecutor(Map.of("save_memory", toolsWith(store).saveMemory()), audit);

    ToolResult result =
        executor.execute(
            "cli:u:weather",
            new ToolCallRequest(
                "save_memory", "{\"content\":\"偏好：Spring Boot\",\"scope\":\"CORE\"}"));

    assertThat(result.success()).isTrue();
    verify(audit)
        .record(
            eq("cli:u:weather"),
            eq("save_memory"),
            any(),
            any(),
            eq(true),
            org.mockito.ArgumentMatchers.isNull(),
            anyLong());
  }

  @Test
  @DisplayName("存储失败经 ToolExecutor → 落失败审计并带 error_message")
  void saveMemory_viaToolExecutor_recordsFailureAudit() {
    LongTermMemoryStore store = store();
    doThrow(new UncheckedIOException("read-only fs", new IOException()))
        .when(store)
        .append(any(), any());
    ToolInvocationStore audit = mock(ToolInvocationStore.class);
    ToolExecutor executor =
        new ToolExecutor(Map.of("save_memory", toolsWith(store).saveMemory()), audit);

    ToolResult result =
        executor.execute(
            "cli:u:weather", new ToolCallRequest("save_memory", "{\"content\":\"写不进去\"}"));

    assertThat(result.success()).isFalse();
    verify(audit)
        .record(
            eq("cli:u:weather"),
            eq("save_memory"),
            any(),
            org.mockito.ArgumentMatchers.isNull(),
            eq(false),
            org.mockito.ArgumentMatchers.contains("read-only fs"),
            anyLong());
  }

  // ---------------------------------------------------------------- recall_memory 契约表

  @Test
  @DisplayName("query 命中归档区 → 命中行以换行连接")
  void recallMemory_hits_joinsLinesWithNewline() {
    LongTermMemoryStore store = store();
    when(store.recallByKeyword("SQLite"))
        .thenReturn(List.of("上次讨论过使用 SQLite 作为本地存储", "SQLite 的 WAL 模式已开启"));
    NivroTool tool = toolsWith(store).recallMemory();

    ToolResult result = tool.execute(args("{\"query\":\"SQLite\"}"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("上次讨论过使用 SQLite 作为本地存储\nSQLite 的 WAL 模式已开启");
  }

  @Test
  @DisplayName("query 无命中 → 返回「无匹配」文案（不返回编造内容、不返回空串）")
  void recallMemory_noMatch_returnsPlaceholder() {
    LongTermMemoryStore store = store();
    when(store.recallByKeyword(any())).thenReturn(List.of());
    NivroTool tool = toolsWith(store).recallMemory();

    ToolResult result = tool.execute(args("{\"query\":\"量子计算\"}"));

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
    NivroTool tool = toolsWith(realStore).recallMemory();

    ToolResult result = tool.execute(args("{\"query\":\"Spring Boot\"}"));

    assertThat(result.content()).contains("无匹配").doesNotContain("Spring Boot");
  }

  @Test
  @DisplayName("query 缺失 → 失败结果，不查存储")
  void recallMemory_missingQuery_fails() {
    LongTermMemoryStore store = store();
    NivroTool tool = toolsWith(store).recallMemory();

    ToolResult result = tool.execute(args("{}"));

    assertThat(result.success()).isFalse();
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("query 为空串 → 失败结果，不查存储")
  void recallMemory_blankQuery_fails() {
    LongTermMemoryStore store = store();
    NivroTool tool = toolsWith(store).recallMemory();

    ToolResult result = tool.execute(args("{\"query\":\"  \"}"));

    assertThat(result.success()).isFalse();
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("存储 IO 失败 → 异常上抛，由 ToolExecutor 落失败审计")
  void recallMemory_storeFailure_propagates() {
    LongTermMemoryStore store = store();
    when(store.recallByKeyword(any()))
        .thenThrow(new UncheckedIOException("db locked", new IOException()));
    NivroTool tool = toolsWith(store).recallMemory();

    assertThatThrownBy(() -> tool.execute(args("{\"query\":\"SQLite\"}")))
        .isInstanceOf(UncheckedIOException.class);
  }

  // ---------------------------------------------------------------- 契约字面量

  @Test
  @DisplayName("工具名与描述逐字对齐契约（AGENT.md 按名引用）")
  void saveMemory_nameAndDescription_matchContract() {
    NivroTool tool = toolsWith(store()).saveMemory();

    assertThat(tool.getName()).isEqualTo("save_memory");
    assertThat(tool.getDescription()).contains("长期记忆");
  }

  @Test
  @DisplayName("输入 schema 声明 content 必填、scope 枚举 CORE/ARCHIVAL")
  void saveMemory_inputSchema_declaresContractShape() {
    JsonSchemaAssert.of(toolsWith(store()).saveMemory().getInputSchema().value())
        .hasRequired("content")
        .hasEnum("scope", "CORE", "ARCHIVAL");
  }

  @Test
  @DisplayName("recall_memory 工具名与 schema 逐字对齐契约")
  void recallMemory_nameAndSchema_matchContract() {
    NivroTool tool = toolsWith(store()).recallMemory();

    assertThat(tool.getName()).isEqualTo("recall_memory");
    assertThat(tool.getDescription()).contains("核心区");
    JsonSchemaAssert.of(tool.getInputSchema().value()).hasRequired("query");
  }

  /** 断言辅助：schema 是字符串，逐项判定而非整体字符串比对（排版差异不致误报）。 */
  private static final class JsonSchemaAssert {

    private final JsonNode schema;

    private JsonSchemaAssert(String schemaJson) {
      this.schema = args(schemaJson);
    }

    static JsonSchemaAssert of(String schemaJson) {
      return new JsonSchemaAssert(schemaJson);
    }

    JsonSchemaAssert hasRequired(String field) {
      assertThat(schema.path("required").toString()).contains("\"" + field + "\"");
      return this;
    }

    JsonSchemaAssert hasEnum(String field, String... values) {
      JsonNode enumNode = schema.path("properties").path(field).path("enum");
      assertThat(enumNode.isArray()).isTrue();
      for (String value : values) {
        assertThat(enumNode.toString()).contains("\"" + value + "\"");
      }
      return this;
    }
  }
}
