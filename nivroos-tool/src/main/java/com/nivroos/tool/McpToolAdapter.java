package com.nivroos.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nivroos.core.model.JsonSchema;
import com.nivroos.core.model.NivroTool;
import com.nivroos.core.model.ToolResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 工具 → {@link NivroTool} 适配（技术方案 §6.4；契约 contracts/mcp-and-notify.md §2）。
 *
 * <p>Wraps a tool advertised by a stdio MCP server so the ReAct loop cannot tell tool sources apart
 * (FR-002): name, description and schema come from the server, execution is a {@code tool/call}
 * forwarded over the already-initialized client. 失败按契约 §0.2 分两路：业务失败（server 回 {@code
 * isError}）返回失败结果并回填文本，传输层异常（子进程死了）上抛给 {@code ToolExecutor} ——两条路都有 WARN 日志与审计，模型侧都拿得到原因。
 *
 * <p>Jackson 2（项目侧 {@code JsonNode}）与调试 SDK 侧的编解码桥接**只允许出现在本类**：参数先 递归拆成纯 Map / List / 标量再交给 SDK，否则
 * Jackson 3 会把 Jackson 2 的节点当普通 Bean 序列化 成垃圾（颗粒度文档 §3.1）。
 */
public class McpToolAdapter implements NivroTool {

  private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String serverName;
  private final McpSchema.Tool tool;
  private final McpSyncClient client;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "client 是启动期连好的长寿命连接 bean（同一 server 的工具共用一个），字段私有且无访问器外泄")
  public McpToolAdapter(String serverName, McpSchema.Tool tool, McpSyncClient client) {
    this.serverName = serverName;
    this.tool = tool;
    this.client = client;
  }

  @Override
  public String getName() {
    return tool.name();
  }

  @Override
  public String getDescription() {
    return tool.description();
  }

  @Override
  public JsonSchema getInputSchema() {
    return new JsonSchema(schemaJson(tool.inputSchema()));
  }

  /**
   * 转发一次 {@code tool/call}。
   *
   * <p>{@code isError} 是 server 给的业务失败（上游 403、参数不合规等）：映射成失败结果并把错误 文本回填给模型，模型据此换路径收敛（关键回归）。
   */
  @Override
  public ToolResult execute(JsonNode input) {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(getName(), arguments(input));
    McpSchema.CallToolResult result = client.callTool(request);
    String text = textOf(result);
    if (Boolean.TRUE.equals(result.isError())) {
      log.warn(
          "mcp tool returned an error: server={}, tool={}, reason={}",
          sanitizeForLog(serverName),
          sanitizeForLog(getName()),
          sanitizeForLog(text));
      return new ToolResult(false, null, text, false);
    }
    return new ToolResult(true, text, null, false);
  }

  /** 结果文本：文本内容拼接，其它内容类型退化为其字符串形态（核心阶段只消费文本语义）。 */
  private static String textOf(McpSchema.CallToolResult result) {
    if (result == null || result.content() == null || result.content().isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (McpSchema.Content content : result.content()) {
      if (content instanceof McpSchema.TextContent textContent) {
        text.append(textContent.text());
      } else {
        text.append(content);
      }
    }
    return text.toString();
  }

  /**
   * 入参转换：Jackson 2 节点 → 纯 Map / List / 标量。
   *
   * <p>The SDK serializes the arguments map with its own mapper (Jackson 3 in 1.1.x), which cannot
   * serialize Jackson 2 nodes - flattening here keeps the bridge in one place and mapper-agnostic.
   */
  private static Map<String, Object> arguments(JsonNode input) {
    if (input == null || input.isNull() || !input.isObject()) {
      return Map.of();
    }
    Map<String, Object> arguments = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> field : input.properties()) {
      arguments.put(field.getKey(), plainValue(field.getValue()));
    }
    return arguments;
  }

  private static Object plainValue(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isObject()) {
      Map<String, Object> map = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        map.put(field.getKey(), plainValue(field.getValue()));
      }
      return map;
    }
    if (node.isArray()) {
      List<Object> list = new ArrayList<>();
      node.forEach(element -> list.add(plainValue(element)));
      return list;
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isIntegralNumber()) {
      return node.longValue();
    }
    if (node.isFloatingPointNumber()) {
      return node.doubleValue();
    }
    return node.asText();
  }

  /**
   * SDK schema → JSON 字符串；server 未给 schema 时补空对象 schema。
   *
   * <p>A null schema would make the whole LLM request fail for every tool, not just this one （关键回归
   * ⑥），so the fallback is an object schema with no properties rather than null.
   */
  private static String schemaJson(McpSchema.JsonSchema schema) {
    ObjectNode root = MAPPER.createObjectNode();
    if (schema == null) {
      root.put("type", "object");
      root.set("properties", MAPPER.createObjectNode());
    } else {
      root.put("type", schema.type() == null ? "object" : schema.type());
      root.set(
          "properties",
          schema.properties() == null
              ? MAPPER.createObjectNode()
              : MAPPER.valueToTree(schema.properties()));
      if (schema.required() != null && !schema.required().isEmpty()) {
        root.set("required", MAPPER.valueToTree(schema.required()));
      }
      if (schema.additionalProperties() != null) {
        root.put("additionalProperties", schema.additionalProperties());
      }
      // $defs / definitions 必须原样带上：properties 里的 $ref 指向它们，丢掉就成了悬空引用
      if (schema.defs() != null) {
        root.set("$defs", MAPPER.valueToTree(schema.defs()));
      }
      if (schema.definitions() != null) {
        root.set("definitions", MAPPER.valueToTree(schema.definitions()));
      }
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("MCP tool schema is not serializable: " + schema, e);
    }
  }

  /** 日志参数 CRLF 消毒（findsecbugs；上游返回的文本可能带换行）。 */
  private static String sanitizeForLog(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
