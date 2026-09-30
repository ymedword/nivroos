package com.nivroos.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.nivroos.core.memory.MemoryScope;
import com.nivroos.core.memory.MemoryService;
import com.nivroos.core.model.JsonSchema;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolResult;
import java.util.List;

/**
 * 长期记忆内置工具（技术方案 §6.2，契约 contracts/memory-tools.md）。
 *
 * <p>Exposes the facade to the model as two plain {@link NivroTool}s - hand-written name/schema, no
 * {@code @Tool} annotation yet (US-4 switches the registration mechanism). The tools only translate
 * model arguments into facade calls: audit, retry and failure recording stay in the existing
 * ToolExecutor path, so this module adds no audit path of its own.
 */
public class MemoryTools {

  /** 输入 schema 逐字对齐契约（工具名与参数名是已定字面量，改一处即破坏 AGENT.md 引用）。 */
  private static final String SAVE_MEMORY_SCHEMA =
      "{\"type\":\"object\",\"properties\":{"
          + "\"content\":{\"type\":\"string\",\"description\":\"要记住的内容\"},"
          + "\"scope\":{\"type\":\"string\",\"enum\":[\"CORE\",\"ARCHIVAL\"],"
          + "\"description\":\"写入分区：CORE 核心区（长期有效、每轮注入、永不截断），ARCHIVAL 归档区（可检索、超限截断）。省略时按 ARCHIVAL\"}"
          + "},\"required\":[\"content\"]}";

  private static final String RECALL_MEMORY_SCHEMA =
      "{\"type\":\"object\",\"properties\":{"
          + "\"query\":{\"type\":\"string\",\"description\":\"检索关键词\"}"
          + "},\"required\":[\"query\"]}";

  private final MemoryService memoryService;

  public MemoryTools(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  public NivroTool saveMemory() {
    return new SaveMemoryTool();
  }

  public NivroTool recallMemory() {
    return new RecallMemoryTool();
  }

  private final class SaveMemoryTool implements NivroTool {

    @Override
    public String getName() {
      return "save_memory";
    }

    @Override
    public String getDescription() {
      return "写入长期记忆（核心区每轮全量注入且永不截断；归档区可被关键词检索）。";
    }

    @Override
    public JsonSchema getInputSchema() {
      return new JsonSchema(SAVE_MEMORY_SCHEMA);
    }

    @Override
    public ToolResult execute(JsonNode input) {
      String content = input.path("content").asText("");
      if (content.isBlank()) {
        // 契约：content 缺失/空 → 失败结果，由 ToolExecutor 落失败审计
        return new ToolResult(false, null, "save_memory 需要非空的 content 参数", false);
      }

      String rawScope = input.path("scope").asText("");
      MemoryScope scope;
      if (rawScope.isBlank()) {
        // 省略 → 让 MemoryService 按契约默认 ARCHIVAL（默认值只此一处定义，避免两处漂移）
        scope = null;
      } else if ("CORE".equals(rawScope)) {
        scope = MemoryScope.CORE;
      } else if ("ARCHIVAL".equals(rawScope)) {
        scope = MemoryScope.ARCHIVAL;
      } else {
        // 契约：非法取值不静默按默认处理
        return new ToolResult(false, null, "非法 scope: " + rawScope + "（合法取值：CORE、ARCHIVAL）", false);
      }

      memoryService.save(content, scope); // 存储失败上抛，由 ToolExecutor 落失败审计（FR-012）
      return new ToolResult(true, scope == MemoryScope.CORE ? "已写入核心记忆区" : "已写入归档记忆区", null, false);
    }
  }

  private final class RecallMemoryTool implements NivroTool {

    @Override
    public String getName() {
      return "recall_memory";
    }

    @Override
    public String getDescription() {
      return "按关键词检索归档记忆（核心区每轮已全量注入，不参与检索）。";
    }

    @Override
    public JsonSchema getInputSchema() {
      return new JsonSchema(RECALL_MEMORY_SCHEMA);
    }

    @Override
    public ToolResult execute(JsonNode input) {
      String query = input.path("query").asText("");
      if (query.isBlank()) {
        return new ToolResult(false, null, "recall_memory 需要非空的 query 参数", false);
      }
      List<String> hits = memoryService.recall(query); // 存储失败上抛，由 ToolExecutor 落失败审计
      if (hits.isEmpty()) {
        // 「无匹配」文案归工具层：store 只回答有没有，措辞不能散落三个后端（也不回显关键词，
        // 否则模型会把回显当成命中内容）
        return new ToolResult(true, "无匹配：归档记忆中没有包含该关键词的内容", null, false);
      }
      return new ToolResult(true, String.join("\n", hits), null, false);
    }
  }
}
